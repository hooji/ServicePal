package com.u1.servicepal.cli;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Installation;
import com.u1.servicepal.ServiceException;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.ServiceStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny read-only CLI that exercises the discovery API and prints what it finds. No service
 * creation or editing — discovery only.
 *
 * <p>Usage: {@code java -jar servicepal.jar [--managed] [--help]}
 */
public final class DiscoverCli {

	private DiscoverCli() {
	}

	public static void main(final String[] args) {
		boolean managedOnly = false;
		for (final String arg : args) {
			if (arg.equals("--help") || arg.equals("-h")) {
				printUsage();
				return;
			}
			if (arg.equals("--managed")) {
				managedOnly = true;
			} else {
				System.err.println("unknown argument: " + arg);
				printUsage();
				System.exit(2);
				return;
			}
		}

		final ServiceManager mgr;
		try {
			mgr = ServiceManager.getServiceManager();
		} catch (final ServiceException e) {
			System.err.println("Could not initialize: " + e.getMessage());
			System.exit(1);
			return;
		}

		System.out.println("ServicePalForJava — service discovery");
		System.out.println("Platform: " + mgr.platform());
		printCapabilities(mgr.capabilities());
		System.out.println();

		final Discovery discovery;
		try {
			discovery = mgr.discover();
		} catch (final UnsupportedOperationException e) {
			System.out.println("Discovery is implemented for macOS in this build.");
			System.out.println("This platform's backend is coming next: " + e.getMessage());
			return;
		}

		final List<ServiceStatus> services = new ArrayList<>();
		int managedCount = 0;
		for (final ServiceStatus s : discovery.services()) {
			if (s.managed()) {
				managedCount++;
			}
			if (!managedOnly || s.managed()) {
				services.add(s);
			}
		}

		printGrouped(services);
		printUnreadable(discovery.unreadable());

		System.out.println();
		System.out.println("Total: " + services.size() + " service"
				+ (services.size() == 1 ? "" : "s")
				+ (managedOnly ? "" : ", " + managedCount + " managed by ServicePal")
				+ (discovery.unreadable().isEmpty() ? ""
						: ", " + discovery.unreadable().size() + " unreadable"));
	}

	private static void printGrouped(final List<ServiceStatus> services) {
		final Map<Installation, List<ServiceStatus>> byInstall = new LinkedHashMap<>();
		byInstall.put(Installation.PER_USER, new ArrayList<>());
		byInstall.put(Installation.SYSTEM_WIDE, new ArrayList<>());
		for (final ServiceStatus s : services) {
			final Installation key = s.installation() != null ? s.installation()
					: Installation.SYSTEM_WIDE;
			byInstall.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
		}

		for (final Map.Entry<Installation, List<ServiceStatus>> entry : byInstall.entrySet()) {
			final List<ServiceStatus> group = entry.getValue();
			System.out.println(entry.getKey() + "  (" + group.size() + " service"
					+ (group.size() == 1 ? "" : "s") + ")");
			if (group.isEmpty()) {
				System.out.println("  (none)");
				System.out.println();
				continue;
			}
			System.out.printf("  %-9s %-7s %-4s %-4s %s%n", "STATE", "PID", "BOOT", "MGR", "ID");
			for (final ServiceStatus s : group) {
				System.out.printf("  %-9s %-7s %-4s %-4s %s%n",
						s.state(),
						s.pid() == null ? "-" : s.pid().toString(),
						s.enabled() ? "yes" : "no",
						s.managed() ? "*" : "",
						s.id());
			}
			System.out.println();
		}
		System.out.println("(STATE is UNKNOWN for system daemons unless run with sudo — "
				+ "their live state lives in the root-only 'system' launchd domain.)");
	}

	private static void printUnreadable(final List<String> unreadable) {
		if (unreadable.isEmpty()) {
			return;
		}
		System.out.println();
		System.out.println("Unreadable definitions (skipped — insufficient permissions or "
				+ "malformed):");
		for (final String path : unreadable) {
			System.out.println("  " + path);
		}
		System.out.println("(Tip: run with sudo to read root-only definitions.)");
	}

	private static void printCapabilities(final Capabilities c) {
		System.out.println("Capabilities:"
				+ " per-user=" + yn(c.perUserInstall())
				+ " system-wide=" + yn(c.systemWideInstall())
				+ " named-user=" + yn(c.namedUser())
				+ " calendar=" + yn(c.calendarSchedule())
				+ " interval=" + yn(c.intervalSchedule())
				+ " keep-alive=" + yn(c.keepAlive())
				+ " cond-keep-alive=" + yn(c.conditionalKeepAlive())
				+ " log-files=" + yn(c.logFileRedirection())
				+ " structured-status=" + yn(c.structuredStatus()));
	}

	private static String yn(final boolean b) {
		return b ? "Y" : "N";
	}

	private static void printUsage() {
		System.out.println("Usage: java -jar servicepal.jar [--managed] [--help]");
		System.out.println("  --managed   show only services created by ServicePal");
		System.out.println("  --help      show this help");
	}
}
