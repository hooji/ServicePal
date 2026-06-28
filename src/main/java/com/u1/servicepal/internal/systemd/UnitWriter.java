package com.u1.servicepal.internal.systemd;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.options.SystemdOptions;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link ServiceSpec} to a systemd {@code .service} unit (INI). The write-side
 * counterpart to {@link UnitReader}. Stamps {@link UnitReader#MANAGED_KEY} so discovery can
 * recognize our units. (Timers for scheduled jobs are a follow-up; this build renders
 * long-running services only.)
 */
public final class UnitWriter {

	public String render(final ServiceSpec spec, final boolean user) {
		return render(spec, user, false);
	}

	/** Render the unit, marking it {@code adopted} when we are installing over a foreign service. */
	public String render(final ServiceSpec spec, final boolean user, final boolean adopted) {
		final SystemdOptions opts = spec.systemd();
		final StringBuilder sb = new StringBuilder();

		sb.append("[Unit]\n");
		sb.append("Description=").append(spec.displayName()).append('\n');
		sb.append(UnitReader.MANAGED_KEY).append("=1\n");
		if (adopted) {
			sb.append(UnitReader.ADOPTED_KEY).append("=1\n");
		}
		if (opts != null) {
			appendList(sb, "After", opts.after());
			appendList(sb, "Wants", opts.wants());
		}
		if (spec.restart() == RestartPolicy.ALWAYS) {
			// Don't let the start-rate limiter give up on a service we asked to always run.
			sb.append("StartLimitIntervalSec=0\n");
		}
		sb.append('\n');

		sb.append("[Service]\n");
		sb.append("Type=").append(systemdType(opts)).append('\n');
		sb.append("ExecStart=").append(String.join(" ", spec.command())).append('\n');
		sb.append("Restart=").append(restart(spec.restart())).append('\n');
		if (opts != null && opts.restartSec() != null) {
			sb.append("RestartSec=").append(opts.restartSec().toSeconds()).append('\n');
		}
		if (spec.runAs().kind() == RunAs.Kind.NAMED_USER) {
			sb.append("User=").append(spec.runAs().userName()).append('\n');
		}
		if (spec.workingDirectory() != null) {
			sb.append("WorkingDirectory=").append(spec.workingDirectory()).append('\n');
		}
		for (final Map.Entry<String, String> e : spec.environment().entrySet()) {
			sb.append("Environment=\"").append(e.getKey()).append('=').append(e.getValue())
					.append("\"\n");
		}
		if (spec.stdout() != null) {
			sb.append("StandardOutput=append:").append(spec.stdout()).append('\n');
		}
		if (spec.stderr() != null) {
			sb.append("StandardError=append:").append(spec.stderr()).append('\n');
		}
		if (opts != null && opts.memoryMax() != null) {
			sb.append("MemoryMax=").append(opts.memoryMax()).append('\n');
		}
		sb.append('\n');

		sb.append("[Install]\n");
		sb.append("WantedBy=").append(user ? "default.target" : "multi-user.target").append('\n');
		return sb.toString();
	}

	private static void appendList(final StringBuilder sb, final String key,
			final java.util.List<String> values) {
		if (!values.isEmpty()) {
			sb.append(key).append('=').append(String.join(" ", values)).append('\n');
		}
	}

	private static String systemdType(final SystemdOptions opts) {
		if (opts != null && opts.type() != null) {
			return opts.type().name().toLowerCase(Locale.ROOT);
		}
		return "exec";
	}

	private static String restart(final RestartPolicy policy) {
		return switch (policy) {
			case NEVER -> "no";
			case ON_FAILURE -> "on-failure";
			case ALWAYS -> "always";
		};
	}

	// --- scheduled jobs (a .timer triggering a oneshot .service) ---

	/**
	 * The oneshot {@code .service} a {@code .timer} triggers: it runs the command once and exits
	 * (the timer decides when), so it carries no {@code [Install]} section and is not enabled on its
	 * own.
	 */
	public String renderScheduledService(final ServiceSpec spec, final boolean adopted) {
		final StringBuilder sb = new StringBuilder();
		sb.append("[Unit]\n");
		sb.append("Description=").append(spec.displayName()).append('\n');
		sb.append(UnitReader.MANAGED_KEY).append("=1\n");
		if (adopted) {
			sb.append(UnitReader.ADOPTED_KEY).append("=1\n");
		}
		sb.append('\n');

		sb.append("[Service]\n");
		sb.append("Type=oneshot\n");
		sb.append("ExecStart=").append(String.join(" ", spec.command())).append('\n');
		if (spec.runAs().kind() == RunAs.Kind.NAMED_USER) {
			sb.append("User=").append(spec.runAs().userName()).append('\n');
		}
		if (spec.workingDirectory() != null) {
			sb.append("WorkingDirectory=").append(spec.workingDirectory()).append('\n');
		}
		for (final Map.Entry<String, String> e : spec.environment().entrySet()) {
			sb.append("Environment=\"").append(e.getKey()).append('=').append(e.getValue())
					.append("\"\n");
		}
		if (spec.stdout() != null) {
			sb.append("StandardOutput=append:").append(spec.stdout()).append('\n');
		}
		if (spec.stderr() != null) {
			sb.append("StandardError=append:").append(spec.stderr()).append('\n');
		}
		return sb.toString();
	}

	/** The {@code .timer} that triggers the oneshot service on the spec's schedule. */
	public String renderTimer(final ServiceSpec spec, final boolean adopted) {
		final Schedule schedule = spec.schedule();
		final StringBuilder sb = new StringBuilder();
		sb.append("[Unit]\n");
		sb.append("Description=").append(spec.displayName()).append('\n');
		sb.append(UnitReader.MANAGED_KEY).append("=1\n");
		if (adopted) {
			sb.append(UnitReader.ADOPTED_KEY).append("=1\n");
		}
		sb.append(UnitReader.SCHEDULE_KEY).append('=').append(scheduleMarker(schedule)).append('\n');
		sb.append('\n');

		sb.append("[Timer]\n");
		if (schedule instanceof IntervalSchedule interval) {
			final long seconds = interval.period().toSeconds();
			sb.append("OnBootSec=").append(seconds).append("s\n");
			sb.append("OnUnitActiveSec=").append(seconds).append("s\n");
		} else if (schedule instanceof CalendarSchedule calendar) {
			sb.append("OnCalendar=").append(onCalendar(calendar.spec())).append('\n');
			sb.append("Persistent=true\n");   // run a missed job once the machine is back up
		}
		sb.append('\n');

		sb.append("[Install]\n");
		sb.append("WantedBy=timers.target\n");
		return sb.toString();
	}

	/** Encode a schedule for the {@link UnitReader#SCHEDULE_KEY} side-band marker. */
	private static String scheduleMarker(final Schedule schedule) {
		if (schedule instanceof IntervalSchedule interval) {
			return "interval:" + interval.period().toSeconds();
		}
		final CalendarSpec s = ((CalendarSchedule) schedule).spec();
		return "calendar:" + nz(s.minute()) + "," + nz(s.hour()) + "," + nz(s.dayOfMonth())
				+ "," + nz(s.month()) + "," + nz(s.dayOfWeek());
	}

	/** Build a systemd {@code OnCalendar} expression from a cron-like {@link CalendarSpec}. */
	private static String onCalendar(final CalendarSpec s) {
		final StringBuilder sb = new StringBuilder();
		if (s.dayOfWeek() != null) {
			sb.append(dayName(s.dayOfWeek())).append(' ');
		}
		sb.append('*').append('-').append(field(s.month())).append('-').append(field(s.dayOfMonth()))
				.append(' ').append(field(s.hour())).append(':').append(field(s.minute()))
				.append(":00");
		return sb.toString();
	}

	private static String dayName(final int dayOfWeek) {
		return switch (dayOfWeek % 7) {   // 0 and 7 both mean Sunday (cron/launchd convention)
			case 1 -> "Mon";
			case 2 -> "Tue";
			case 3 -> "Wed";
			case 4 -> "Thu";
			case 5 -> "Fri";
			case 6 -> "Sat";
			default -> "Sun";
		};
	}

	private static String field(final Integer value) {
		if (value == null) {
			return "*";
		}
		return value < 10 ? "0" + value : Integer.toString(value);
	}

	private static String nz(final Integer value) {
		return value == null ? "" : value.toString();
	}
}
