package com.u1.servicepal.cli;

import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceException;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.RunState;
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
		final ServiceSpec.Builder builder = ServiceSpec.builder()
				.id(ID)
				.command("/bin/sleep", "120")
				.autoStart(true);
		if (platform == Platform.MACOS_LAUNCHD) {
			builder.asCurrentUser();   // a per-user launchd agent; no root needed
		} else if (platform == Platform.LINUX_SYSTEMD) {
			if (!root) {
				System.out.println("SELFTEST SKIP: the systemd self-test installs a system-wide"
						+ " unit and needs sudo (this is " + platform + ", non-root)");
				return;
			}
			builder.asSystemDaemon();
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

			final ServiceStatus st = mgr.status(ID);
			System.out.println("status: installed=" + st.installed() + " managed=" + st.managed()
					+ " state=" + st.state() + " pid=" + st.pid());
			failures += check("installed", st.installed());
			failures += check("managed by us", st.managed());
			failures += check("running", st.state() == RunState.RUNNING);
			failures += check("has pid", st.pid() != null);
			failures += check("isManaged(id)", mgr.isManaged(ID));

			final String raw = mgr.readNative(ID);
			failures += check("readNative carries marker",
					raw != null && raw.contains("com.u1.servicepal.Managed"));

			final ServiceSpec back = mgr.read(ID);
			failures += check("read round-trips command",
					back != null && back.command().contains("/bin/sleep"));
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

		System.out.println(failures == 0 ? "SELFTEST PASS"
				: "SELFTEST FAIL (" + failures + " failing checks)");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static int check(final String name, final boolean ok) {
		System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + name);
		return ok ? 0 : 1;
	}
}
