package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.options.OpenRcOptions;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link ServiceSpec} to an OpenRC init script. The write-side counterpart to
 * {@link OpenRcScriptReader}; stamps the managed + runlevel marker comments so discovery and
 * enable/disable can recognize and target our scripts.
 *
 * <p>Restart policy picks the supervisor: {@code start-stop-daemon} (with
 * {@code command_background}) for {@link RestartPolicy#NEVER}, otherwise {@code supervise-daemon}
 * (which respawns on any exit — OpenRC cannot distinguish ON_FAILURE from ALWAYS, so ALWAYS only
 * adds an unlimited {@code respawn_max}).
 */
public final class OpenRcScriptWriter {

	/**
	 * @param spec     the service to render
	 * @param runlevel the runlevel to stamp (for enable/disable)
	 * @param pidfile  absolute path of the pidfile the supervisor should manage
	 */
	public String render(final ServiceSpec spec, final String runlevel, final String pidfile) {
		return render(spec, runlevel, pidfile, false);
	}

	/** Render the init script, marking it {@code adopted} when installing over a foreign service. */
	public String render(final ServiceSpec spec, final String runlevel, final String pidfile,
			final boolean adopted) {
		final boolean supervise = useSupervise(spec);
		final StringBuilder sb = new StringBuilder();

		sb.append("#!/sbin/openrc-run\n");
		sb.append("# ").append(OpenRcScriptReader.MANAGED_MARKER).append(": 1\n");
		if (adopted) {
			sb.append("# ").append(OpenRcScriptReader.ADOPTED_MARKER).append(": 1\n");
		}
		sb.append("# ").append(OpenRcScriptReader.RUNLEVEL_MARKER).append(": ")
				.append(runlevel).append('\n');
		sb.append('\n');

		sb.append("description=").append(quote(spec.displayName())).append('\n');

		for (final Map.Entry<String, String> e : spec.environment().entrySet()) {
			sb.append("export ").append(e.getKey()).append('=').append(quote(e.getValue()))
					.append('\n');
		}

		sb.append("command=").append(quote(spec.command().get(0))).append('\n');
		final String args = argsOf(spec.command());
		if (!args.isEmpty()) {
			sb.append("command_args=").append(quote(args)).append('\n');
		}
		if (spec.runAs().kind() == RunAs.Kind.NAMED_USER) {
			sb.append("command_user=").append(quote(spec.runAs().userName())).append('\n');
		}
		if (spec.workingDirectory() != null) {
			sb.append("directory=").append(quote(spec.workingDirectory().toString())).append('\n');
		}
		sb.append("pidfile=").append(quote(pidfile)).append('\n');

		if (supervise) {
			sb.append("supervisor=supervise-daemon\n");
			if (spec.restart() == RestartPolicy.ALWAYS) {
				sb.append("respawn_max=0\n");   // never give up on a service asked to always run
			}
		} else {
			// start-stop-daemon must background the (foreground) command and track the pidfile.
			sb.append("command_background=true\n");
		}

		if (spec.stdout() != null) {
			sb.append("output_log=").append(quote(spec.stdout().toString())).append('\n');
		}
		if (spec.stderr() != null) {
			sb.append("error_log=").append(quote(spec.stderr().toString())).append('\n');
		}

		// Only emit depend() when there are dependencies: an empty `depend() {}` body is a POSIX
		// shell syntax error (openrc-run sources the script), and depend() is optional anyway.
		final OpenRcOptions opts = spec.openrc();
		if (opts != null && !opts.need().isEmpty()) {
			sb.append('\n');
			sb.append("depend() {\n");
			for (final String need : opts.need()) {
				sb.append("\tneed ").append(need).append('\n');
			}
			sb.append("}\n");
		}
		return sb.toString();
	}

	private static boolean useSupervise(final ServiceSpec spec) {
		final OpenRcOptions opts = spec.openrc();
		if (opts != null && opts.supervisor() != null) {
			return opts.supervisor() == OpenRcOptions.Supervisor.SUPERVISE_DAEMON;
		}
		return spec.restart() != RestartPolicy.NEVER;
	}

	private static String argsOf(final List<String> command) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 1; i < command.size(); i++) {
			if (i > 1) {
				sb.append(' ');
			}
			sb.append(command.get(i));
		}
		return sb.toString();
	}

	/** Wrap a value in double quotes, escaping the shell-special characters within them. */
	private static String quote(final String value) {
		final StringBuilder sb = new StringBuilder(value.length() + 2);
		sb.append('"');
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (c == '\\' || c == '"' || c == '$' || c == '`') {
				sb.append('\\');
			}
			sb.append(c);
		}
		sb.append('"');
		return sb.toString();
	}
}
