package com.u1.servicepal.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.Schedule;
import java.time.DayOfWeek;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The picker's {@code setSchedule}/{@code getSchedule} round-trip. Swing components construct fine
 * headless (they are never shown here), so this runs on CI with no display.
 */
class SchedulePanelTest {

	@Test
	void roundTripsADailySchedule() {
		final SchedulePanel panel = new SchedulePanel();
		panel.setSchedule(Schedule.dailyAt(6, 15));
		final CalendarSchedule back = assertInstanceOf(CalendarSchedule.class, panel.getSchedule());
		assertEquals(Integer.valueOf(6), back.spec().hour());
		assertEquals(Integer.valueOf(15), back.spec().minute());
		assertNull(back.spec().dayOfWeek(), "a daily schedule has no day-of-week");
	}

	@Test
	void roundTripsAWeeklySchedule() {
		final SchedulePanel panel = new SchedulePanel();
		panel.setSchedule(Schedule.weeklyAt(DayOfWeek.WEDNESDAY, 8, 0));
		final CalendarSchedule back = assertInstanceOf(CalendarSchedule.class, panel.getSchedule());
		assertEquals(Integer.valueOf(DayOfWeek.WEDNESDAY.getValue()), back.spec().dayOfWeek());
		assertEquals(Integer.valueOf(8), back.spec().hour());
		assertEquals(Integer.valueOf(0), back.spec().minute());
	}

	@Test
	void roundTripsAnEveryMinutesSchedule() {
		final SchedulePanel panel = new SchedulePanel();
		panel.setSchedule(Schedule.everyMinutes(10));
		final IntervalSchedule back = assertInstanceOf(IntervalSchedule.class, panel.getSchedule());
		assertEquals(Duration.ofMinutes(10), back.period());
	}

	@Test
	void defaultsToADailySchedule() {
		// A freshly built picker (before the user touches it) yields a usable schedule.
		assertInstanceOf(CalendarSchedule.class, new SchedulePanel().getSchedule());
	}
}
