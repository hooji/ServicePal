package com.u1.servicepal.internal.macos;

import com.dd.plist.NSDictionary;
import com.u1.servicepal.Capabilities;
import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
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
