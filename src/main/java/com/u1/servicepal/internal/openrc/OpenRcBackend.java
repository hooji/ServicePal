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
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.Schedule;
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
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Linux/OpenRC backend. A <strong>daemon</strong> is an init script in {@code /etc/init.d} driven by
 * {@code rc-service} / {@code rc-update}. OpenRC has no per-user services (SYSTEM_WIDE-only) and no
 * native scheduler, so a <strong>scheduled</strong> job is a <em>cron fallback</em>: the init script
 * is the definition record (carrying a {@code X-ServicePal-Schedule} marker, never added to a
 * runlevel) and a crontab entry (added on {@code enable}) runs the command. Calendar schedules map
 * cleanly to cron; intervals only when the period divides a minute/hour (else {@code CronSchedule}
 * fails fast). There is no {@code daemon-reload} equivalent — OpenRC reads the script on demand.
 */
public final class OpenRcBackend implements Backend {

	/** Comment that tags our managed cron entries in the crontab (followed by the service id). */
	private static final String CRON_TAG = "# X-ServicePal:";

	private final RcService rc;
	private final Cron cron;
	private final Path initdDir;
	private final Path runlevelsDir;
	private final Path runDir;
	private final OpenRcScriptReader reader = new OpenRcScriptReader();
	private final OpenRcScriptWriter writer = new OpenRcScriptWriter();

	public OpenRcBackend(final RcService rc, final Cron cron, final Path initdDir,
			final Path runlevelsDir, final Path runDir) {
		this.rc = rc;
		this.cron = cron;
		this.initdDir = initdDir;
		this.runlevelsDir = runlevelsDir;
		this.runDir = runDir;
	}

	/** Real OpenRC wiring: the standard paths + the {@code rc-*} / {@code crontab} subprocesses. */
	public static OpenRcBackend createDefault() {
		final DefaultCommandRunner runner = new DefaultCommandRunner();
		return new OpenRcBackend(new DefaultRcService(runner), new DefaultCron(runner),
				Path.of("/etc/init.d"), Path.of("/etc/runlevels"), Path.of("/run"));
	}

	@Override
	public Platform platform() {
		return Platform.LINUX_OPENRC;
	}

	@Override
	public Capabilities capabilities() {
		// No per-user install and no conditional keep-alive; status is best-effort
		// (structuredStatus=false). Scheduling is via a cron fallback — calendar fully, intervals
		// only when the period divides a minute/hour (CronSchedule fails fast otherwise).
		return new Capabilities(false, true, true, true, true, true, false, true, false);
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
		final Map<String, String> script = reader.parseFile(file);
		final ServiceSpec base = reader.toSpec(script, installation, id, isEnabled(id));
		if (reader.isScheduled(script)) {
			final Schedule schedule = reader.scheduleOf(script);
			return schedule == null ? base : base.toBuilder().schedule(schedule).build();
		}
		return base;
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
		final boolean scheduled = spec.schedule() != null;
		if (scheduled) {
			CronSchedule.toCronLine(spec.schedule());   // fail fast on a cron-inexpressible interval
		}
		final Path file = initdDir.resolve(spec.id());

		boolean adopted = false;
		boolean wasManagedDaemon = false;
		boolean wasScheduled = false;
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
			wasScheduled = existing != null && reader.isScheduled(existing);
			wasManagedDaemon = existingManaged && !wasScheduled;
		}

