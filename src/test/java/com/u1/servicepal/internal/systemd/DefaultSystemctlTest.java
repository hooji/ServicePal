package com.u1.servicepal.internal.systemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultSystemctlTest {

	private static final String SHOW_OUTPUT = """
			LoadState=loaded
			ActiveState=active
			SubState=running
			UnitFileState=enabled
			MainPID=4321
			ExecMainStatus=0
			""";

	/** Records commands and returns a canned result. */
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

	@Test
	void parsesShowOutput() {
		final UnitState st = DefaultSystemctl.parseShow(SHOW_OUTPUT);
		assertEquals("loaded", st.loadState());
		assertEquals("active", st.activeState());
		assertEquals("running", st.subState());
		assertEquals("enabled", st.unitFileState());
		assertEquals(Integer.valueOf(4321), st.mainPid());
		assertEquals(Integer.valueOf(0), st.execMainStatus());
	}

	@Test
	void systemVerbHasNoUserFlag() {
		final Recorder rec = new Recorder(new CommandResult(0, "", ""));
		new DefaultSystemctl(rec).start(false, "com.x.service");
		assertEquals(List.of("systemctl", "start", "com.x.service"), rec.commands.get(0));
	}

	@Test
	void userVerbAddsUserFlag() {
		final Recorder rec = new Recorder(new CommandResult(0, "", ""));
		new DefaultSystemctl(rec).enable(true, "com.x.service");
		assertEquals(List.of("systemctl", "--user", "enable", "com.x.service"),
				rec.commands.get(0));
	}

	@Test
	void showRequestsTheExpectedProperties() {
		final Recorder rec = new Recorder(new CommandResult(0, SHOW_OUTPUT, ""));
		new DefaultSystemctl(rec).show(false, "com.x.service");
		final List<String> cmd = rec.commands.get(0);
		assertTrue(cmd.contains("show"));
		assertTrue(cmd.contains("-p"));
		final int p = cmd.indexOf("-p");
		final String props = cmd.get(p + 1);
		assertTrue(props.contains("LoadState"));
		assertTrue(props.contains("ExecMainStatus"));
		assertTrue(props.contains("NextElapseUSecRealtime"));
		assertTrue(props.contains("LastTriggerUSec"));
	}

	@Test
	void parsesTimerRunTimesFromUsec() {
		final UnitState st = DefaultSystemctl.parseShow("""
				NextElapseUSecRealtime=1751168400000000
				LastTriggerUSec=1751082000000000
				""");
		assertEquals(java.time.Instant.ofEpochSecond(1751168400L), st.nextElapseRealtime());
		assertEquals(java.time.Instant.ofEpochSecond(1751082000L), st.lastTrigger());
	}

	@Test
	void treatsZeroUsecAsNoRunTime() {
		final UnitState st = DefaultSystemctl.parseShow("NextElapseUSecRealtime=0\nLastTriggerUSec=0\n");
		assertEquals(null, st.nextElapseRealtime());
		assertEquals(null, st.lastTrigger());
	}

	@Test
	void showReturnsNotFoundOnFailure() {
		final Recorder rec = new Recorder(new CommandResult(1, "", "no such unit"));
		assertEquals("not-found", new DefaultSystemctl(rec).show(false, "nope.service").loadState());
	}
}
