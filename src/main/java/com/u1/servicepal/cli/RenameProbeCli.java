package com.u1.servicepal.cli;

import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceException;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.util.List;

/**
 * Reproduces the macOS "rename a running per-user service" bug end-to-end against real launchd.
 *
 * <p>Mirrors what the GUI does on Save: install + start a per-user agent, then <em>upsert</em> it
 * with a changed display name (and enable + start, as Save does). The upsert rewrites the plist and
 * reloads the service ({@code bootout} then {@code bootstrap}). Because {@code bootout} is
 * asynchronous, a {@code bootstrap} issued before the old instance has finished unloading races the
 * teardown and fails with "Bootstrap failed: 5: Input/output error" — which then leaves the service
 * booted out, so the follow-up start fails with "Could not find service".
 *
 * <p>Two things make the race actually fire on fast CI runners (where a plain {@code /bin/sleep}
 * tears down too quickly to lose the race): the probe's service is deliberately <strong>slow to
 * stop</strong> (it traps SIGTERM and lingers a few seconds, widening the window between bootout and
 * a clean unload), and the rename is <strong>repeated</strong> several times to catch the
 * intermittent timing.
 *
 * <p>Runs PER_USER — no root: the whole point is that a user-owned agent should be renameable
 * without sudo. macOS only; prints SKIP elsewhere. Exits non-zero if any check fails, so a CI job
 * running it is RED while the bug is present and GREEN once it is fixed.
 */
public final class RenameProbeCli {

	private static final String ID = "com.u1.servicepal.renameprobe";
	private static final int RENAME_ITERATIONS = 8;

	/**
	 * A command that keeps running but is slow to stop: on SIGTERM it sleeps a few seconds before
	 * exiting, so launchd's asynchronous {@code bootout} is still tearing it down when the upsert's
	 * {@code bootstrap} fires — the condition that triggers the reload race.
	 */
	private static final List<String> SLOW_TO_STOP = List.of(
			"/bin/sh", "-c", "trap 'sleep 3; exit 0' TERM; while :; do sleep 1; done");

	private RenameProbeCli() {
	}

	public static void main(final String[] args) {
		final ServiceManager mgr;
		try {
			mgr = ServiceManager.getServiceManager();
		} catch (final ServiceException e) {
			System.out.println("RENAME-PROBE SKIP: could not initialize: " + e.getMessage());
			return;
		}
		if (mgr.platform() != Platform.MACOS_LAUNCHD) {
			System.out.println("RENAME-PROBE SKIP: macOS/launchd only (this is " + mgr.platform() + ")");
			return;
		}

		int failures = 0;
		try {
			if (mgr.isInstalled(ID)) {
				mgr.uninstall(ID, true);
			}

			// 1. Create a per-user agent (slow to stop) and start it.
			mgr.installEnableStart(ServiceSpec.builder()
					.id(ID)
					.displayName("Original Name")
					.command(SLOW_TO_STOP)
					.asCurrentUser()
					.autoStart(true)
					.build());
			final ServiceStatus started = pollRunning(mgr);
			System.out.println("after install: state=" + started.state() + " pid=" + started.pid());
			failures += check("installed + running before rename",
					started.installed() && started.state() == RunState.RUNNING && started.pid() != null);

			// 2. Rename repeatedly — exactly the GUI Save path (upsert, then enable + start). Any
			//    iteration that errors, or leaves the service not-running, is the bug.
			int renamed = 0;
			for (int i = 1; i <= RENAME_ITERATIONS; i++) {
				final String name = "Renamed " + i;
				try {
					final ServiceSpec next = mgr.read(ID).toBuilder().displayName(name).build();
					mgr.install(next);
					mgr.enable(ID);
					mgr.start(ID);
				} catch (final RuntimeException e) {
					System.out.println("rename #" + i + " threw: " + e.getMessage());
					break;
				}
				final ServiceStatus afterRename = pollRunning(mgr);
				if (!afterRename.installed() || afterRename.state() != RunState.RUNNING) {
					System.out.println("rename #" + i + " left it installed=" + afterRename.installed()
							+ " state=" + afterRename.state());
					break;
				}
				renamed = i;
			}
			System.out.println("completed " + renamed + "/" + RENAME_ITERATIONS + " renames");
			failures += check("all renames stayed running", renamed == RENAME_ITERATIONS);

			final ServiceSpec readBack = mgr.read(ID);
			failures += check("final name round-trips",
					readBack != null && ("Renamed " + RENAME_ITERATIONS).equals(readBack.displayName()));

			// 3. And it must still be controllable (the reported cascade was that restart failed
			//    with "Could not find service" because the service had been left booted out).
			boolean restartThrew = false;
			try {
				mgr.restart(ID);
			} catch (final RuntimeException e) {
				restartThrew = true;
				System.out.println("restart threw: " + e.getMessage());
			}
			failures += check("restart after renames did not error", !restartThrew);
			failures += check("running after restart", pollRunning(mgr).state() == RunState.RUNNING);
		} catch (final Throwable t) {
			System.out.println("RENAME-PROBE ERROR: " + t);
			t.printStackTrace(System.out);
			failures++;
		} finally {
			try {
				if (mgr.isInstalled(ID)) {
					mgr.uninstall(ID, true);
				}
			} catch (final Throwable t) {
				System.out.println("cleanup warning: " + t);
			}
		}

		System.out.println(failures == 0 ? "RENAME-PROBE PASS"
				: "RENAME-PROBE FAIL (" + failures + " failing checks)");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/** Poll briefly for RUNNING (launchd load/start is asynchronous). */
	private static ServiceStatus pollRunning(final ServiceManager mgr) throws InterruptedException {
		ServiceStatus st = mgr.status(ID);
		for (int i = 0; i < 20 && st.state() != RunState.RUNNING; i++) {
			Thread.sleep(500);
			st = mgr.status(ID);
		}
		return st;
	}

	private static int check(final String name, final boolean ok) {
		System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name);
		return ok ? 0 : 1;
	}
}
