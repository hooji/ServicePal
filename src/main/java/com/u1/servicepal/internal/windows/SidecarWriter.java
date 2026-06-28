package com.u1.servicepal.internal.windows;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a {@link ServiceSpec} to the Windows sidecar JSON written to
 * {@code %ProgramData%\ServicePal\<id>.json}. The sidecar is our record of the spec (the SCM
 * registry holds only the host's {@code binPath}, not the real command): the {@link ServiceHost}
 * reads it to learn what to run, {@code read()} reconstructs a spec from it, and its presence is
 * our managed-by marker. The write-side counterpart to {@link SidecarReader}.
 */
public final class SidecarWriter {

	/** @param scheduled true for a Task Scheduler job, false for a service (daemon). */
	public String render(final ServiceSpec spec, final boolean scheduled) {
		return render(spec, scheduled, false);
	}

	/**
	 * @param scheduled true for a Task Scheduler job, false for a service (daemon)
	 * @param adopted   true when we are installing over a service we did not create
	 */
	public String render(final ServiceSpec spec, final boolean scheduled, final boolean adopted) {
		final Map<String, Object> o = new LinkedHashMap<>();
		o.put(SidecarReader.MANAGED_KEY, SidecarReader.MANAGED_VALUE);
		if (adopted) {
			o.put(SidecarReader.ADOPTED_KEY, Boolean.toString(true));
		}
		o.put(SidecarReader.KIND_KEY, scheduled ? SidecarReader.KIND_TASK : SidecarReader.KIND_SERVICE);
		o.put("id", spec.id());
		o.put("displayName", spec.displayName());
		o.put("description", spec.description());                 // null omitted by Json
		o.put("command", new ArrayList<>(spec.command()));
		o.put("workingDirectory", pathOrNull(spec.workingDirectory()));
		o.put("environment", new LinkedHashMap<String, Object>(spec.environment()));
		o.put("stdout", pathOrNull(spec.stdout()));
		o.put("stderr", pathOrNull(spec.stderr()));
		o.put("autoStart", Boolean.toString(spec.autoStart()));
		o.put("restart", spec.restart().name());
		o.put("runAsKind", spec.runAs().kind().name());
		o.put("runAsUser", spec.runAs().userName());             // null unless NAMED_USER
		if (spec.schedule() != null) {
			o.put("schedule", scheduleMap(spec.schedule()));
		}
		return Json.write(o);
	}

	private static Object pathOrNull(final Path path) {
		return path == null ? null : path.toString();
	}

	/** Serialize a {@link Schedule} so {@code read()} can round-trip a scheduled job. Values are
	 * strings (the sidecar {@link Json} encodes scalars as strings). */
	private static Map<String, Object> scheduleMap(final Schedule schedule) {
		final Map<String, Object> m = new LinkedHashMap<>();
		if (schedule instanceof IntervalSchedule interval) {
			m.put("type", "interval");
			m.put("periodSeconds", Long.toString(interval.period().toSeconds()));
		} else if (schedule instanceof CalendarSchedule calendar) {
			final CalendarSpec spec = calendar.spec();
			m.put("type", "calendar");
			putIfPresent(m, "minute", spec.minute());
			putIfPresent(m, "hour", spec.hour());
			putIfPresent(m, "dayOfMonth", spec.dayOfMonth());
			putIfPresent(m, "month", spec.month());
			putIfPresent(m, "dayOfWeek", spec.dayOfWeek());
		}
		return m;
	}

	private static void putIfPresent(final Map<String, Object> m, final String key,
			final Integer value) {
		if (value != null) {
			m.put(key, Integer.toString(value));
		}
	}
}
