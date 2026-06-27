package com.u1.servicepal.internal.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import com.u1.servicepal.model.RunState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultLaunchctlTest {

	private static final String RUNNING_PRINT = """
			com.example.foo = {
				active count = 1
				path = /Library/LaunchDaemons/com.example.foo.plist
				state = running
				program = /usr/local/bin/foo
				pid = 4242
				last exit code = 0
			}
			""";

	private static final String STOPPED_PRINT = """
			com.example.foo = {
				active count = 0
				state = not running
				last exit code = 2
			}
			""";

	/** Routes commands: `id -u` returns the given uid; `launchctl print` returns the result. */
	private static CommandRunner router(final String uid, final CommandResult print) {
		return command -> {
			if (command.size() >= 2 && command.get(0).equals("id")) {
				return new CommandResult(0, uid + "\n", "");
			}
			return print;
		};
	}

	@Test
	void parsesRunningPrint() {
		final ServiceRuntime rt = DefaultLaunchctl.parsePrint(RUNNING_PRINT);
		assertEquals(RunState.RUNNING, rt.state());
		assertEquals(Integer.valueOf(4242), rt.pid());
		assertEquals(Integer.valueOf(0), rt.lastExitCode());
	}

	@Test
	void parsesStoppedPrint() {
		final ServiceRuntime rt = DefaultLaunchctl.parsePrint(STOPPED_PRINT);
		assertEquals(RunState.STOPPED, rt.state());
		assertNull(rt.pid());
		assertEquals(Integer.valueOf(2), rt.lastExitCode());
	}

	@Test
	void guiRunningServiceIsRunning() {
		final CommandRunner runner = router("501", new CommandResult(0, RUNNING_PRINT, ""));
		final ServiceRuntime rt = new DefaultLaunchctl(runner).runtime(LaunchdDomain.GUI, "com.example.foo");
		assertEquals(RunState.RUNNING, rt.state());
		assertEquals(Integer.valueOf(4242), rt.pid());
	}

	@Test
	void notFoundServiceIsStopped() {
		final CommandRunner runner = router("501",
				new CommandResult(113, "", "Could not find service \"x\" in domain for gui"));
		final ServiceRuntime rt = new DefaultLaunchctl(runner).runtime(LaunchdDomain.GUI, "x");
		assertEquals(RunState.STOPPED, rt.state());
	}

	@Test
	void systemDomainWithoutRootIsUnknown() {
		// Non-root uid -> we must not even claim STOPPED for the unobservable system domain.
		final CommandRunner runner = router("501", new CommandResult(0, RUNNING_PRINT, ""));
		final ServiceRuntime rt = new DefaultLaunchctl(runner).runtime(LaunchdDomain.SYSTEM, "com.example.foo");
		assertEquals(RunState.UNKNOWN, rt.state());
		assertNull(rt.pid());
	}

	@Test
	void systemDomainAsRootReadsState() {
		final CommandRunner runner = router("0", new CommandResult(0, RUNNING_PRINT, ""));
		final ServiceRuntime rt = new DefaultLaunchctl(runner).runtime(LaunchdDomain.SYSTEM, "com.example.foo");
		assertEquals(RunState.RUNNING, rt.state());
		assertEquals(Integer.valueOf(4242), rt.pid());
	}
}
