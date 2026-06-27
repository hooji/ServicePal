package com.u1.servicepal.internal.macos;

import com.dd.plist.NSDictionary;
import com.u1.servicepal.Capabilities;
import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
import com.u1.servicepal.internal.Backend;
import com.u1.servicepal.internal.exec.DefaultCommandRunner;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * macOS/launchd backend. This increment implements discovery + inspection: it enumerates
 * {@code .plist} files in the standard locations and enriches them with live job state from
 * {@code launchctl list}. Mutation (bootstrap/bootout/kickstart and the plist writer) is step 4.
 */
public final class LaunchdBackend implements Backend {

	private final Launchctl launchctl;
	private final Map<Installation, List<Path>> directories;
	private final PlistReader reader = new PlistReader();

	public LaunchdBackend(final Launchctl launchctl,
			final Map<Installation, List<Path>> directories) {
		this.launchctl = launchctl;
		this.directories = directories;
	}

	/** Real macOS wiring: standard launchd directories + {@code launchctl} subprocess. */
	public static LaunchdBackend createDefault() {
		final Path home = Path.of(System.getProperty("user.home", ""));
		final Map<Installation, List<Path>> dirs = Map.of(
				Installation.PER_USER,
				List.of(home.resolve("Library/LaunchAgents")),
				Installation.SYSTEM_WIDE,
				List.of(Path.of("/Library/LaunchDaemons"), Path.of("/Library/LaunchAgents")));
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
		return List.of(Installation.PER_USER, Installation.SYSTEM_WIDE);
	}

	@Override
	public List<ServiceStatus> list(final Installation installation) {
		final Map<String, JobInfo> jobs = launchctl.listJobs();
		final List<ServiceStatus> out = new ArrayList<>();
		for (final Path dir : directories.getOrDefault(installation, List.of())) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.plist")) {
				for (final Path file : stream) {
					final ServiceStatus status = tryStatus(file, installation, jobs);
					if (status != null) {
						out.add(status);
					}
				}
			} catch (final IOException e) {
				// A whole unreadable directory shouldn't abort discovery; skip it.
				continue;
			}
		}
		return out;
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		final Path file = findPlist(id, installation);
		if (file == null) {
			return null;
		}
		final NSDictionary dict = reader.parseFile(file);
		return reader.toSpec(dict, installation, stem(file));
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		final Path file = findPlist(id, installation);
		if (file == null) {
			return null;
		}
		try {
			return Files.readString(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read " + file, e);
		}
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		final Path file = findPlist(id, installation);
		if (file == null) {
			return null;
		}
		return tryStatus(file, installation, launchctl.listJobs());
	}

	/** Build a status for one plist, or {@code null} if it can't be parsed (resilient). */
	private ServiceStatus tryStatus(final Path file, final Installation installation,
			final Map<String, JobInfo> jobs) {
		final NSDictionary dict;
		try {
			dict = reader.parseFile(file);
		} catch (final DefinitionIOException e) {
			return null;
		}
		final String label = reader.label(dict);
		final String id = label != null ? label : stem(file);
		final boolean managed = reader.isManaged(dict);
		final boolean enabled = reader.runAtLoad(dict);
		final JobInfo job = jobs.get(id);
		final RunState state = stateOf(job);
		final Integer pid = job == null ? null : job.pid();
		final Integer lastExit = job == null ? null : job.lastStatus();
		return new ServiceStatus(id, installation, true, enabled, managed, state, pid, lastExit,
				null);
	}

	private static RunState stateOf(final JobInfo job) {
		if (job == null) {
			return RunState.STOPPED;
		}
		if (job.pid() != null && job.pid() > 0) {
			return RunState.RUNNING;
		}
		return RunState.STOPPED;
	}

	/** Locate the plist for an id: by {@code <id>.plist} filename first, then by Label scan. */
	private Path findPlist(final String id, final Installation installation) {
		final List<Path> dirs = directories.getOrDefault(installation, List.of());
		for (final Path dir : dirs) {
			final Path candidate = dir.resolve(id + ".plist");
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		for (final Path dir : dirs) {
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.plist")) {
				for (final Path file : stream) {
					try {
						if (id.equals(reader.label(reader.parseFile(file)))) {
							return file;
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
}
