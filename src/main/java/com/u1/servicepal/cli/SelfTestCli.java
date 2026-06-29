package com.u1.servicepal.cli;

import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceException;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
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

				// Machine-wide discovery via EnumServicesStatusExW: a Windows box always runs many
				// services, so this actively exercises the FFM enumeration against the real SCM (not
				// just our own service) and proves third-party services now surface in list().
				final java.util.List<ServiceStatus> all = mgr.list();
				boolean sawForeign = false;
				boolean sawOurs = false;
				for (final ServiceStatus s : all) {
					sawForeign = sawForeign || !s.managed();
					sawOurs = sawOurs || ID.equals(s.id());
				}
				System.out.println("machine-wide services discovered: " + all.size());
				failures += check("discovery enumerates machine-wide services", all.size() > 5);
				failures += check("discovery includes third-party (unmanaged) services", sawForeign);
				failures += check("discovery still includes our managed service", sawOurs);
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

		// Linux: also exercise a real scheduled job. On systemd that arms a real `.timer` (a bad
		// OnCalendar would fail here); on OpenRC it round-trips a real crontab entry via busybox
		// `crontab` — both are real-system paths unit tests can't see.
		if ((platform == Platform.LINUX_SYSTEMD || platform == Platform.LINUX_OPENRC) && root) {
			failures += scheduledSelfTest(mgr);
		}

		// Windows: exercise a real per-user job — a current-user Task Scheduler task (no admin). This
		// is the no-admin path the GUI uses by default on Windows; create/read/status/uninstall all
		// work without an interactive session (only actually *running* an InteractiveToken task would
		// need one, so we don't gate on RUNNING here).
		if (platform == Platform.WINDOWS) {
			failures += perUserSelfTest(mgr);
		}

		System.out.println(failures == 0 ? "SELFTEST PASS"
				: "SELFTEST FAIL (" + failures + " failing checks)");
		if (failures > 0) {
			System.exit(1);
		}
	}

	/**
	 * Install → arm → inspect → uninstall a throwaway scheduled job. On systemd this arms a real
	 * {@code .timer}; on OpenRC it round-trips a real crontab entry (busybox {@code crontab}).
	 */
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
			// enable arms it: systemd `start <unit>.timer` rejects a bad OnCalendar; OpenRC writes a
			// real crontab entry. Either failure surfaces here.
			mgr.installEnableStart(scheduled);
			final ServiceStatus st = mgr.status(sid);
			System.out.println("scheduled: installed=" + st.installed() + " enabled=" + st.enabled()
					+ " nextRun=" + st.nextRun());
			failures += check("scheduled: installed", st.installed());
			failures += check("scheduled: enabled/armed", st.enabled());
			// next-run is informational, not a gate: systemd only exposes NextElapseUSecRealtime once
			// the timer is active (a non-booted CI VM may not reach timers.target), and OpenRC
			// computes it. The parse + mapping are covered by unit tests.
			System.out.println("  [INFO] scheduled: nextRun "
					+ (st.nextRun() != null ? "exposed (" + st.nextRun() + ")" : "not exposed here"));
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

	/**
	 * Install → inspect → uninstall a throwaway <em>per-user</em> job: a current-user Task Scheduler
	 * task (no admin). Validates the create/read/status/uninstall path on the real {@code schtasks}.
	 * We do not start it (an InteractiveToken task needs an interactive session, which a CI runner may
	 * lack), so RUNNING is not gated here.
	 */
	private static int perUserSelfTest(final ServiceManager mgr) {
		final String pid = ID + ".peruser";
		int failures = 0;
		try {
			if (mgr.isInstalled(pid)) {
				mgr.uninstall(pid, true);
			}
			final ServiceSpec spec = ServiceSpec.builder()
					.id(pid)
					.command("ping", "-n", "120", "127.0.0.1")
					.asCurrentUser()
					.autoStart(true)
					.restart(RestartPolicy.ON_FAILURE)
					.build();
			mgr.install(spec);   // creates a current-user task; no start-now (logon trigger)
			final ServiceStatus st = mgr.status(pid);
			System.out.println("per-user: installed=" + st.installed() + " managed=" + st.managed()
					+ " installation=" + st.installation());
			failures += check("per-user: installed", st.installed());
			failures += check("per-user: managed by us", st.managed());
			failures += check("per-user: resolves as PER_USER",
					st.installation() == Installation.PER_USER);
			final ServiceSpec back = mgr.read(pid);
			failures += check("per-user: read round-trips command",
					back != null && back.command().equals(spec.command()));
			failures += check("per-user: read round-trips run-as",
					back != null && back.runAs().kind() == RunAs.Kind.CURRENT_USER);
		} catch (final Throwable t) {
			System.out.println("PER-USER SELFTEST ERROR: " + t);
			t.printStackTrace(System.out);
			failures++;
		} finally {
			try {
				if (mgr.isInstalled(pid)) {
					mgr.uninstall(pid, true);
				}
			} catch (final Throwable t) {
				System.out.println("per-user cleanup warning: " + t);
			}
		}
		return failures;
	}

	private static int check(final String name, final boolean ok) {
		System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name);
		return ok ? 0 : 1;
	}
}
