package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SchtasksScheduler}: it builds the right {@code schtasks.exe} argv and maps
 * exit codes / status text, without touching a real Task Scheduler. The {@link CommandRunner} seam
 * is replaced with a recording fake, so every assertion is platform-independent.
 *
 * <p>Adapted from the sibling Windows implementations' scheduler tests to this backend's API: it
 * exposes {@code exists/create/delete/run/end/setEnabled/isRunning} and passes the task name
 * through verbatim (there is no fixed {@code \ServicePal\} task-folder prefix on this branch — the
 * {@link WindowsBackend} uses the service id as the task name).
 */
class SchtasksSchedulerTest {

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

	private static final CommandResult OK = new CommandResult(0, "", "");

	@Test
	void existsReflectsQueryExitCode() {
		final Recorder ok = new Recorder(OK);
		assertTrue(new SchtasksScheduler(ok).exists("job"));
		assertEquals(List.of("schtasks", "/query", "/tn", "job"), ok.commands.get(0));

		final SchtasksScheduler missing =
				new SchtasksScheduler(cmd -> new CommandResult(1, "", "ERROR: task not found"));
		assertFalse(missing.exists("nope"));
	}

	@Test
	void lifecycleVerbsBuildSchtasksCommands() {
		final Recorder rec = new Recorder(OK);
		final TaskScheduler sched = new SchtasksScheduler(rec);
		sched.run("job");
		sched.end("job");
		sched.delete("job");
		sched.setEnabled("job", true);
		sched.setEnabled("job", false);

		assertEquals(List.of("schtasks", "/run", "/tn", "job"), rec.commands.get(0));
		assertEquals(List.of("schtasks", "/end", "/tn", "job"), rec.commands.get(1));
		assertEquals(List.of("schtasks", "/delete", "/tn", "job", "/f"), rec.commands.get(2));
		assertTrue(rec.commands.get(3).contains("/enable"));
		assertTrue(rec.commands.get(4).contains("/disable"));
	}

	@Test
	void createWritesXmlAndPassesRunAsAccount() {
		final Recorder rec = new Recorder(OK);
		new SchtasksScheduler(rec).create("job", "<Task/>", "svc-acct", "s3cret");

		final List<String> cmd = rec.commands.get(0);
		assertEquals(List.of("schtasks", "/create", "/tn", "job", "/xml"), cmd.subList(0, 5));
		assertTrue(cmd.get(5).endsWith(".xml"), "the XML is staged through a temp file");
		assertTrue(cmd.contains("/f"));
		assertEquals("svc-acct", cmd.get(cmd.indexOf("/ru") + 1));
		assertEquals("s3cret", cmd.get(cmd.indexOf("/rp") + 1));
	}

	@Test
	void createForSystemPrincipalOmitsRunAsAccount() {
		final Recorder rec = new Recorder(OK);
		new SchtasksScheduler(rec).create("job", "<Task/>", null, null);
		assertFalse(rec.commands.get(0).contains("/ru"),
				"a null account leaves the principal to the XML (e.g. SYSTEM)");
	}

	@Test
	void createFailureThrows() {
		final SchtasksScheduler sched =
				new SchtasksScheduler(cmd -> new CommandResult(1, "", "Access is denied."));
		assertThrows(NativeCommandException.class, () -> sched.create("job", "<Task/>", null, null));
	}

	@Test
	void isRunningParsesQueryListStatus() {
		final Recorder running = new Recorder(new CommandResult(0, "Status:  Running\n", ""));
		assertTrue(new SchtasksScheduler(running).isRunning("job"));
		assertEquals(List.of("schtasks", "/query", "/tn", "job", "/fo", "list"),
				running.commands.get(0));

		final SchtasksScheduler ready =
				new SchtasksScheduler(cmd -> new CommandResult(0, "Status:  Ready\n", ""));
		assertFalse(ready.isRunning("job"));
	}
}
