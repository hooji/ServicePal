package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.Platform;
import com.u1.servicepal.UnsupportedFeatureException;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.Schedule;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Maps a {@link Schedule} to/from the bits OpenRC scheduling needs: a side-band marker stored in the
 * init script (so the schedule round-trips exactly, like macOS/systemd do) and the cron line crond
 * actually runs.
 *
 * <p>Cron expresses calendar schedules cleanly. Intervals only fit when the period divides evenly
 * into a minute or an hour (a cron step field); other intervals (e.g. "every 90 minutes") have no
 * cron form and {@link #toCronLine} fails fast.
 */
final class CronSchedule {

	private CronSchedule() {
	}

	/** Encode a schedule for the {@code X-ServicePal-Schedule} init-script marker. */
	static String encodeMarker(final Schedule schedule) {
		if (schedule instanceof IntervalSchedule interval) {
			return "interval:" + interval.period().toSeconds();
		}
		final CalendarSpec s = ((CalendarSchedule) schedule).spec();
		return "calendar:" + nz(s.minute()) + "," + nz(s.hour()) + "," + nz(s.dayOfMonth())
				+ "," + nz(s.month()) + "," + nz(s.dayOfWeek());
	}

	/** Decode the {@code X-ServicePal-Schedule} marker value, or {@code null} if unparseable. */
	static Schedule decodeMarker(final String marker) {
		if (marker == null) {
			return null;
		}
		if (marker.startsWith("interval:")) {
			final Integer seconds = intOrNull(marker.substring("interval:".length()));
			return seconds == null || seconds <= 0 ? null : Schedule.every(Duration.ofSeconds(seconds));
		}
		if (marker.startsWith("calendar:")) {
			final String[] p = marker.substring("calendar:".length()).split(",", -1);
			if (p.length != 5) {
				return null;
			}
			return Schedule.calendar(new CalendarSpec(intOrNull(p[0]), intOrNull(p[1]),
					intOrNull(p[2]), intOrNull(p[3]), intOrNull(p[4])));
		}
		return null;
	}

	/** The {@code minute hour day-of-month month day-of-week} cron fields for this schedule. */
	static String toCronLine(final Schedule schedule) {
		if (schedule instanceof CalendarSchedule calendar) {
			final CalendarSpec s = calendar.spec();
			return field(s.minute()) + " " + field(s.hour()) + " " + field(s.dayOfMonth())
					+ " " + field(s.month()) + " " + field(s.dayOfWeek());
		}
		final IntervalSchedule interval = (IntervalSchedule) schedule;
		final long seconds = interval.period().toSeconds();
		if (seconds % 60 != 0) {
			throw inexpressible(interval);
		}
		final long minutes = seconds / 60;
		if (minutes < 60) {
			if (60 % minutes != 0) {
				throw inexpressible(interval);   // e.g. every 7 minutes — cron can't express it
			}
			return "*/" + minutes + " * * * *";
		}
		if (minutes % 60 != 0) {
			throw inexpressible(interval);   // e.g. every 90 minutes
		}
		final long hours = minutes / 60;
		if (hours < 24 && 24 % hours == 0) {
			return "0 */" + hours + " * * *";
		}
		if (hours == 24) {
			return "0 0 * * *";   // once a day
		}
		throw inexpressible(interval);
	}

	/**
	 * The next time this schedule's cron line fires at or after {@code from}, in {@code zone}, or
	 * {@code null} if none within a 5-year horizon. cron records no "last run", so only next-run is
	 * derivable on OpenRC. The schedule is assumed cron-expressible (install already fails fast).
	 */
	static Instant nextRun(final Schedule schedule, final Instant from, final ZoneId zone) {
		final String[] f = toCronLine(schedule).split(" ");
		ZonedDateTime t = from.atZone(zone).withSecond(0).withNano(0).plusMinutes(1);
		final ZonedDateTime limit = t.plusYears(5);
		while (t.isBefore(limit)) {
			if (fieldMatches(f[0], t.getMinute()) && fieldMatches(f[1], t.getHour())
					&& fieldMatches(f[2], t.getDayOfMonth()) && fieldMatches(f[3], t.getMonthValue())
					&& dowMatches(f[4], t.getDayOfWeek())) {
				return t.toInstant();
			}
			t = t.plusMinutes(1);
		}
		return null;
	}

	private static boolean fieldMatches(final String field, final int value) {
		if (field.equals("*")) {
			return true;
		}
		if (field.startsWith("*/")) {
			final Integer step = intOrNull(field.substring(2));
			return step != null && step > 0 && value % step == 0;
		}
		final Integer exact = intOrNull(field);
		return exact != null && exact == value;
	}

	private static boolean dowMatches(final String field, final DayOfWeek dayOfWeek) {
		// cron day-of-week is 0-7 with 0 and 7 both Sunday; DayOfWeek is MON=1..SUN=7.
		final int cronDow = dayOfWeek == DayOfWeek.SUNDAY ? 0 : dayOfWeek.getValue();
		if (field.equals("*")) {
			return true;
		}
		if (field.startsWith("*/")) {
			final Integer step = intOrNull(field.substring(2));
			return step != null && step > 0 && cronDow % step == 0;
		}
		final Integer exact = intOrNull(field);
		return exact != null && (exact == 7 ? 0 : exact) == cronDow;
	}

	private static UnsupportedFeatureException inexpressible(final IntervalSchedule interval) {
		return new UnsupportedFeatureException(
				"interval schedule of " + interval.period() + " (cron needs a period dividing a "
						+ "minute or an hour)", Platform.LINUX_OPENRC);
	}

	private static String field(final Integer value) {
		return value == null ? "*" : value.toString();
	}

	private static String nz(final Integer value) {
		return value == null ? "" : value.toString();
	}

	private static Integer intOrNull(final String raw) {
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
}
