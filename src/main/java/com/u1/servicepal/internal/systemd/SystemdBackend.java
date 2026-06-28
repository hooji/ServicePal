package com.u1.servicepal.internal.systemd;

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
 * Linux/systemd backend (services). Writes {@code .service} units and drives {@code systemctl}.
 * PER_USER → the {@code --user} manager + {@code ~/.config/systemd/user}; SYSTEM_WIDE → the
 * system manager + {@code /etc/systemd/system}. Scheduled jobs (.timer) are a follow-up — this
 * build reports {@code calendar}/{@code interval} as unsupported so they fail fast.
 */
public final class SystemdBackend implements Backend {

	private final Systemctl systemctl;
	private final Map<Installation, Path> directories;
	private final UnitReader reader = new UnitReader();
	private final UnitWriter writer = new UnitWriter();

	public SystemdBackend(final Systemctl systemctl, final Map<Installation, Path> directories) {
		this.systemctl = systemctl;
		this.directories = Map.copyOf(directories);
	}

	public static SystemdBackend createDefault() {
		final Path home = Path.of(System.getProperty("user.home", ""));
		final Map<Installation, Path> dirs = Map.of(
				Installation.PER_USER, home.resolve(".config/systemd/user"),
				Installation.SYSTEM_WIDE, Path.of("/etc/systemd/system"));
		return new SystemdBackend(new DefaultSystemctl(new DefaultCommandRunner()), dirs);
	}

	@Override
	public Platform platform() {
		return Platform.LINUX_SYSTEMD;
	}

	@Override
	public Capabilities capabilities() {
		// calendar/interval are false until .timer support lands.
		return new Capabilities(true, true, true, false, false, true, true, true, true);
	}

	@Override
	public List<Installation> supportedInstallations() {
		return List.of(Installation.PER_USER, Installation.SYSTEM_WIDE);
	}

	@Override
	public Discovery discover(final Installation installation) {
		final List<ServiceStatus> services = new ArrayList<>();
		final List<String> unreadable = new ArrayList<>();
		final Path dir = directories.get(installation);
		if (dir == null || !Files.isDirectory(dir)) {
			return new Discovery(services, unreadable);
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.service")) {
			for (final Path file : stream) {
				if (!Files.isRegularFile(file)) {
					continue;   // skip masked units (symlinks to /dev/null) etc.
				}
				try {
					services.add(buildStatus(reader.parseFile(file), file, installation));
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
		final Path file = findUnit(id, installation);
		if (file == null) {
			return null;
		}
		return reader.toSpec(reader.parseFile(file), installation, id);
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		final Path file = findUnit(id, installation);
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
		final Path file = findUnit(id, installation);
		if (file == null) {
			return null;
		}
		return buildStatus(reader.parseFile(file), file, installation);
	}

	// --- mutation ---

	@Override
	public void install(final ServiceSpec spec, final boolean overwriteUnmanaged) {
		final Installation installation = spec.runAs().installation();
		final Path dir = directories.get(installation);
		final Path file = dir.resolve(unitName(spec.id()));

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
		try {
			Files.createDirectories(dir);
			Files.writeString(file, writer.render(spec, user(installation), adopted));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write " + file, e);
		}
		systemctl.daemonReload(user(installation));
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		final Path file = findUnit(id, installation);
		if (file == null) {
			throw new ServiceNotFoundException(id);
		}
		if (!unmanagedOk && !reader.isManaged(reader.parseFile(file))) {
			throw new UnmanagedServiceException(id);
		}
		final boolean user = user(installation);
		final String unit = unitName(id);
		ignoreFailure(() -> systemctl.stop(user, unit));
		ignoreFailure(() -> systemctl.disable(user, unit));
		try {
			Files.deleteIfExists(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to delete " + file, e);
		}
		systemctl.daemonReload(user);
	}

	@Override
	public void enable(final String id, final Installation installation) {
		systemctl.enable(user(installation), requireUnit(id, installation));
	}

	@Override
	public void disable(final String id, final Installation installation) {
		systemctl.disable(user(installation), requireUnit(id, installation));
	}

	@Override
	public void start(final String id, final Installation installation) {
		systemctl.start(user(installation), requireUnit(id, installation));
	}

	@Override
	public void stop(final String id, final Installation installation) {
		systemctl.stop(user(installation), requireUnit(id, installation));
	}

	@Override
	public void restart(final String id, final Installation installation) {
		systemctl.restart(user(installation), requireUnit(id, installation));
	}

	private ServiceStatus buildStatus(final Map<String, String> unit, final Path file,
			final Installation installation) {
		final String id = stem(file);
		final boolean managed = reader.isManaged(unit);
		final boolean adopted = managed && reader.isAdopted(unit);
		final UnitState st = systemctl.show(user(installation), unitName(id));
		final boolean enabled = "enabled".equals(st.unitFileState())
				|| "enabled-runtime".equals(st.unitFileState())
				|| (st.unitFileState() == null && reader.enabledByInstall(unit));
		return new ServiceStatus(id, installation, true, enabled, managed, adopted, runState(st),
				st.mainPid(), st.execMainStatus(), null);
	}

	private static RunState runState(final UnitState st) {
		if ("failed".equals(st.activeState())) {
			return RunState.FAILED;
		}
		if ("activating".equals(st.activeState())) {
			return RunState.STARTING;
		}
		if ("deactivating".equals(st.activeState())) {
			return RunState.STOPPING;
		}
		if ("active".equals(st.activeState())) {
			return "running".equals(st.subState()) ? RunState.RUNNING : RunState.STOPPED;
		}
		if ("inactive".equals(st.activeState())) {
			return RunState.STOPPED;
		}
		return RunState.UNKNOWN;
	}

	private Path findUnit(final String id, final Installation installation) {
		final Path dir = directories.get(installation);
		if (dir == null) {
			return null;
		}
		final Path candidate = dir.resolve(unitName(id));
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	private String requireUnit(final String id, final Installation installation) {
		if (findUnit(id, installation) == null) {
			throw new ServiceNotFoundException(id);
		}
		return unitName(id);
	}

	private boolean user(final Installation installation) {
		return installation == Installation.PER_USER;
	}

	private static String unitName(final String id) {
		return id + ".service";
	}

	private static String stem(final Path file) {
		final String name = file.getFileName().toString();
		return name.endsWith(".service")
				? name.substring(0, name.length() - ".service".length()) : name;
	}

	private static void ignoreFailure(final Runnable action) {
		try {
			action.run();
		} catch (final NativeCommandException ignored) {
			// best-effort during teardown
		}
	}
}
