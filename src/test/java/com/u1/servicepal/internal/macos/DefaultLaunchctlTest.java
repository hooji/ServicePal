package com.u1.servicepal.internal.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultLaunchctlTest {

	private static CommandRunner stub(final CommandResult result) {
		return command -> result;
	}

	@Test
	void parsesListOutput() {
		final String out = "PID\tStatus\tLabel\n"
				+ "4242\t0\tcom.example.foo\n"
				+ "-\t0\tcom.acme.bar\n"
				+ "78\t-9\tcom.x.y\n";
		final Launchctl launchctl = new DefaultLaunchctl(stub(new CommandResult(0, out, "")));

		final Map<String, JobInfo> jobs = launchctl.listJobs();

		assertEquals(3, jobs.size());
		assertEquals(Integer.valueOf(4242), jobs.get("com.example.foo").pid());
		assertEquals(Integer.valueOf(0), jobs.get("com.example.foo").lastStatus());
		assertNull(jobs.get("com.acme.bar").pid(), "'-' pid parses to null");
		assertEquals(Integer.valueOf(-9), jobs.get("com.x.y").lastStatus());
	}

	@Test
	void returnsEmptyOnFailure() {
		final Launchctl launchctl = new DefaultLaunchctl(stub(new CommandResult(1, "", "boom")));
		assertTrue(launchctl.listJobs().isEmpty());
	}
}
