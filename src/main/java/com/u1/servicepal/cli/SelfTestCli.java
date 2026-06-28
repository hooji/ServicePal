package com.u1.servicepal.cli;

import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceException;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;

/**
 * Exercises the real mutation path end-to-end: install → start → inspect → uninstall a
 * throwaway PER_USER agent, printing PASS/FAIL per check. Intended to be RUN on a real Mac
 * (locally or via the CI probe) — it actually talks to launchctl. On non-macOS platforms it
 * prints SKIP. Exits non-zero if any check fails.
 */
public final class SelfTestCli {

	private static final String ID = "com.u1.servicepal.selftest";

	private SelfTestCli() {
	}

	public static void main(final String[] args) {
		final ServiceManager mgr;
		try {
			mgr = ServiceManager.getServiceManager();
		} catch (final ServiceException e) {
			System.out.println("SELFTEST SKIP: could not initialize: " + e.getMessage());
			return;
		}
		final Platform platform = mgr.platform();
		final boolean root = "root".equals(System.getProperty("user.name"));
		final ServiceSpec.Builder builder = ServiceSpec.builder().id(ID).autoStart(true);
		if (platform == Platform.MACOS_LAUNCHD) {
			builder.command("/bin/sleep", "120").asCurrentUser();   // per-user agent; no root needed
		} else if (platform == Platform.LINUX_SYSTEMD || platform == Platform.LINUX_OPENRC) {
			if (!root) {
				System.out.println("SELFTEST SKIP: the " + platform + " self-test installs a"
						+ " system-wide service and needs root/sudo (this is non-root)");
				return;
			}
			builder.command("/bin/sleep", "120").asSystemDaemon();
		} else if (platform == Platform.WINDOWS) {
			// A long-running, console-less command supervised by our bundled FFM ServiceHost.
			// Installing a machine-wide service needs admin; the GitHub windows runner is admin.
			builder.command("ping", "-n", "120", "127.0.0.1").asSystemDaemon();
		} else {
			System.out.println("SELFTEST SKIP: mutation not implemented for " + platform);
			return;
		}
		final ServiceSpec spec = builder.build();

		int failures = 0;
		try {
			if (mgr.isInstalled(ID)) {
				mgr.uninstall(ID, true);
			}
			mgr.installEnableStart(spec);

			// Starting can be asynchronous (Windows StartService returns before the host reaches
			// RUNNING); poll briefly for RUNNING. A no-op once it is already running.
			ServiceStatus st = mgr.status(ID);
			for (int i = 0; i < 30 && st.state() != RunState.RUNNING; i++) {
				Thread.sleep(500);
				st = mgr.status(ID);
			}
			System.out.println("status: installed=" + st.installed() + " managed=" + st.managed()
					+ " state=" + st.state() + " pid=" + st.pid());
			failures += check("installed", st.installed());
			failures += check("managed by us", st.managed());
			failures += check("running", st.state() == RunState.RUNNING);
			failures += check("has pid", st.pid() != null);
			failures += check("isManaged(id)", mgr.isManaged(ID));

			final String raw = mgr.readNative(ID);
			// The managed marker is platform-specific; isManaged(id) above already proves it's
			// recognized. Here just confirm readNative returns this service's definition.
			failures += check("readNative returns the definition", raw != null && raw.contains(ID));

			final ServiceSpec back = mgr.read(ID);
			failures += check("read round-trips command",
					back != null && back.command().equals(spec.command()));

			if (platform == Platform.WINDOWS) {
				// Exercise the in-place upsert (ChangeServiceConfigW) on the real SCM: re-install
				// the same id with a changed display name and confirm it stays installed/running.
				mgr.install(spec.toBuilder().displayName("ServicePal self-test (upsert)").build());
				final ServiceStatus after = mgr.status(ID);
				failures += check("upsert keeps it installed", after.installed());
				failures += check("upsert keeps it running", after.state() == RunState.RUNNING);
			}
		} catch (final Throwable t) {
			System.out.println("SELFTEST ERROR: " + t);
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

		try {
			failures += check("uninstalled", !mgr.isInstalled(ID));
		} catch (final Throwable t) {
			System.out.println("post-check error: " + t);
			failures++;
		}

		// systemd: also exercise a real scheduled job (.timer). `start` arms the timer, so a
		// malformed OnCalendar would make systemd reject the unit and fail here — exactly the kind
		// of real-systemd bug unit tests can't see.
		if (platform == Platform.LINUX_SYSTEMD && root) {
			failures += scheduledSelfTest(mgr);
		}

		System.out.println(failures == 0 ? "SELFTEST PASS"
				: "SELFTEST FAIL (" + failures + " failing checks)");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/** Install → arm → inspect → uninstall a throwaway scheduled job (systemd {@code .timer}). */
	private static int scheduledSelfTest(final ServiceManager mgr) {
		final String sid = ID + ".scheduled";
		int failures = 0;
		try {
			if (mgr.isInstalled(sid)) {
				mgr.uninstall(sid, true);
			}
			final ServiceSpec scheduled = ServiceSpec.builder()
					.id(sid)
					.command("/bin/true")
					.asSystemDaemon()
					.schedule(Schedule.dailyAt(3, 30))
					.build();
			// start arms the .timer; systemd rejects (and this throws) if our OnCalendar is bad.
			mgr.installEnableStart(scheduled);
			final ServiceStatus st = mgr.status(sid);
			System.out.println("scheduled: installed=" + st.installed() + " enabled=" + st.enabled());
			failures += check("scheduled: installed", st.installed());
			failures += check("scheduled: timer enabled/armed", st.enabled());
			final ServiceSpec back = mgr.read(sid);
			failures += check("scheduled: schedule round-trips",
					back != null && back.schedule() instanceof CalendarSchedule);
		} catch (final Throwable t) {
			System.out.println("SCHEDULED SELFTEST ERROR: " + t);
			t.printStackTrace(System.out);
			failures++;
		} finally {
			try {
				if (mgr.isInstalled(sid)) {
					mgr.uninstall(sid, true);
				}
			} catch (final Throwable t) {
				System.out.println("scheduled cleanup warning: " + t);
			}
		}
		return failures;
	}

	private static int check(final String name, final boolean ok) {
		System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name);
		return ok ? 0 : 1;
	}
}
