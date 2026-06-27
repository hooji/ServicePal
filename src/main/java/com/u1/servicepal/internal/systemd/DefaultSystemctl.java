package com.u1.servicepal.internal.systemd;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.ArrayList;
import java.util.List;

/** Drives {@code systemctl} via subprocess. */
public final class DefaultSystemctl implements Systemctl {

	private static final String SHOW_PROPS =
			"LoadState,ActiveState,SubState,UnitFileState,MainPID,ExecMainStatus";

	private final CommandRunner runner;

	public DefaultSystemctl(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public void daemonReload(final boolean user) {
		mutate(base(user, "daemon-reload"));
	}

	@Override
	public void enable(final boolean user, final String unit) {
		mutate(base(user, "enable", unit));
	}

	@Override
	public void disable(final boolean user, final String unit) {
		mutate(base(user, "disable", unit));
	}

	@Override
	public void start(final boolean user, final String unit) {
		mutate(base(user, "start", unit));
	}

	@Override
	public void stop(final boolean user, final String unit) {
		mutate(base(user, "stop", unit));
	}

	@Override
	public void restart(final boolean user, final String unit) {
		mutate(base(user, "restart", unit));
	}

	@Override
	public UnitState show(final boolean user, final String unit) {
		final List<String> cmd = base(user, "show", unit);
		cmd.add("-p");
		cmd.add(SHOW_PROPS);
		final CommandResult res = runner.run(cmd);
		if (!res.ok() || res.stdout() == null) {
			return UnitState.notFound();
		}
		return parseShow(res.stdout());
	}

	/** Parse {@code Key=Value} lines from {@code systemctl show}. Package-visible for testing. */
	static UnitState parseShow(final String out) {
		String load = null;
		String active = null;
		String sub = null;
		String fileState = null;
		Integer pid = null;
		Integer exit = null;
		for (final String raw : out.split("\n")) {
			final int eq = raw.indexOf('=');
			if (eq < 0) {
				continue;
			}
			final String key = raw.substring(0, eq).strip();
			final String value = raw.substring(eq + 1).strip();
			switch (key) {
				case "LoadState" -> load = value;
				case "ActiveState" -> active = value;
				case "SubState" -> sub = value;
				case "UnitFileState" -> fileState = value;
				case "MainPID" -> pid = positiveOrNull(value);
				case "ExecMainStatus" -> exit = parseOrNull(value);
				default -> {
					// ignore other properties
				}
			}
		}
		return new UnitState(load, active, sub, fileState, pid, exit);
	}

	private List<String> base(final boolean user, final String... args) {
		final List<String> cmd = new ArrayList<>();
		cmd.add("systemctl");
		if (user) {
			cmd.add("--user");
		}
		for (final String a : args) {
			cmd.add(a);
		}
		return cmd;
	}

	private void mutate(final List<String> command) {
		final CommandResult res = runner.run(command);
		if (!res.ok()) {
			throw new NativeCommandException(command, res.exitCode(), res.stderr());
		}
	}

	private static Integer positiveOrNull(final String s) {
		final Integer v = parseOrNull(s);
		return (v == null || v <= 0) ? null : v;
	}

	private static Integer parseOrNull(final String s) {
		try {
			return Integer.valueOf(s.strip());
		} catch (final NumberFormatException e) {
			return null;
		}
	}
}
