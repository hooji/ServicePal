package com.u1.servicepal.internal.macos;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSNumber;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.dd.plist.PropertyListParser;
import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/**
 * Reads a launchd {@code .plist} into our model. The only class that imports {@code dd-plist},
 * so the codec stays swappable. Read-only for now; the writer (renderer) arrives in step 4.
 */
public final class PlistReader {

	/** Marker key written into plists we create, so we can recognize "our" services. */
	public static final String MANAGED_KEY = "com.u1.servicepal.Managed";

	/**
	 * Custom key persisting the friendly {@code displayName}. launchd has no native equivalent
	 * (its {@code Label} is the id), so without this the display name would not survive a
	 * read &rarr; the spec's displayName would fall back to the id. Written only when it differs
	 * from the id.
	 */
	public static final String DISPLAY_NAME_KEY = "com.u1.servicepal.DisplayName";

	/** Parse a plist file into a dictionary. Throws on malformed input. */
	public NSDictionary parseFile(final Path file) {
		final NSObject root;
		try {
			root = PropertyListParser.parse(file.toFile());
		} catch (final Exception e) {
			throw new DefinitionIOException("failed to parse plist " + file, e);
		}
		if (!(root instanceof NSDictionary)) {
			throw new DefinitionIOException("plist root is not a dictionary: " + file, null);
		}
		return (NSDictionary) root;
	}

	/** The {@code Label}, or {@code null} if absent. */
	public String label(final NSDictionary dict) {
		return str(dict.get("Label"));
	}

	public boolean isManaged(final NSDictionary dict) {
		return dict.containsKey(MANAGED_KEY);
	}

	public boolean runAtLoad(final NSDictionary dict) {
		return boolOf(dict.get("RunAtLoad"), false);
	}

	/** Map a parsed dictionary into a {@link ServiceSpec}. {@code fallbackId} is used when the
	 * plist has no {@code Label}. */
	public ServiceSpec toSpec(final NSDictionary dict, final Installation installation,
			final String fallbackId) {
		final ServiceSpec.Builder b = ServiceSpec.builder();

		final String label = label(dict);
		b.id(label != null ? label : fallbackId);

		final String displayName = str(dict.get(DISPLAY_NAME_KEY));
		if (displayName != null) {
			b.displayName(displayName);
		}

		b.command(commandOf(dict));

		final String wd = str(dict.get("WorkingDirectory"));
		if (wd != null) {
			b.workingDirectory(Path.of(wd));
		}

		final NSObject env = dict.get("EnvironmentVariables");
		if (env instanceof NSDictionary) {
			final NSDictionary envDict = (NSDictionary) env;
			for (final String key : envDict.keySet()) {
				b.env(key, str(envDict.get(key)));
			}
		}

		final String userName = str(dict.get("UserName"));
		if (userName != null) {
			b.runAs(RunAs.namedUser(userName));
		} else if (installation == Installation.PER_USER) {
			b.asCurrentUser();
		} else {
			b.asSystemDaemon();
		}

		final String out = str(dict.get("StandardOutPath"));
		if (out != null) {
			b.stdout(Path.of(out));
		}
		final String err = str(dict.get("StandardErrorPath"));
		if (err != null) {
			b.stderr(Path.of(err));
		}

		b.autoStart(runAtLoad(dict));
		b.restart(restartOf(dict));

		final Schedule schedule = scheduleOf(dict);
		if (schedule != null) {
			b.schedule(schedule);
		}

		return b.build();
	}

	private static java.util.List<String> commandOf(final NSDictionary dict) {
		final NSObject args = dict.get("ProgramArguments");
		if (args instanceof NSArray) {
			final NSArray arr = (NSArray) args;
			final java.util.List<String> out = new java.util.ArrayList<>();
			for (int i = 0; i < arr.count(); i++) {
				final String s = str(arr.objectAtIndex(i));
				if (s != null) {
					out.add(s);
				}
			}
			if (!out.isEmpty()) {
				return out;
			}
		}
		final String program = str(dict.get("Program"));
		if (program != null) {
			return java.util.List.of(program);
		}
		throw new DefinitionIOException(
				"plist has neither ProgramArguments nor Program", null);
	}

	private static RestartPolicy restartOf(final NSDictionary dict) {
		final NSObject ka = dict.get("KeepAlive");
		if (ka instanceof NSNumber) {
			return boolOf(ka, false) ? RestartPolicy.ALWAYS : RestartPolicy.NEVER;
		}
		if (ka instanceof NSDictionary) {
			final NSDictionary kd = (NSDictionary) ka;
			if (kd.containsKey("SuccessfulExit") && !boolOf(kd.get("SuccessfulExit"), true)) {
				return RestartPolicy.ON_FAILURE;
			}
			if (kd.containsKey("Crashed") && boolOf(kd.get("Crashed"), false)) {
				return RestartPolicy.ON_FAILURE;
			}
			return RestartPolicy.ALWAYS;
		}
		return RestartPolicy.NEVER;
	}

	private static Schedule scheduleOf(final NSDictionary dict) {
		final NSObject interval = dict.get("StartInterval");
		if (interval instanceof NSNumber) {
			return Schedule.every(Duration.ofSeconds(((NSNumber) interval).longValue()));
		}
		final NSObject cal = dict.get("StartCalendarInterval");
		if (cal instanceof NSDictionary) {
			return Schedule.calendar(calendarOf((NSDictionary) cal));
		}
		if (cal instanceof NSArray) {
			final NSArray arr = (NSArray) cal;
			// Multiple calendar entries: represent the first (lossy; full set comes in step 4).
			if (arr.count() > 0 && arr.objectAtIndex(0) instanceof NSDictionary) {
				return Schedule.calendar(calendarOf((NSDictionary) arr.objectAtIndex(0)));
			}
		}
		return null;
	}

	private static CalendarSpec calendarOf(final NSDictionary d) {
		return new CalendarSpec(
				intOf(d.get("Minute")),
				intOf(d.get("Hour")),
				intOf(d.get("Day")),
				intOf(d.get("Month")),
				intOf(d.get("Weekday")));
	}

	private static String str(final NSObject o) {
		if (o == null) {
			return null;
		}
		if (o instanceof NSString) {
			return ((NSString) o).getContent();
		}
		return o.toString();
	}

	private static Integer intOf(final NSObject o) {
		if (o instanceof NSNumber) {
			return ((NSNumber) o).intValue();
		}
		return null;
	}

	private static boolean boolOf(final NSObject o, final boolean dflt) {
		if (o instanceof NSNumber) {
			return ((NSNumber) o).boolValue();
		}
		return dflt;
	}

	/** Exposed for callers that need the raw key set (kept for symmetry/testing). */
	public Set<String> keys(final NSDictionary dict) {
		return dict.keySet();
	}
}
