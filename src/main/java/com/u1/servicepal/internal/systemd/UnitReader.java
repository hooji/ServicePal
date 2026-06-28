package com.u1.servicepal.internal.systemd;

import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a systemd {@code .service} unit (INI) into our model. Best-effort: keys we don't
 * model are ignored (the verbatim text is still available via {@code readNative}).
 */
public final class UnitReader {

	/** Marker key (in {@code [Unit]}) stamped into units we manage. */
	public static final String MANAGED_KEY = "X-ServicePal-Managed";

	/** Side-band marker (in {@code [Unit]}) set when we install over a service we did not create. */
	public static final String ADOPTED_KEY = "X-ServicePal-Adopted";

	/**
	 * Side-band marker (in a {@code .timer}'s {@code [Unit]}) recording the {@link Schedule} in a
	 * form we can parse straight back — {@code interval:<seconds>} or
	 * {@code calendar:<min>,<hour>,<dom>,<month>,<dow>} (empty field = wildcard). Avoids having to
	 * re-parse arbitrary {@code OnCalendar} syntax.
	 */
	public static final String SCHEDULE_KEY = "X-ServicePal-Schedule";

	/** Read a unit file into a flat key→value map (last value wins; section-insensitive). */
	public Map<String, String> parseFile(final Path file) {
		final String text;
		try {
			text = Files.readString(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read unit " + file, e);
		}
		return parse(text);
	}

	public Map<String, String> parse(final String text) {
		final Map<String, String> values = new LinkedHashMap<>();
		for (final String raw : text.split("\n")) {
			final String line = raw.strip();
			if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")
					|| line.startsWith("[")) {
				continue;
			}
			final int eq = line.indexOf('=');
			if (eq < 0) {
				continue;
			}
			final String key = line.substring(0, eq).strip();
			final String value = line.substring(eq + 1).strip();
			values.put(key, value);
		}
		return values;
	}

	public boolean isManaged(final Map<String, String> unit) {
		return unit.containsKey(MANAGED_KEY);
	}

	/** Whether we manage this unit but did not originally create it. */
	public boolean isAdopted(final Map<String, String> unit) {
		return unit.containsKey(ADOPTED_KEY);
	}

	/** The {@link Schedule} recorded in a {@code .timer}'s side-band marker, or {@code null}. */
	public Schedule scheduleOf(final Map<String, String> unit) {
		final String marker = unit.get(SCHEDULE_KEY);
		if (marker == null) {
			return null;
		}
		if (marker.startsWith("interval:")) {
			final Integer seconds = parseIntOrNull(marker.substring("interval:".length()));
			return seconds == null || seconds <= 0 ? null
					: Schedule.every(Duration.ofSeconds(seconds));
		}
		if (marker.startsWith("calendar:")) {
			final String[] parts = marker.substring("calendar:".length()).split(",", -1);
			if (parts.length != 5) {
				return null;
			}
			return Schedule.calendar(new CalendarSpec(parseIntOrNull(parts[0]),
					parseIntOrNull(parts[1]), parseIntOrNull(parts[2]), parseIntOrNull(parts[3]),
					parseIntOrNull(parts[4])));
		}
		return null;
	}

	private static Integer parseIntOrNull(final String raw) {
		final String s = raw.strip();
		if (s.isEmpty()) {
			return null;
		}
		try {
			return Integer.valueOf(s);
		} catch (final NumberFormatException e) {
			return null;
		}
	}

	public boolean enabledByInstall(final Map<String, String> unit) {
		return unit.containsKey("WantedBy") || unit.containsKey("RequiredBy");
	}

	/** Map a parsed unit into a {@link ServiceSpec}. {@code id} is the unit's stem. */
	public ServiceSpec toSpec(final Map<String, String> unit, final Installation installation,
			final String id) {
		final ServiceSpec.Builder b = ServiceSpec.builder().id(id);

		final String description = unit.get("Description");
		if (description != null) {
			b.displayName(description);
		}

		b.command(commandOf(unit));

		final String user = unit.get("User");
		if (user != null) {
			b.runAs(RunAs.namedUser(user));
		} else if (installation == Installation.PER_USER) {
			b.asCurrentUser();
		} else {
			b.asSystemDaemon();
		}

		final String wd = unit.get("WorkingDirectory");
		if (wd != null) {
			b.workingDirectory(Path.of(wd));
		}
		b.restart(restartOf(unit.get("Restart")));
		b.autoStart(enabledByInstall(unit));
		return b.build();
	}

	private static List<String> commandOf(final Map<String, String> unit) {
		final String exec = unit.get("ExecStart");
		if (exec == null || exec.isBlank()) {
			throw new DefinitionIOException("unit has no ExecStart", null);
		}
		// Strip a leading special prefix (-, @, +, !) systemd allows on ExecStart.
		String line = exec.strip();
		while (!line.isEmpty() && "-@+!:".indexOf(line.charAt(0)) >= 0) {
			line = line.substring(1);
		}
		final List<String> parts = new ArrayList<>();
		for (final String token : line.split("\\s+")) {
			if (!token.isEmpty()) {
				parts.add(token);
			}
		}
		return parts;
	}

	private static RestartPolicy restartOf(final String value) {
		if (value == null) {
			return RestartPolicy.NEVER;
		}
		return switch (value.strip()) {
			case "always", "on-abnormal", "on-watchdog", "on-abort" -> RestartPolicy.ALWAYS;
			case "on-failure" -> RestartPolicy.ON_FAILURE;
			default -> RestartPolicy.NEVER;
		};
	}
}
