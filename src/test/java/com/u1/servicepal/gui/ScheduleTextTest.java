package com.u1.servicepal.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.u1.servicepal.model.CalendarSpec;
import com.u1.servicepal.model.Schedule;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The human-readable schedule/run-time formatting (display-only, Swing-free). */
class ScheduleTextTest {

	@Test
	void dailySummary() {
		assertEquals("Daily at 03:30", ScheduleText.summary(Schedule.dailyAt(3, 30)));
	}

	@Test
	void weeklySummary() {
		assertEquals("Weekly on Monday at 09:00",
				ScheduleText.summary(Schedule.weeklyAt(DayOfWeek.MONDAY, 9, 0)));
		assertEquals("Weekly on Sunday at 00:05",
				ScheduleText.summary(Schedule.weeklyAt(DayOfWeek.SUNDAY, 0, 5)));
	}

	@Test
	void monthlySummary() {
		assertEquals("Monthly on day 15 at 00:00", ScheduleText.summary(Schedule.monthlyAt(15, 0, 0)));
	}

	@Test
	void intervalSummary() {
		assertEquals("Every 15 minutes", ScheduleText.summary(Schedule.everyMinutes(15)));
		assertEquals("Every minute", ScheduleText.summary(Schedule.everyMinutes(1)));
		assertEquals("Every 2 hours", ScheduleText.summary(Schedule.every(Duration.ofHours(2))));
		assertEquals("Every hour", ScheduleText.summary(Schedule.every(Duration.ofHours(1))));
	}

	@Test
	void hourlySummary() {
		// hour = null, minute set → "every hour at :MM".
		assertEquals("Hourly at :05",
				ScheduleText.summary(Schedule.calendar(new CalendarSpec(5, null, null, null, null))));
	}

	@Test
	void nullScheduleAndNullRunTimeRenderAsDash() {
		assertEquals("—", ScheduleText.summary(null));
		assertEquals("—", ScheduleText.runTime(null));
	}

	@Test
	void runTimeFormatsAnInstant() {
		assertNotEquals("—", ScheduleText.runTime(Instant.parse("2026-06-29T03:30:00Z")));
	}
}
