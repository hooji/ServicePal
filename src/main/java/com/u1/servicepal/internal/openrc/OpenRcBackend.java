package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceNotFoundException;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.UnsupportedFeatureException;
import com.u1.servicepal.internal.Backend;
import com.u1.servicepal.internal.exec.DefaultCommandRunner;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import com.u1.servicepal.model.options.OpenRcOptions;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Linux/OpenRC backend. Writes init scripts to {@code /etc/init.d} and drives
 * {@code rc-service} / {@code rc-update}. OpenRC has no per-user services and no native
 * scheduler, so it is SYSTEM_WIDE-only and reports {@code calendar}/{@code interval} as
 * unsupported (scheduled specs fail fast in the facade). There is no {@code daemon-reload}
 * equivalent — OpenRC reads the script on demand — so install/uninstall is just file I/O plus
 * lifecycle commands.
 */
public final class OpenRcBackend implements Backend {

	private final RcService rc;
	private final Path initdDir;
	private final Path runlevelsDir;
	private final Path runDir;
	private final OpenRcScriptReader reader = new OpenRcScriptReader();
	private final OpenRcScriptWriter writer = new OpenRcScriptWriter();

	public OpenRcBackend(final RcService rc, final Path initdDir, final Path runlevelsDir,
			final Path runDir) {
		this.rc = rc;
		this.initdDir = initdDir;
		this.runlevelsDir = runlevelsDir;
		this.runDir = runDir;
	}

	/** Real OpenRC wiring: the standard paths + the {@code rc-*} subprocesses. */
	public static OpenRcBackend createDefault() {
		return new OpenRcBackend(new DefaultRcService(new DefaultCommandRunner()),
				Path.of("/etc/init.d"), Path.of("/etc/runlevels"), Path.of("/run"));
	}

	@Override
	public Platform platform() {
		return Platform.LINUX_OPENRC;
	}

	@Override
	public Capabilities capabilities() {
		// No per-user install, no calendar/interval scheduling, no conditional keep-alive, and
		// status is best-effort (structuredStatus=false). Matches the design's OpenRC row.
		return new Capabilities(false, true, true, false, false, true, false, true, false);
	}

	@Override
	public List<Installation> supportedInstallations() {
		return List.of(Installation.SYSTEM_WIDE);
	}

	@Override
	public Discovery discover(final Installation installation) {
		final List<ServiceStatus> services = new ArrayList<>();
		final List<String> unreadable = new ArrayList<>();
		if (installation != Installation.SYSTEM_WIDE || !Files.isDirectory(initdDir)) {
			return new Discovery(services, unreadable);
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(initdDir)) {
			for (final Path file : stream) {
				if (!Files.isRegularFile(file)) {
					continue;
				}
				try {
					services.add(buildStatus(reader.parseFile(file), file));
				} catch (final DefinitionIOException e) {
					unreadable.add(file.toString());
				}
			}
		} catch (final IOException e) {
			// unreadable directory — skip
		}
		return new Discovery(services, unreadable);
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		final Path file = findScript(id, installation);
		if (file == null) {
			return null;
		}
		return reader.toSpec(reader.parseFile(file), installation, id, isEnabled(id));
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		final Path file = findScript(id, installation);
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
		final Path file = findScript(id, installation);
		if (file == null) {
			return null;
		}
		return buildStatus(reader.parseFile(file), file);
	}

	// --- mutation ---

