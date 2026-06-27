package com.u1.servicepal.internal.systemd;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
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
		final SystemdOptions opts = spec.systemd();
		final StringBuilder sb = new StringBuilder();

		sb.append("[Unit]\n");
		sb.append("Description=").append(spec.displayName()).append('\n');
		sb.append(UnitReader.MANAGED_KEY).append("=1\n");
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
}
