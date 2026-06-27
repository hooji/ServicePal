package com.u1.servicepal.internal.macos;

import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads loaded jobs via {@code launchctl list}. That output is a simple tab-separated table
 * ({@code PID\tStatus\tLabel}), far easier to parse than {@code launchctl print}. Mutation
 * (which will use the modern bootstrap/kickstart subcommands) arrives in step 4.
 */
public final class DefaultLaunchctl implements Launchctl {

	private final CommandRunner runner;

	public DefaultLaunchctl(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public Map<String, JobInfo> listJobs() {
		final Map<String, JobInfo> jobs = new LinkedHashMap<>();
		final CommandResult result = runner.run(List.of("launchctl", "list"));
		if (!result.ok() || result.stdout() == null) {
			return jobs;
		}
		final String[] lines = result.stdout().split("\n");
		for (final String line : lines) {
			if (line.isBlank() || line.startsWith("PID\t") || line.startsWith("PID ")) {
				continue;
			}
			final String[] cols = line.split("\t");
			if (cols.length < 3) {
				continue;
			}
			final String label = cols[2].strip();
			if (label.isEmpty()) {
				continue;
			}
			jobs.put(label, new JobInfo(parseOrNull(cols[0]), parseOrNull(cols[1])));
		}
		return jobs;
	}

	private static Integer parseOrNull(final String raw) {
		final String s = raw.strip();
		if (s.isEmpty() || s.equals("-")) {
			return null;
		}
		try {
			return Integer.valueOf(s);
		} catch (final NumberFormatException e) {
			return null;
		}
	}
}
