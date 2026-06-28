package com.u1.servicepal.internal.macos;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.options.MacOptions;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link ServiceSpec} to launchd plist XML. The write-side counterpart to
 * {@link PlistReader}; together they keep {@code dd-plist} confined to this package. Every
 * plist we write carries the {@link PlistReader#MANAGED_KEY} marker so discovery can tell it
 * apart from foreign services.
 */
public final class PlistWriter {

	public String render(final ServiceSpec spec) {
		final NSDictionary root = new NSDictionary();
		root.put("Label", spec.id());
		// launchd has no native "friendly name", so persist ours in a side-band key when it
		// differs from the id; PlistReader restores it so displayName round-trips (otherwise a
		// read() would report the id, e.g. the generated uuid).
		if (!spec.displayName().equals(spec.id())) {
			root.put(PlistReader.DISPLAY_NAME_KEY, spec.displayName());
		}
		root.put("ProgramArguments", stringArray(spec.command()));

		if (spec.workingDirectory() != null) {
			root.put("WorkingDirectory", spec.workingDirectory().toString());
		}
		if (!spec.environment().isEmpty()) {
			final NSDictionary env = new NSDictionary();
			for (final Map.Entry<String, String> entry : spec.environment().entrySet()) {
				env.put(entry.getKey(), entry.getValue());
			}
			root.put("EnvironmentVariables", env);
		}
		if (spec.runAs().kind() == RunAs.Kind.NAMED_USER) {
			root.put("UserName", spec.runAs().userName());
		}
		if (spec.stdout() != null) {
			root.put("StandardOutPath", spec.stdout().toString());
		}
		if (spec.stderr() != null) {
			root.put("StandardErrorPath", spec.stderr().toString());
		}

		root.put("RunAtLoad", spec.autoStart());
		putKeepAlive(root, spec);
		putSchedule(root, spec.schedule());
		putMacOptions(root, spec.mac());

		root.put(PlistReader.MANAGED_KEY, true);
		return root.toXMLPropertyList();
	}

	private static NSArray stringArray(final List<String> values) {
		final NSString[] items = new NSString[values.size()];
		for (int i = 0; i < items.length; i++) {
			items[i] = new NSString(values.get(i));
		}
		return new NSArray(items);
	}

	private static void putKeepAlive(final NSDictionary root, final ServiceSpec spec) {
		final MacOptions mac = spec.mac();
		if (mac != null && mac.keepAliveWhen() != null) {
			final NSDictionary ka = new NSDictionary();
			switch (mac.keepAliveWhen()) {
				case CRASHED -> ka.put("Crashed", true);
				case SUCCESSFUL_EXIT -> ka.put("SuccessfulExit", true);
				case NETWORK_STATE -> ka.put("NetworkState", true);
				case PATH_STATE -> ka.put("PathState", new NSDictionary());
			}
			root.put("KeepAlive", ka);
			return;
		}
		switch (spec.restart()) {
			case ALWAYS -> root.put("KeepAlive", true);
			case ON_FAILURE -> {
				final NSDictionary ka = new NSDictionary();
				ka.put("SuccessfulExit", false);
				root.put("KeepAlive", ka);
			}
			case NEVER -> {
				// omit
			}
		}
	}

	private static void putSchedule(final NSDictionary root, final Schedule schedule) {
		if (schedule instanceof IntervalSchedule interval) {
			root.put("StartInterval", interval.period().toSeconds());
		} else if (schedule instanceof CalendarSchedule calendar) {
			root.put("StartCalendarInterval", calendarDict(calendar.spec()));
		}
	}

	private static NSDictionary calendarDict(final CalendarSpec spec) {
		final NSDictionary d = new NSDictionary();
		if (spec.minute() != null) {
			d.put("Minute", spec.minute().longValue());
		}
		if (spec.hour() != null) {
			d.put("Hour", spec.hour().longValue());
		}
		if (spec.dayOfMonth() != null) {
			d.put("Day", spec.dayOfMonth().longValue());
		}
		if (spec.month() != null) {
			d.put("Month", spec.month().longValue());
		}
		if (spec.dayOfWeek() != null) {
			d.put("Weekday", spec.dayOfWeek().longValue());
		}
		return d;
	}

	private static void putMacOptions(final NSDictionary root, final MacOptions mac) {
		if (mac == null) {
			return;
		}
		if (mac.processType() != null) {
			final String name = mac.processType().name();
			root.put("ProcessType", name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT));
		}
		if (mac.lowPriorityIO() != null) {
			root.put("LowPriorityIO", mac.lowPriorityIO().booleanValue());
		}
		if (mac.nice() != null) {
			root.put("Nice", mac.nice().longValue());
		}
		if (mac.throttleInterval() != null) {
			root.put("ThrottleInterval", mac.throttleInterval().toSeconds());
		}
	}
}
