package com.u1.servicepal.internal.windows;

import com.u1.servicepal.DefinitionIOException;
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
import java.util.List;
import java.util.Map;

/**
 * Parses the Windows sidecar JSON back into our model. Best-effort, mirroring the other
 * backends' readers. The read-side counterpart to {@link SidecarWriter}.
 */
public final class SidecarReader {

	/** Marker key; its presence means ServicePal manages this service. */
	public static final String MANAGED_KEY = "servicePalManaged";
	public static final String MANAGED_VALUE = "com.u1.servicepal";

	/** Side-band marker ({@code "true"}) set when we install over a service we did not create. */
	public static final String ADOPTED_KEY = "servicePalAdopted";

	/** Which Windows subsystem this id lives in (so by-id ops route correctly). */
	public static final String KIND_KEY = "kind";
	public static final String KIND_SERVICE = "SERVICE";
	public static final String KIND_TASK = "TASK";

	public Map<String, Object> parse(final String json) {
		return Json.parseObject(json);
	}

	public Map<String, Object> parseFile(final Path file) {
		try {
			return Json.parseObject(Files.readString(file));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read sidecar " + file, e);
		}
	}

	public boolean isManaged(final Map<String, Object> sidecar) {
		return MANAGED_VALUE.equals(sidecar.get(MANAGED_KEY));
	}

	/** Whether we manage this service but did not originally create it. */
	public boolean isAdopted(final Map<String, Object> sidecar) {
		return Boolean.parseBoolean(str(sidecar.get(ADOPTED_KEY)));
	}

	/** {@link #KIND_TASK} for a scheduled job, else {@link #KIND_SERVICE} (the default). */
	public String kind(final Map<String, Object> sidecar) {
		return KIND_TASK.equals(sidecar.get(KIND_KEY)) ? KIND_TASK : KIND_SERVICE;
	}

	public boolean autoStart(final Map<String, Object> sidecar) {
		return Boolean.parseBoolean(str(sidecar.get("autoStart")));
	}

	/** Map a parsed sidecar into a {@link ServiceSpec}. {@code id} is the sidecar's stem. */
	public ServiceSpec toSpec(final Map<String, Object> sidecar, final String id) {
		final ServiceSpec.Builder b = ServiceSpec.builder().id(id);

		final String displayName = str(sidecar.get("displayName"));
		if (displayName != null) {
			b.displayName(displayName);
		}
		final String description = str(sidecar.get("description"));
		if (description != null) {
			b.description(description);
		}

		b.command(stringList(sidecar.get("command")));

		final String workingDirectory = str(sidecar.get("workingDirectory"));
		if (workingDirectory != null) {
			b.workingDirectory(Path.of(workingDirectory));
		}
		for (final Map.Entry<String, String> e : stringMap(sidecar.get("environment")).entrySet()) {
			b.env(e.getKey(), e.getValue());
		}
		final String stdout = str(sidecar.get("stdout"));
		if (stdout != null) {
			b.stdout(Path.of(stdout));
		}
		final String stderr = str(sidecar.get("stderr"));
		if (stderr != null) {
			b.stderr(Path.of(stderr));
		}
		b.autoStart(autoStart(sidecar));

		final String restart = str(sidecar.get("restart"));
		if (restart != null) {
			b.restart(parseRestart(restart));
		}

		final String runAsKind = str(sidecar.get("runAsKind"));
		final String runAsUser = str(sidecar.get("runAsUser"));
		if (RunAs.Kind.NAMED_USER.name().equals(runAsKind) && runAsUser != null) {
			b.runAs(RunAs.namedUser(runAsUser));
		} else if (RunAs.Kind.SYSTEM_DAEMON.name().equals(runAsKind)) {
			b.asSystemDaemon();
		} else {
			b.asCurrentUser();
		}

		final Schedule schedule = parseSchedule(sidecar.get("schedule"));
		if (schedule != null) {
			b.schedule(schedule);
		}
		return b.build();
	}

	/** Reconstruct a {@link Schedule} from the sidecar (counterpart to the writer's scheduleMap). */
	private static Schedule parseSchedule(final Object value) {
		if (!(value instanceof Map)) {
			return null;
		}
		final Map<?, ?> m = (Map<?, ?>) value;
		final String type = str(m.get("type"));
		if ("interval".equals(type)) {
			final Long seconds = parseLong(str(m.get("periodSeconds")));
			return (seconds == null || seconds <= 0) ? null
					: Schedule.every(Duration.ofSeconds(seconds));
		}
		if ("calendar".equals(type)) {
			return Schedule.calendar(new CalendarSpec(
					parseInt(str(m.get("minute"))), parseInt(str(m.get("hour"))),
					parseInt(str(m.get("dayOfMonth"))), parseInt(str(m.get("month"))),
					parseInt(str(m.get("dayOfWeek")))));
		}
		return null;
	}

	private static Integer parseInt(final String value) {
		if (value == null) {
			return null;
		}
		try {
			return Integer.valueOf(value);
		} catch (final NumberFormatException e) {
			return null;
		}
	}

	private static Long parseLong(final String value) {
		if (value == null) {
			return null;
		}
		try {
			return Long.valueOf(value);
		} catch (final NumberFormatException e) {
			return null;
		}
	}

	private static RestartPolicy parseRestart(final String value) {
		try {
			return RestartPolicy.valueOf(value);
		} catch (final IllegalArgumentException e) {
			return RestartPolicy.NEVER;
		}
	}

	private static String str(final Object value) {
		return value instanceof String ? (String) value : null;
	}

	private static List<String> stringList(final Object value) {
		final List<String> result = new ArrayList<>();
		if (value instanceof List) {
			for (final Object item : (List<?>) value) {
				if (item instanceof String) {
					result.add((String) item);
				}
			}
		}
		return result;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> stringMap(final Object value) {
		final Map<String, String> result = new java.util.LinkedHashMap<>();
		if (value instanceof Map) {
			for (final Map.Entry<String, Object> e : ((Map<String, Object>) value).entrySet()) {
				if (e.getValue() instanceof String) {
					result.put(e.getKey(), (String) e.getValue());
				}
			}
		}
		return result;
	}
}
