package com.u1.servicepal.internal.macos;

import com.dd.plist.NSDictionary;
import com.u1.servicepal.Capabilities;
import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceNotFoundException;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.internal.Backend;
import com.u1.servicepal.internal.exec.DefaultCommandRunner;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * macOS/launchd backend, fully implemented (discovery + inspection + mutation). Discovery
 * enumerates {@code .plist} files in the standard locations and reads live state with
 * domain-targeted {@code launchctl print}; files it can't read (permissions or malformed)
 * are reported, not silently dropped. Mutation writes the plist via {@link PlistWriter} and
 * drives the modern {@code launchctl} subcommands (bootstrap/bootout/kickstart/kill/enable).
 */
public final class LaunchdBackend implements Backend {

	// `launchctl bootout` is asynchronous; a `bootstrap` issued before the old instance has finished
	// unloading races the teardown and fails with "Bootstrap failed: 5: Input/output error". Retry
	// the bootstrap, backing off, to let the teardown complete. The budget (8 attempts, 300ms ×
	// attempt → ~8.4s total) comfortably outlasts a service that is a few seconds slow to stop.
	private static final int BOOTSTRAP_ATTEMPTS = 8;
	private static final long BOOTSTRAP_BACKOFF_MS = 300L;

	private final Launchctl launchctl;
	private final List<LaunchdDir> directories;
	private final PlistReader reader = new PlistReader();
	private final PlistWriter writer = new PlistWriter();

	public LaunchdBackend(final Launchctl launchctl, final List<LaunchdDir> directories) {
		this.launchctl = launchctl;
		this.directories = List.copyOf(directories);
	}

	/** Real macOS wiring: standard launchd directories + {@code launchctl} subprocess. */
	public static LaunchdBackend createDefault() {
		final Path home = Path.of(System.getProperty("user.home", ""));
		final List<LaunchdDir> dirs = List.of(
				new LaunchdDir(home.resolve("Library/LaunchAgents"),
						Installation.PER_USER, LaunchdDomain.GUI),
				new LaunchdDir(Path.of("/Library/LaunchDaemons"),
						Installation.SYSTEM_WIDE, LaunchdDomain.SYSTEM),
				new LaunchdDir(Path.of("/Library/LaunchAgents"),
						Installation.SYSTEM_WIDE, LaunchdDomain.GUI));
		return new LaunchdBackend(new DefaultLaunchctl(new DefaultCommandRunner()), dirs);
	}

	@Override
	public Platform platform() {
		return Platform.MACOS_LAUNCHD;
	}

	@Override
	public Capabilities capabilities() {
		return new Capabilities(true, true, true, true, true, true, true, true, false);
	}

	@Override
	public List<Installation> supportedInstallations() {
		final List<Installation> result = new ArrayList<>();
		for (final Installation candidate : List.of(Installation.PER_USER, Installation.SYSTEM_WIDE)) {
			for (final LaunchdDir dir : directories) {
				if (dir.installation() == candidate && !result.contains(candidate)) {
					result.add(candidate);
					break;
				}
			}
		}
		return result;
	}

