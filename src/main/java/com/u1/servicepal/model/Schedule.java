package com.u1.servicepal.model;

import java.time.DayOfWeek;
import java.time.Duration;

/**
 * When a scheduled job runs. A non-null schedule on a {@code ServiceSpec} routes the job to
 * the platform's scheduling backend (launchd {@code StartCalendarInterval} / systemd
 * {@code .timer} / Windows Task Scheduler). OpenRC has no native scheduler (fail-fast).
 */
public sealed interface Schedule permits CalendarSchedule, IntervalSchedule {

	static Schedule every(final Duration period) {
		return new IntervalSchedule(period);
	}

	static Schedule everyMinutes(final int n) {
		return new IntervalSchedule(Duration.ofMinutes(n));
	}

	static Schedule calendar(final CalendarSpec spec) {
		return new CalendarSchedule(spec);
	}

	static Schedule dailyAt(final int hour, final int minute) {
		return new CalendarSchedule(CalendarSpec.dailyAt(hour, minute));
	}

	static Schedule weeklyAt(final DayOfWeek day, final int hour, final int minute) {
		return new CalendarSchedule(CalendarSpec.weeklyAt(day.getValue(), hour, minute));
	}

	static Schedule monthlyAt(final int dayOfMonth, final int hour, final int minute) {
		return new CalendarSchedule(CalendarSpec.monthlyAt(dayOfMonth, hour, minute));
	}
}
