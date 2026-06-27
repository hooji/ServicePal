package com.u1.servicepal.internal.openrc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultRcServiceTest {

	@Test
	void parsesStartedStoppedCrashed() {
		assertEquals("started",
				DefaultRcService.parseStatus(new CommandResult(0, " * status: started\n", "")).status());
		assertEquals("stopped",
				DefaultRcService.parseStatus(new CommandResult(3, " * status: stopped\n", "")).status());
		assertEquals("crashed",
				DefaultRcService.parseStatus(new CommandResult(1, " * status: crashed\n", "")).status());
	}

	@Test
	void fallsBackToExitCodeWhenTextIsUnrecognized() {
		assertEquals("started",
				DefaultRcService.parseStatus(new CommandResult(0, "", "")).status());
		assertNull(DefaultRcService.parseStatus(new CommandResult(1, "", "boom")).status());
	}

	@Test
	void parseDistinguishesSharedPrefixes() {
		// "starting"/"stopping" must not be misread as "started"/"stopped" (and vice versa). The
		// recognized status word wins over the exit code even when the verb exits non-zero.
		assertEquals("starting", DefaultRcService.parseStatus(
				new CommandResult(0, " * status: starting\n", "")).status());
		assertEquals("stopping", DefaultRcService.parseStatus(
				new CommandResult(0, " * status: stopping\n", "")).status());
		assertEquals("started", DefaultRcService.parseStatus(
				new CommandResult(0, " * status: started\n", "")).status());
		assertEquals("stopped", DefaultRcService.parseStatus(
				new CommandResult(3, " * status: stopped\n", "")).status());
	}

	@Test
	void mutatingVerbsBuildExpectedCommands() {
		final Recorder rec = new Recorder(new CommandResult(0, "", ""));
		final DefaultRcService rc = new DefaultRcService(rec);
		rc.add("api", "default");
		rc.del("api", "default");
		rc.start("api");
		rc.stop("api");
		rc.restart("api");

		assertEquals(List.of("rc-update", "add", "api", "default"), rec.commands.get(0));
		assertEquals(List.of("rc-update", "del", "api", "default"), rec.commands.get(1));
		assertEquals(List.of("rc-service", "api", "start"), rec.commands.get(2));
		assertEquals(List.of("rc-service", "api", "stop"), rec.commands.get(3));
		assertEquals(List.of("rc-service", "api", "restart"), rec.commands.get(4));
	}

	@Test
	void nonZeroExitOnMutationThrows() {
		final DefaultRcService rc =
				new DefaultRcService(cmd -> new CommandResult(1, "", "permission denied"));
		assertThrows(NativeCommandException.class, () -> rc.start("api"));
	}

	/** A {@link CommandRunner} that records every argv it is handed and returns a canned result. */
	private static final class Recorder implements CommandRunner {

		final List<List<String>> commands = new ArrayList<>();
		private final CommandResult result;

		Recorder(final CommandResult result) {
			this.result = result;
		}

		@Override
		public CommandResult run(final List<String> command) {
			commands.add(command);
			return result;
		}
	}
}