	@Override
	public Discovery discover(final Installation installation) {
		final List<ServiceStatus> services = new ArrayList<>();
		final List<String> unreadable = new ArrayList<>();
		for (final LaunchdDir dir : directories) {
			if (dir.installation() != installation || !Files.isDirectory(dir.dir())) {
				continue;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.dir(), "*.plist")) {
				for (final Path file : stream) {
					try {
						services.add(buildStatus(reader.parseFile(file), file, dir));
					} catch (final DefinitionIOException e) {
						unreadable.add(file.toString());
					}
				}
			} catch (final IOException e) {
				// A whole unreadable directory shouldn't abort discovery; skip it.
			}
		}
		return new Discovery(services, unreadable);
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		final Located located = find(id, installation);
		if (located == null) {
			return null;
		}
		return reader.toSpec(reader.parseFile(located.file()), installation, stem(located.file()));
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		final Located located = find(id, installation);
		if (located == null) {
			return null;
		}
		try {
			return Files.readString(located.file());
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read " + located.file(), e);
		}
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		final Located located = find(id, installation);
		if (located == null) {
			return null;
		}
		return buildStatus(reader.parseFile(located.file()), located.file(), located.dir());
	}

	// --- mutation ---

	@Override
	public void install(final ServiceSpec spec, final boolean overwriteUnmanaged) {
		final Installation installation = spec.runAs().installation();
		final LaunchdDir target = writeTarget(installation);
		final Path file = target.dir().resolve(spec.id() + ".plist");

		// Decide provenance: keep our own marker(s) when re-installing one of ours; mark an
		// adoption when we install over a service we did not create (only allowed with the override).
		boolean adopted = false;
		if (Files.isRegularFile(file)) {
			NSDictionary existing = null;
			try {
				existing = reader.parseFile(file);
			} catch (final DefinitionIOException e) {
				existing = null;   // unreadable/foreign
			}
			final boolean existingManaged = existing != null && reader.isManaged(existing);
			if (!existingManaged && !overwriteUnmanaged) {
				throw new UnmanagedServiceException(spec.id());
			}
			adopted = existingManaged ? reader.isAdopted(existing) : true;
		}

		try {
			Files.createDirectories(target.dir());
			Files.writeString(file, writer.render(spec, adopted));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write " + file, e);
		}

		// Upsert: unload any loaded instance, then (re)load the freshly written plist.
		reload(target, spec.id(), file);
	}

	/**
	 * Reload a (possibly already-loaded) service: {@code bootout} any current instance, then
	 * {@code bootstrap} the written plist. Because {@code bootout} is asynchronous, a bootstrap
	 * issued immediately can race the still-running teardown and fail with EIO ("Bootstrap failed:
	 * 5: Input/output error") — which, left unhandled, also leaves the service booted out so a
	 * follow-up start cannot find it. Retry the bootstrap on that transient error, backing off to
	 * give the teardown time to finish. A user-owned ({@code gui/<uid>}) agent reloads without root.
	 */
	private void reload(final LaunchdDir target, final String id, final Path file) {
		try {
			launchctl.bootout(target.domain(), id);
		} catch (final NativeCommandException ignored) {
			// not currently loaded — fine
		}
		for (int attempt = 1; attempt <= BOOTSTRAP_ATTEMPTS; attempt++) {
			try {
				launchctl.bootstrap(target.domain(), file);
				return;
			} catch (final NativeCommandException e) {
				if (attempt == BOOTSTRAP_ATTEMPTS || !isTransientReloadError(e)) {
					throw e;
				}
				sleepQuietly(BOOTSTRAP_BACKOFF_MS * attempt);
			}
		}
	}

	/**
	 * Whether a {@code bootstrap} failure looks like the transient bootout-still-in-flight race
	 * (worth retrying) rather than a real error (e.g. a bad plist or a permission problem). Matches
	 * both the exit code and the message, since the wording varies across macOS versions.
	 */
	static boolean isTransientReloadError(final NativeCommandException e) {
		if (e.exitCode() == 5 || e.exitCode() == 37) {   // EIO / EBUSY
			return true;
		}
		final String s = (e.stderr() != null ? e.stderr() : e.getMessage())
				.toLowerCase(java.util.Locale.ROOT);
		return s.contains("input/output error")
				|| s.contains("operation already in progress")
				|| s.contains("operation now in progress")
				|| s.contains("service already loaded")
				|| s.contains("already bootstrapped");
	}

	private static void sleepQuietly(final long millis) {
		try {
			Thread.sleep(millis);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		final Located located = find(id, installation);
		if (located == null) {
			throw new ServiceNotFoundException(id);
		}
		if (!unmanagedOk) {
			try {
				if (!reader.isManaged(reader.parseFile(located.file()))) {
					throw new UnmanagedServiceException(id);
				}
			} catch (final DefinitionIOException e) {
				throw new UnmanagedServiceException(id);
			}
		}
		try {
			launchctl.bootout(located.dir().domain(), id);
		} catch (final NativeCommandException ignored) {
			// not loaded — proceed to delete the definition anyway
		}
		try {
			Files.deleteIfExists(located.file());
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to delete " + located.file(), e);
		}
	}

	@Override
	public void enable(final String id, final Installation installation) {
		final Located located = require(id, installation);
		launchctl.enable(located.dir().domain(), id);
	}

	@Override
	public void disable(final String id, final Installation installation) {
		final Located located = require(id, installation);
		launchctl.disable(located.dir().domain(), id);
	}

	@Override
	public void start(final String id, final Installation installation) {
		final Located located = require(id, installation);
		launchctl.kickstart(located.dir().domain(), id, false);
	}

	@Override
	public void stop(final String id, final Installation installation) {
		final Located located = require(id, installation);
		launchctl.killService(located.dir().domain(), id, "SIGTERM");
	}

	@Override
	public void restart(final String id, final Installation installation) {
		final Located located = require(id, installation);
		launchctl.kickstart(located.dir().domain(), id, true);
	}

	private Located require(final String id, final Installation installation) {
		final Located located = find(id, installation);
		if (located == null) {
			throw new ServiceNotFoundException(id);
		}
		return located;
	}

	/** The canonical directory we WRITE to for an installation: the first configured dir for
	 * that installation (LaunchDaemons for SYSTEM_WIDE, the user's LaunchAgents for PER_USER). */
	private LaunchdDir writeTarget(final Installation installation) {
		for (final LaunchdDir dir : directories) {
			if (dir.installation() == installation) {
				return dir;
			}
		}
		throw new com.u1.servicepal.UnsupportedFeatureException(
				"installation " + installation, Platform.MACOS_LAUNCHD);
	}

	private ServiceStatus buildStatus(final NSDictionary dict, final Path file,
			final LaunchdDir dir) {
		final String label = reader.label(dict);
		final String id = label != null ? label : stem(file);
		final boolean managed = reader.isManaged(dict);
		final boolean adopted = managed && reader.isAdopted(dict);
		final boolean enabled = reader.runAtLoad(dict);
		final ServiceRuntime rt = launchctl.runtime(dir.domain(), id);
		return new ServiceStatus(id, dir.installation(), true, enabled, managed, adopted, rt.state(),
				rt.pid(), rt.lastExitCode(), null);
	}

	/** Locate the plist (and its directory) for an id: by filename first, then by Label scan. */
	private Located find(final String id, final Installation installation) {
		for (final LaunchdDir dir : directories) {
			if (dir.installation() != installation) {
				continue;
			}
			final Path candidate = dir.dir().resolve(id + ".plist");
			if (Files.isRegularFile(candidate)) {
				return new Located(candidate, dir);
			}
		}
		for (final LaunchdDir dir : directories) {
			if (dir.installation() != installation || !Files.isDirectory(dir.dir())) {
				continue;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir.dir(), "*.plist")) {
				for (final Path file : stream) {
					try {
						if (id.equals(reader.label(reader.parseFile(file)))) {
							return new Located(file, dir);
						}
					} catch (final DefinitionIOException e) {
						// skip unparseable file
					}
				}
			} catch (final IOException e) {
				// skip unreadable dir
			}
		}
		return null;
	}

	private static String stem(final Path file) {
		final String name = file.getFileName().toString();
		return name.endsWith(".plist") ? name.substring(0, name.length() - ".plist".length()) : name;
	}

	private record Located(Path file, LaunchdDir dir) {
	}
}
