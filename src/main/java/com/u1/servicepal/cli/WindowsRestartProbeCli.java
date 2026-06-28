package com.u1.servicepal.cli;

import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceException;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;

/**
 * Reproduces the Windows SCM restart/uninstall races end-to-end against the real Service Control
 * Manager — the Windows analog of the macOS launchd reload race.
 *
 * <p>{@code ControlService(STOP)} is asynchronous: it returns before the service reaches
 * {@code STOPPED}. So {@code restart}'s immediate {@code StartServiceW} hits a still-stopping
 * service and is rejected with {@code ERROR_SERVICE_ALREADY_RUNNING} (which we ignore) — leaving the
 * service STOPPED instead of restarted. And {@code uninstall}'s {@code DeleteService} on a still-
 * running service only <em>marks</em> it for delete, so a quick reinstall of the same id can fail
 * with {@code ERROR_SERVICE_MARKED_FOR_DELETE (1072)}.
 *
 * <p>The daemon is a long-lived command supervised by the bundled FFM service host. SYSTEM_WIDE,
 * so it needs admin (the windows runners are admin). Windows only; prints SKIP elsewhere. Exits
 * non-zero on any failed check, so the CI job is RED while the bug is present and GREEN once fixed.
 */
public final class WindowsRestartProbeCli {

	private static final String ID = "com.u1.servicepal.winrestartprobe";
	private static final int RESTART_ITERATIONS = 8;

	private WindowsRestartProbeCli() {
	}

	public static void main(final String[] args) {
		final ServiceManager mgr;
		try {
			mgr = ServiceManager.getServiceManager();
		} catch (final ServiceException e) {
			System.out.println("WIN-RESTART-PROBE SKIP: could not initialize: " + e.getMessage());
			return;
		}
		if (mgr.platform() != Platform.WINDOWS) {
			System.out.println("WIN-RESTART-PROBE SKIP: Windows only (this is " + mgr.platform() + ")");
			return;
		}

		final ServiceSpec spec = ServiceSpec.builder()
				.id(ID)
				.displayName("Windows Restart Probe")
				.command("ping", "-n", "1000", "127.0.0.1")   // long-lived, console-less
				.asSystemDaemon()
				.autoStart(true)
				.build();

		int failures = 0;
		try {
			if (mgr.isInstalled(ID)) {
				mgr.uninstall(ID, true);
			}

			// 1. Install + start a real service (the FFM host supervises the ping).
			mgr.installEnableStart(spec);
			final ServiceStatus started = pollRunning(mgr);
			System.out.println("after install: state=" + started.state() + " pid=" + started.pid());
			failures += check("installed + running before restart",
					started.installed() && started.state() == RunState.RUNNING && started.pid() != null);

			// 2. Restart repeatedly — exactly the GUI Restart button. Each must leave it RUNNING.
			int restarted = 0;
			for (int i = 1; i <= RESTART_ITERATIONS; i++) {
				try {
					mgr.restart(ID);
				} catch (final RuntimeException e) {
					System.out.println("restart #" + i + " threw: " + e.getMessage());
					break;
				}
				final ServiceStatus afterRestart = pollRunning(mgr);
				if (afterRestart.state() != RunState.RUNNING) {
					System.out.println("restart #" + i + " left it state=" + afterRestart.state());
					break;
				}
				restarted = i;
			}
			System.out.println("completed " + restarted + "/" + RESTART_ITERATIONS + " restarts");
			failures += check("all restarts stayed running", restarted == RESTART_ITERATIONS);

			// 3. Uninstall then immediately reinstall the same id (marked-for-delete hazard).
			mgr.uninstall(ID, true);
			boolean reinstallThrew = false;
			try {
				mgr.installEnableStart(spec);
			} catch (final RuntimeException e) {
				reinstallThrew = true;
				System.out.println("reinstall after uninstall threw: " + e.getMessage());
			}
			failures += check("reinstall right after uninstall did not error", !reinstallThrew);
			if (!reinstallThrew) {
				failures += check("running after reinstall", pollRunning(mgr).state() == RunState.RUNNING);
			}
		} catch (final Throwable t) {
			System.out.println("WIN-RESTART-PROBE ERROR: " + t);
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

		System.out.println(failures == 0 ? "WIN-RESTART-PROBE PASS"
				: "WIN-RESTART-PROBE FAIL (" + failures + " failing checks)");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/** Poll for RUNNING (StartService and the host reaching RUNNING are asynchronous). */
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
