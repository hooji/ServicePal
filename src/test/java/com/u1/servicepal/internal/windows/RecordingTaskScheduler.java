package com.u1.servicepal.internal.windows;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A test fake for {@link TaskScheduler}: records calls and tracks which tasks "exist". */
public final class RecordingTaskScheduler implements TaskScheduler {

	public final List<String> calls = new ArrayList<>();
	public final Set<String> tasks = new LinkedHashSet<>();

	public String lastXml;
	public String lastAccount;
	public String lastPassword;
	public boolean running;
	public TaskRunTimes runTimes = TaskRunTimes.UNKNOWN;

	@Override
	public boolean exists(final String name) {
		return tasks.contains(name);
	}

	@Override
	public void create(final String name, final String xml, final String account,
			final String password) {
		calls.add("create " + name);
		tasks.add(name);
		lastXml = xml;
		lastAccount = account;
		lastPassword = password;
	}

	@Override
	public void delete(final String name) {
		calls.add("delete " + name);
		tasks.remove(name);
	}

	@Override
	public void run(final String name) {
		calls.add("run " + name);
	}

	@Override
	public void end(final String name) {
		calls.add("end " + name);
	}

	@Override
	public void setEnabled(final String name, final boolean enabled) {
		calls.add("setEnabled " + name + " " + enabled);
	}

	@Override
	public boolean isRunning(final String name) {
		return running;
	}

	@Override
	public TaskRunTimes runTimes(final String name) {
		return runTimes;
	}
}
