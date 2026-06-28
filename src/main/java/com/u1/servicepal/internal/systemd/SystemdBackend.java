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
import com.u1.servicepal.model.Schedule;
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
 * Linux/systemd backend. Writes {@code .service} units (daemons) and drives {@code systemctl}.
 * PER_USER → the {@code --user} manager + {@code ~/.config/systemd/user}; SYSTEM_WIDE → the
 * system manager + {@code /etc/systemd/system}. A <strong>scheduled</strong> job (non-null
 * {@code Schedule}) becomes a {@code .timer} + a oneshot {@code .service} pair; by-id ops target the
 * {@code .timer}, and discovery surfaces the job once (via the timer, hiding the backing service).
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
		// Scheduled jobs run via a .timer + oneshot .service pair.
		return new Capabilities(true, true, true, true, true, true, true, true, true);
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
		// Scheduled jobs surface once, via their .timer; the oneshot .service backing a timer is
		// an implementation detail and is skipped below.
		final java.util.Set<String> scheduledStems = new java.util.HashSet<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.timer")) {
			for (final Path file : stream) {
				if (!Files.isRegularFile(file)) {
					continue;
				}
				scheduledStems.add(stem(file));
				try {
					services.add(buildScheduledStatus(reader.parseFile(file), file, installation));
				} catch (final DefinitionIOException e) {
					unreadable.add(file.toString());
				}
			}
		} catch (final IOException e) {
			// unreadable directory — skip
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.service")) {
			for (final Path file : stream) {
				if (!Files.isRegularFile(file) || scheduledStems.contains(stem(file))) {
					continue;   // skip masked units and the oneshot service behind a timer
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
		final Path timer = findTimer(id, installation);
		if (timer != null) {
			final Path service = findUnit(id, installation);
			if (service == null) {
				return null;   // can't reconstruct the command without the backing .service
			}
			final ServiceSpec base = reader.toSpec(reader.parseFile(service), installation, id);
			final Schedule schedule = reader.scheduleOf(reader.parseFile(timer));
			return schedule == null ? base : base.toBuilder().schedule(schedule).build();
		}
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
		final Path timer = findTimer(id, installation);
		if (timer != null) {
			return buildScheduledStatus(reader.parseFile(timer), timer, installation);
		}
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
		final boolean scheduled = spec.schedule() != null;
		final boolean adopted = provenance(spec.id(), installation, overwriteUnmanaged);
		final boolean user = user(installation);

		try {
			Files.createDirectories(dir);
			if (scheduled) {
				// Switching a managed, enabled daemon to a schedule: disable it first so its
				// [Install] symlink doesn't keep auto-starting it now that it's a oneshot.
				if (findTimer(spec.id(), installation) == null
						&& isManagedDaemon(spec.id(), installation)) {
					ignoreFailure(() -> systemctl.disable(user, unitName(spec.id())));
				}
				Files.writeString(dir.resolve(unitName(spec.id())),
						writer.renderScheduledService(spec, adopted));
				Files.writeString(dir.resolve(timerName(spec.id())),
						writer.renderTimer(spec, adopted));
			} else {
				Files.writeString(dir.resolve(unitName(spec.id())),
						writer.render(spec, user, adopted));
				removeManagedTimer(spec.id(), installation);   // tear down a stale timer if any
			}
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write units for " + spec.id(), e);
		}
		systemctl.daemonReload(user);
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		final Path timer = findTimer(id, installation);
		final Path service = findUnit(id, installation);
		if (timer == null && service == null) {
			throw new ServiceNotFoundException(id);
		}
		final Path primary = timer != null ? timer : service;
		if (!unmanagedOk && !reader.isManaged(reader.parseFile(primary))) {
			throw new UnmanagedServiceException(id);
		}
		final boolean user = user(installation);
		final String primaryUnit = timer != null ? timerName(id) : unitName(id);
		ignoreFailure(() -> systemctl.stop(user, primaryUnit));
		ignoreFailure(() -> systemctl.disable(user, primaryUnit));
		try {
			if (timer != null) {
				Files.deleteIfExists(timer);
			}
			if (service != null) {
				Files.deleteIfExists(service);
			}
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to delete units for " + id, e);
		}
		systemctl.daemonReload(user);
	}

	/**
	 * Provenance for an install: throws {@link UnmanagedServiceException} (unless {@code overwrite})
	 * when an existing definition for this id isn't ours; otherwise returns whether it is adopted
	 * (preserving our own marker on re-install, marking adoption when taking over something foreign).
	 * Checks the {@code .timer} first (the primary record of a scheduled job), then the {@code .service}.
	 */
	private boolean provenance(final String id, final Installation installation,
			final boolean overwrite) {
		final Path dir = directories.get(installation);
		final Path timer = dir.resolve(timerName(id));
		final Path service = dir.resolve(unitName(id));
		final Path existingFile = Files.isRegularFile(timer) ? timer
				: (Files.isRegularFile(service) ? service : null);
		if (existingFile == null) {
			return false;
		}
		Map<String, String> existing = null;
		try {
			existing = reader.parseFile(existingFile);
		} catch (final DefinitionIOException e) {
			existing = null;   // unreadable/foreign
		}
		final boolean existingManaged = existing != null && reader.isManaged(existing);
		if (!existingManaged && !overwrite) {
			throw new UnmanagedServiceException(id);
		}
		return existingManaged ? reader.isAdopted(existing) : true;
	}

	private boolean isManagedDaemon(final String id, final Installation installation) {
		final Path service = findUnit(id, installation);
		if (service == null) {
			return false;
		}
		try {
			final Map<String, String> unit = reader.parseFile(service);
			return reader.isManaged(unit) && reader.enabledByInstall(unit);
		} catch (final DefinitionIOException e) {
			return false;
		}
	}

	private void removeManagedTimer(final String id, final Installation installation)
			throws IOException {
		final Path timer = findTimer(id, installation);
		if (timer == null) {
			return;
		}
		try {
			if (!reader.isManaged(reader.parseFile(timer))) {
				return;
			}
		} catch (final DefinitionIOException e) {
			return;
		}
		final boolean user = user(installation);
		ignoreFailure(() -> systemctl.stop(user, timerName(id)));
		ignoreFailure(() -> systemctl.disable(user, timerName(id)));
		Files.deleteIfExists(timer);
	}

	@Override
	public void enable(final String id, final Installation installation) {
		systemctl.enable(user(installation), requirePrimaryUnit(id, installation));
	}

	@Override
	public void disable(final String id, final Installation installation) {
		systemctl.disable(user(installation), requirePrimaryUnit(id, installation));
	}

	@Override
	public void start(final String id, final Installation installation) {
		systemctl.start(user(installation), requirePrimaryUnit(id, installation));
	}

	@Override
	public void stop(final String id, final Installation installation) {
		systemctl.stop(user(installation), requirePrimaryUnit(id, installation));
	}

	@Override
	public void restart(final String id, final Installation installation) {
		systemctl.restart(user(installation), requirePrimaryUnit(id, installation));
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

	private ServiceStatus buildScheduledStatus(final Map<String, String> timerUnit,
			final Path timerFile, final Installation installation) {
		final String id = stem(timerFile);
		final boolean managed = reader.isManaged(timerUnit);
		final boolean adopted = managed && reader.isAdopted(timerUnit);
		final boolean user = user(installation);
		final UnitState timerState = systemctl.show(user, timerName(id));
		final UnitState serviceState = systemctl.show(user, unitName(id));
		final boolean enabled = "enabled".equals(timerState.unitFileState())
				|| "enabled-runtime".equals(timerState.unitFileState())
				|| (timerState.unitFileState() == null && reader.enabledByInstall(timerUnit));
		// Coarse state from the backing service: RUNNING mid-execution, FAILED on a failed run,
		// otherwise STOPPED — the timer is armed and waiting (the GUI relabels this "Scheduled").
		return new ServiceStatus(id, installation, true, enabled, managed, adopted,
				runState(serviceState), serviceState.mainPid(), serviceState.execMainStatus(), null);
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

	private Path findTimer(final String id, final Installation installation) {
		final Path dir = directories.get(installation);
		if (dir == null) {
			return null;
		}
		final Path candidate = dir.resolve(timerName(id));
		return Files.isRegularFile(candidate) ? candidate : null;
	}

	/** The unit a by-id op targets: the {@code .timer} for a scheduled job, else the {@code .service}. */
	private String requirePrimaryUnit(final String id, final Installation installation) {
		if (findTimer(id, installation) != null) {
			return timerName(id);
		}
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

	private static String timerName(final String id) {
		return id + ".timer";
	}

	private static String stem(final Path file) {
		final String name = file.getFileName().toString();
		if (name.endsWith(".service")) {
			return name.substring(0, name.length() - ".service".length());
		}
		if (name.endsWith(".timer")) {
			return name.substring(0, name.length() - ".timer".length());
		}
		return name;
	}

	private static void ignoreFailure(final Runnable action) {
		try {
			action.run();
		} catch (final NativeCommandException ignored) {
			// best-effort during teardown
		}
	}
}