	@Override
	public void install(final ServiceSpec spec, final boolean overwriteUnmanaged) {
		final Installation installation = spec.runAs().installation();
		if (installation != Installation.SYSTEM_WIDE) {
			throw new UnsupportedFeatureException("per-user installation", Platform.LINUX_OPENRC);
		}
		final Path file = initdDir.resolve(spec.id());

		boolean adopted = false;
		if (Files.isRegularFile(file)) {
			Map<String, String> existing = null;
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

		final String runlevel = runlevelOf(spec);
		final String pidfile = runDir.resolve(spec.id() + ".pid").toString();
		try {
			Files.createDirectories(initdDir);
			Files.writeString(file, writer.render(spec, runlevel, pidfile, adopted));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write " + file, e);
		}
		makeExecutable(file);   // OpenRC will not run a non-executable init script
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		final Path file = findScript(id, installation);
		if (file == null) {
			throw new ServiceNotFoundException(id);
		}
		if (!unmanagedOk) {
			try {
				if (!reader.isManaged(reader.parseFile(file))) {
					throw new UnmanagedServiceException(id);
				}
			} catch (final DefinitionIOException e) {
				throw new UnmanagedServiceException(id);
			}
		}
		final String runlevel = runlevelOf(file);
		ignoreFailure(() -> rc.stop(id));
		ignoreFailure(() -> rc.del(id, runlevel));
		try {
			Files.deleteIfExists(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to delete " + file, e);
		}
	}

	@Override
	public void enable(final String id, final Installation installation) {
		rc.add(id, runlevelOf(require(id, installation)));
	}

	@Override
	public void disable(final String id, final Installation installation) {
		rc.del(id, runlevelOf(require(id, installation)));
	}

	@Override
	public void start(final String id, final Installation installation) {
		require(id, installation);
		rc.start(id);
	}

	@Override
	public void stop(final String id, final Installation installation) {
		require(id, installation);
		rc.stop(id);
	}

	@Override
	public void restart(final String id, final Installation installation) {
		require(id, installation);
		rc.restart(id);
	}

	private ServiceStatus buildStatus(final Map<String, String> script, final Path file) {
		final String id = file.getFileName().toString();
		final boolean managed = reader.isManaged(script);
		final boolean adopted = managed && reader.isAdopted(script);
		final boolean enabled = isEnabled(id);
		final RunState state = runState(rc.status(id).status());
		return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, enabled, managed, adopted, state,
				readPid(id), null, null);
	}

	private static RunState runState(final String status) {
		if (status == null) {
			return RunState.UNKNOWN;
		}
		return switch (status) {
			case "started" -> RunState.RUNNING;
			case "stopped", "inactive" -> RunState.STOPPED;
			case "crashed" -> RunState.FAILED;
			case "starting" -> RunState.STARTING;
			case "stopping" -> RunState.STOPPING;
			default -> RunState.UNKNOWN;
		};
	}

	private Path findScript(final String id, final Installation installation) {
		if (installation != Installation.SYSTEM_WIDE) {
			return null;
		}
		final Path candidate = initdDir.resolve(id);
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	private Path require(final String id, final Installation installation) {
		final Path file = findScript(id, installation);
		if (file == null) {
			throw new ServiceNotFoundException(id);
		}
		return file;
	}

	/** Is the service wired into any runlevel? (rc-update creates a symlink under a runlevel dir.) */
	private boolean isEnabled(final String id) {
		if (!Files.isDirectory(runlevelsDir)) {
			return false;
		}
		try (DirectoryStream<Path> runlevels = Files.newDirectoryStream(runlevelsDir)) {
			for (final Path runlevel : runlevels) {
				if (Files.isDirectory(runlevel)
						&& Files.exists(runlevel.resolve(id), LinkOption.NOFOLLOW_LINKS)) {
					return true;
				}
			}
		} catch (final IOException e) {
			// can't read runlevels — treat as not enabled
		}
		return false;
	}

	/** Best-effort PID from the pidfile (may be the supervisor's PID for supervised services). */
	private Integer readPid(final String id) {
		final Path pidfile = runDir.resolve(id + ".pid");
		if (!Files.isRegularFile(pidfile)) {
			return null;
		}
		try {
			final String content = Files.readString(pidfile).strip();
			if (content.isEmpty()) {
				return null;
			}
			final int pid = Integer.parseInt(content.split("\\s+")[0]);
			return pid > 0 ? pid : null;
		} catch (final IOException | NumberFormatException e) {
			return null;
		}
	}

	private String runlevelOf(final ServiceSpec spec) {
		final OpenRcOptions opts = spec.openrc();
		if (opts != null && opts.runlevel() != null && !opts.runlevel().isBlank()) {
			return opts.runlevel();
		}
		return OpenRcScriptReader.DEFAULT_RUNLEVEL;
	}

	private String runlevelOf(final Path file) {
		try {
			return reader.runlevel(reader.parse(Files.readString(file)));
		} catch (final IOException e) {
			return OpenRcScriptReader.DEFAULT_RUNLEVEL;
		}
	}

	private static void makeExecutable(final Path file) {
		final PosixFileAttributeView posix =
				Files.getFileAttributeView(file, PosixFileAttributeView.class);
		if (posix != null) {
			try {
				Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
			} catch (final IOException e) {
				throw new DefinitionIOException("failed to chmod +x " + file, e);
			}
		} else {
			// Non-POSIX filesystem (e.g. a Windows CI runner exercising the unit tests): the
			// executable bit is meaningless there, so this is a best-effort no-op.
			file.toFile().setExecutable(true);
		}
	}

	private static void ignoreFailure(final Runnable action) {
		try {
			action.run();
		} catch (final NativeCommandException ignored) {
			// best-effort during teardown
		}
	}
}
