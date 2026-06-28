package com.u1.servicepal.internal.openrc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.u1.servicepal.UnsupportedFeatureException;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.Schedule;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** The Schedule ⇄ cron mapping: cron lines, the round-trip marker, and interval fail-fast. */
class CronScheduleTest {

	@Test
	void calendarSchedulesMapToCronFields() {
		assertEquals("30 3 * * *", CronSchedule.toCronLine(Schedule.dailyAt(3, 30)));
		assertEquals("0 9 * * 1", CronSchedule.toCronLine(Schedule.weeklyAt(DayOfWeek.MONDAY, 9, 0)));
		assertEquals("0 0 15 * *", CronSchedule.toCronLine(Schedule.monthlyAt(15, 0, 0)));
	}

	@Test
	void expressibleIntervalsBecomeCronSteps() {
		assertEquals("*/15 * * * *", CronSchedule.toCronLine(Schedule.everyMinutes(15)));
		assertEquals("0 */2 * * *", CronSchedule.toCronLine(Schedule.every(Duration.ofHours(2))));
		assertEquals("0 0 * * *", CronSchedule.toCronLine(Schedule.every(Duration.ofHours(24))));
	}

	@Test
	void inexpressibleIntervalsFailFast() {
		assertThrows(UnsupportedFeatureException.class,
				() -> CronSchedule.toCronLine(Schedule.everyMinutes(7)));   // 7 ∤ 60
		assertThrows(UnsupportedFeatureException.class,
				() -> CronSchedule.toCronLine(Schedule.everyMinutes(90)));  // 90 min, no cron form
		assertThrows(UnsupportedFeatureException.class,
				() -> CronSchedule.toCronLine(Schedule.every(Duration.ofHours(5))));  // 24 ∤ 5
	}

	@Test
	void computesTheNextRunFromTheSchedule() {
		final ZoneId utc = ZoneId.of("UTC");
		// daily at 03:30, asked at 10:00 today -> the next firing is 03:30 tomorrow.
		assertEquals(Instant.parse("2026-06-29T03:30:00Z"),
				CronSchedule.nextRun(Schedule.dailyAt(3, 30), Instant.parse("2026-06-28T10:00:00Z"), utc));
		// every 15 minutes, asked at 10:07 -> the next */15 boundary is 10:15.
		assertEquals(Instant.parse("2026-06-28T10:15:00Z"),
				CronSchedule.nextRun(Schedule.everyMinutes(15), Instant.parse("2026-06-28T10:07:00Z"), utc));
		// weekly on Monday at 09:00, asked Sunday -> the next Monday.
		assertEquals(Instant.parse("2026-06-29T09:00:00Z"),
				CronSchedule.nextRun(Schedule.weeklyAt(DayOfWeek.MONDAY, 9, 0),
						Instant.parse("2026-06-28T12:00:00Z"), utc));   // 2026-06-28 is a Sunday
	}

	@Test
	void markerRoundTripsCalendarAndInterval() {
		final Schedule calendar = Schedule.dailyAt(3, 30);
		final CalendarSchedule backCal = assertInstanceOf(CalendarSchedule.class,
				CronSchedule.decodeMarker(CronSchedule.encodeMarker(calendar)));
		assertEquals(Integer.valueOf(3), backCal.spec().hour());
		assertEquals(Integer.valueOf(30), backCal.spec().minute());

		final IntervalSchedule backInt = assertInstanceOf(IntervalSchedule.class,
				CronSchedule.decodeMarker(CronSchedule.encodeMarker(Schedule.everyMinutes(15))));
		assertEquals(Duration.ofMinutes(15), backInt.period());
	}
}