		// Mode-switch cleanup: drop the old activation when changing daemon <-> scheduled.
		if (scheduled && wasManagedDaemon) {
			final String oldRunlevel = runlevelOf(file);
			ignoreFailure(() -> rc.del(spec.id(), oldRunlevel));   // was a boot daemon
		} else if (!scheduled && wasScheduled) {
			removeCronEntry(spec.id());                            // was a cron job
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
		if (isScheduledScript(file)) {
			removeCronEntry(id);   // a cron job: no rc-service/runlevel to tear down
		} else {
			final String runlevel = runlevelOf(file);
			ignoreFailure(() -> rc.stop(id));
			ignoreFailure(() -> rc.del(id, runlevel));
		}
		try {
			Files.deleteIfExists(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to delete " + file, e);
		}
	}

	@Override
	public void enable(final String id, final Installation installation) {
		final Path file = require(id, installation);
		final Map<String, String> script = reader.parseFile(file);
		if (reader.isScheduled(script)) {
			addCronEntry(id, script, installation);   // arm the cron entry
		} else {
			rc.add(id, runlevelOf(file));
		}
	}

	@Override
	public void disable(final String id, final Installation installation) {
		final Path file = require(id, installation);
		if (reader.isScheduled(reader.parseFile(file))) {
			removeCronEntry(id);
		} else {
			rc.del(id, runlevelOf(file));
		}
	}

	@Override
	public void start(final String id, final Installation installation) {
		final Path file = require(id, installation);
		if (reader.isScheduled(reader.parseFile(file))) {
			return;   // a scheduled job runs on its schedule; "start now" is a no-op
		}
		rc.start(id);
	}

	@Override
	public void stop(final String id, final Installation installation) {
		final Path file = require(id, installation);
		if (reader.isScheduled(reader.parseFile(file))) {
			return;   // a scheduled job has no long-running process to stop
		}
		rc.stop(id);
	}

	@Override
	public void restart(final String id, final Installation installation) {
		final Path file = require(id, installation);
		if (reader.isScheduled(reader.parseFile(file))) {
			return;   // a scheduled job runs on its schedule; nothing to restart now
		}
		rc.restart(id);
	}

	private ServiceStatus buildStatus(final Map<String, String> script, final Path file) {
		final String id = file.getFileName().toString();
		final boolean managed = reader.isManaged(script);
		final boolean adopted = managed && reader.isAdopted(script);
		if (reader.isScheduled(script)) {
			// A cron job: "enabled" = its cron entry is present. It has no long-running process, so
			// it reads STOPPED between runs (the GUI relabels a scheduled job "Scheduled"). cron has
			// no last-run record; next-run is computed from the schedule (only while it's armed).
			final boolean enabled = hasCronEntry(id);
			final Schedule schedule = reader.scheduleOf(script);
			final Instant nextRun = enabled && schedule != null
					? CronSchedule.nextRun(schedule, Instant.now(), ZoneId.systemDefault()) : null;
			return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, enabled, managed,
					adopted, RunState.STOPPED, null, null, null).withRunTimes(nextRun, null);
		}
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

	// --- cron entries (the execution side of a scheduled job) ---

	/** Add (or replace) the crontab entry that runs this scheduled job's command. */
	private void addCronEntry(final String id, final Map<String, String> script,
			final Installation installation) {
		final ServiceSpec spec = reader.toSpec(script, installation, id, false);
		final String cronLine = CronSchedule.toCronLine(reader.scheduleOf(script));
		putCronEntry(id, cronLine, cronCommand(spec));
	}

	private void putCronEntry(final String id, final String cronLine, final String command) {
		final StringBuilder sb = new StringBuilder(removeCronBlock(cron.read(), id));
		if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
			sb.append('\n');
		}
		sb.append(CRON_TAG).append(' ').append(id).append('\n');
		sb.append(cronLine).append(' ').append(command).append('\n');
		cron.write(sb.toString());
	}

	private void removeCronEntry(final String id) {
		final String before = cron.read();
		final String after = removeCronBlock(before, id);
		if (!after.equals(before)) {
			cron.write(after);
		}
	}

	private boolean hasCronEntry(final String id) {
		final String marker = (CRON_TAG + " " + id);
		for (final String line : cron.read().split("\n", -1)) {
			if (line.strip().equals(marker)) {
				return true;
			}
		}
		return false;
	}

	/** Drop our {@code # X-ServicePal: <id>} tag line and the cron entry line that follows it. */
	private static String removeCronBlock(final String crontab, final String id) {
		final String marker = (CRON_TAG + " " + id);
		final String[] lines = crontab.split("\n", -1);
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].strip().equals(marker)) {
				i++;   // also skip the cron entry line immediately after the tag
				continue;
			}
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(lines[i]);
		}
		return sb.toString();
	}

	/** The shell command crond runs — directly as root, or via {@code su} for a named user. */
	private static String cronCommand(final ServiceSpec spec) {
		final String joined = String.join(" ", spec.command());
		if (spec.runAs().kind() == RunAs.Kind.NAMED_USER) {
			return "su -s /bin/sh " + spec.runAs().userName() + " -c \""
					+ joined.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		return joined;
	}

	private boolean isScheduledScript(final Path file) {
		try {
			return reader.isScheduled(reader.parseFile(file));
		} catch (final DefinitionIOException e) {
			return false;
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
