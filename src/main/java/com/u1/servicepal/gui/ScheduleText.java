package com.u1.servicepal.gui;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.Schedule;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * Pure, display-only formatting of a {@link Schedule} and run-time {@link Instant}s into the short
 * human strings the detail panel shows ("Daily at 03:30", "Every 15 minutes", "Mon 28 Jun, 09:00").
 * Swing-free so it unit-tests with no display.
 */
final class ScheduleText {

	private static final String[] DAY_NAMES = {
			"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

	private static final DateTimeFormatter RUN_TIME =
			DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

	private ScheduleText() {
	}

	/** A short human summary of a schedule, e.g. "Daily at 03:30" or "Every 15 minutes". */
	static String summary(final Schedule schedule) {
		if (schedule == null) {
			return "—";
		}
		if (schedule instanceof IntervalSchedule interval) {
			return "Every " + duration(interval.period());
		}
		final CalendarSpec s = ((CalendarSchedule) schedule).spec();
		if (s.dayOfWeek() != null) {
			return "Weekly on " + dayName(s.dayOfWeek()) + " at " + time(s.hour(), s.minute());
		}
		if (s.dayOfMonth() != null) {
			return "Monthly on day " + s.dayOfMonth() + " at " + time(s.hour(), s.minute());
		}
		if (s.hour() == null && s.minute() != null) {
			return "Hourly at :" + two(s.minute());
		}
		return "Daily at " + time(s.hour(), s.minute());
	}

	/** A run-time instant formatted in the local zone, or "—" when {@code null}. */
	static String runTime(final Instant instant) {
		if (instant == null) {
			return "—";
		}
		return RUN_TIME.withZone(ZoneId.systemDefault()).format(instant);
	}

	/** "HH:MM" with null fields treated as 0. */
	static String time(final Integer hour, final Integer minute) {
		return two(hour == null ? 0 : hour) + ":" + two(minute == null ? 0 : minute);
	}

	/** The weekday name for a cron-style day-of-week (0-7, with both 0 and 7 meaning Sunday). */
	static String dayName(final int cronDayOfWeek) {
		final int index = cronDayOfWeek == 7 ? 0 : cronDayOfWeek;
		if (index < 0 || index > 6) {
			return "day " + cronDayOfWeek;
		}
		return DAY_NAMES[index];
	}

	private static String duration(final Duration period) {
		final long minutes = period.toMinutes();
		if (minutes < 60) {
			return minutes == 1 ? "minute" : minutes + " minutes";
		}
		if (minutes % 60 == 0) {
			final long hours = minutes / 60;
			return hours == 1 ? "hour" : hours + " hours";
		}
		return (minutes / 60) + "h " + (minutes % 60) + "m";
	}

	private static String two(final int value) {
		return value < 10 ? "0" + value : Integer.toString(value);
	}
}
