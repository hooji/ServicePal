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
 * macOS/launchd backend. This increment implements discovery + inspection: it enumerates
 * {@code .plist} files in the standard locations and reads live state with domain-targeted
 * {@code launchctl print}. Files it can't read (permissions or malformed) are reported, not
 * silently dropped. Mutation (bootstrap/bootout/kickstart and the plist writer) is step 4.
 */
public final class LaunchdBackend implements Backend {

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

		if (Files.isRegularFile(file) && !overwriteUnmanaged) {
			try {
				if (!reader.isManaged(reader.parseFile(file))) {
					throw new UnmanagedServiceException(spec.id());
				}
			} catch (final DefinitionIOException e) {
				throw new UnmanagedServiceException(spec.id());
			}
		}

		try {
			Files.createDirectories(target.dir());
			Files.writeString(file, writer.render(spec));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write " + file, e);
		}

		// Upsert: if a previous instance is loaded, unload before (re)loading. Ignore the
		// "not loaded" failure of the first bootout.
		try {
			launchctl.bootout(target.domain(), spec.id());
		} catch (final NativeCommandException ignored) {
			// not currently loaded — fine
		}
		launchctl.bootstrap(target.domain(), file);
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
		final boolean enabled = reader.runAtLoad(dict);
		final ServiceRuntime rt = launchctl.runtime(dir.domain(), id);
		return new ServiceStatus(id, dir.installation(), true, enabled, managed, rt.state(),
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
