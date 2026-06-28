package com.u1.servicepal.cli;

import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceException;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;

/**
 * Reproduces the macOS "rename a running per-user service" bug end-to-end against real launchd.
 *
 * <p>Mirrors exactly what the GUI does when you edit a job's name: install + start a per-user agent,
 * then <em>upsert</em> it with a changed display name and (as the GUI's Save does) enable + start.
 * The upsert rewrites the plist and reloads the service ({@code bootout} then {@code bootstrap});
 * because {@code bootout} is asynchronous, a {@code bootstrap} issued too soon races the teardown
 * and fails with "Bootstrap failed: 5: Input/output error" — which then leaves the service booted
 * out, so the follow-up start fails with "Could not find service".
 *
 * <p>Runs PER_USER (no root needed — the whole point is that a user-owned agent should be
 * renameable without sudo). macOS only; prints SKIP elsewhere. Exits non-zero if any check fails,
 * so a CI job running it goes red while the bug is present and green once it is fixed.
 */
public final class RenameProbeCli {

	private static final String ID = "com.u1.servicepal.renameprobe";

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

			// 1. Create a per-user agent and start it (a long sleep so it is genuinely running).
			final ServiceSpec original = ServiceSpec.builder()
					.id(ID)
					.displayName("Original Name")
					.command("/bin/sleep", "1000")
					.asCurrentUser()
					.autoStart(true)
					.build();
			mgr.installEnableStart(original);
			final ServiceStatus started = pollRunning(mgr);
			System.out.println("after install: state=" + started.state() + " pid=" + started.pid());
			failures += check("installed + running before rename",
					started.installed() && started.state() == RunState.RUNNING && started.pid() != null);

			// 2. Rename it — the exact GUI Save path: upsert with a new display name, then enable+start.
			final ServiceSpec renamed = mgr.read(ID).toBuilder().displayName("Renamed Service").build();
			boolean renameThrew = false;
			try {
				mgr.install(renamed);
				mgr.enable(ID);
				mgr.start(ID);
			} catch (final RuntimeException e) {
				renameThrew = true;
				System.out.println("rename threw: " + e.getMessage());
			}
			failures += check("rename (upsert) did not error", !renameThrew);

			// 3. After the rename the service must still be installed and running (no cascade).
			final ServiceStatus afterRename = pollRunning(mgr);
			System.out.println("after rename: installed=" + afterRename.installed()
					+ " state=" + afterRename.state() + " pid=" + afterRename.pid());
			failures += check("still installed after rename", afterRename.installed());
			failures += check("still running after rename",
					afterRename.state() == RunState.RUNNING && afterRename.pid() != null);

			final ServiceSpec readBack = mgr.read(ID);
			failures += check("new name round-trips",
					readBack != null && "Renamed Service".equals(readBack.displayName()));

			// 4. And it must still be controllable — the reported cascade was that restart failed
			//    ("Could not find service") because the service had been left booted out.
			boolean restartThrew = false;
			try {
				mgr.restart(ID);
			} catch (final RuntimeException e) {
				restartThrew = true;
				System.out.println("restart threw: " + e.getMessage());
			}
			failures += check("restart after rename did not error", !restartThrew);
			final ServiceStatus afterRestart = pollRunning(mgr);
			failures += check("running after restart", afterRestart.state() == RunState.RUNNING);
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
		for (int i = 0; i < 30 && st.state() != RunState.RUNNING; i++) {
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
