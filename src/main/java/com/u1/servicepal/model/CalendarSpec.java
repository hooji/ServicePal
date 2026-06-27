package com.u1.servicepal.model;

/**
 * A cron-like calendar specification. Each field is a nullable {@link Integer}: {@code null}
 * means "any" (a wildcard). {@code dayOfWeek} is 0-7 with both 0 and 7 meaning Sunday
 * (launchd/cron convention).
 */
public record CalendarSpec(
		Integer minute,
		Integer hour,
		Integer dayOfMonth,
		Integer month,
		Integer dayOfWeek) {

	public static CalendarSpec dailyAt(final int hour, final int minute) {
		return new CalendarSpec(minute, hour, null, null, null);
	}

	public static CalendarSpec weeklyAt(final int dayOfWeek, final int hour, final int minute) {
		return new CalendarSpec(minute, hour, null, null, dayOfWeek);
	}

	public static CalendarSpec monthlyAt(final int dayOfMonth, final int hour, final int minute) {
		return new CalendarSpec(minute, hour, dayOfMonth, null, null);
	}
}
