package com.u1.servicepal.internal.systemd;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** A test fake for {@link Systemctl}: records verb calls and returns canned {@link UnitState}. */
public final class RecordingSystemctl implements Systemctl {

	public final List<String> calls = new ArrayList<>();

	private final BiFunction<Boolean, String, UnitState> showFn;

	public RecordingSystemctl() {
		this((user, unit) -> UnitState.notFound());
	}

	public RecordingSystemctl(final BiFunction<Boolean, String, UnitState> showFn) {
		this.showFn = showFn;
	}

	private static String scope(final boolean user) {
		return user ? "user" : "system";
	}

	@Override
	public void daemonReload(final boolean user) {
		calls.add("daemon-reload " + scope(user));
	}

	@Override
	public void enable(final boolean user, final String unit) {
		calls.add("enable " + scope(user) + " " + unit);
	}

	@Override
	public void disable(final boolean user, final String unit) {
		calls.add("disable " + scope(user) + " " + unit);
	}

	@Override
	public void start(final boolean user, final String unit) {
		calls.add("start " + scope(user) + " " + unit);
	}

	@Override
	public void stop(final boolean user, final String unit) {
		calls.add("stop " + scope(user) + " " + unit);
	}

	@Override
	public void restart(final boolean user, final String unit) {
		calls.add("restart " + scope(user) + " " + unit);
	}

	@Override
	public UnitState show(final boolean user, final String unit) {
		return showFn.apply(user, unit);
	}
}
