package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.time.DayOfWeek;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TaskXmlWriterTest {

	private final TaskXmlWriter writer = new TaskXmlWriter();

	private static ServiceSpec.Builder base() {
		return ServiceSpec.builder()
				.id("com.example.job")
				.displayName("Acme Job")
				.command("C:\\app\\job.exe", "--run")
				.asSystemDaemon();
	}

	@Test
	void rendersDailyCalendarTrigger() {
		final String xml = writer.render(base().schedule(Schedule.dailyAt(3, 30)).build());
		assertTrue(xml.contains("<CalendarTrigger>"));
		assertTrue(xml.contains("<ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay>"));
		assertTrue(xml.contains("<StartBoundary>2020-01-01T03:30:00</StartBoundary>"));
		assertTrue(xml.contains("<Command>C:\\app\\job.exe</Command>"));
		assertTrue(xml.contains("<Arguments>--run</Arguments>"));
		assertTrue(xml.contains("<UserId>S-1-5-18</UserId>"), "system daemon runs as LocalSystem");
	}

	@Test
	void rendersIntervalTimeTrigger() {
		final String xml = writer.render(base().schedule(Schedule.every(Duration.ofMinutes(5)))
				.build());
		assertTrue(xml.contains("<TimeTrigger>"));
		assertTrue(xml.contains("<Interval>PT5M</Interval>"));
		assertTrue(xml.contains("<StopAtDurationEnd>false</StopAtDurationEnd>"));
	}

	@Test
	void rendersWeeklyTrigger() {
		final String xml = writer.render(
				base().schedule(Schedule.weeklyAt(DayOfWeek.MONDAY, 9, 0)).build());
		assertTrue(xml.contains("<ScheduleByWeek>"));
		assertTrue(xml.contains("<Monday />"));
	}

	@Test
	void quotesArgumentsContainingSpaces() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.job")
				.command("C:\\app\\job.exe", "--path", "C:\\Program Files\\x", "--flag")
				.asSystemDaemon()
				.schedule(Schedule.dailyAt(1, 0))
				.build();
		final String xml = writer.render(spec);
		// The space-containing arg is wrapped in quotes (then XML-escaped); the bare one is not.
		assertTrue(xml.contains("--path &quot;C:\\Program Files\\x&quot; --flag"));
	}

	@Test
	void namedUserPrincipalUsesPasswordLogon() {
		final String xml = writer.render(
				base().asUser("svc-acme").schedule(Schedule.dailyAt(1, 0)).build());
		assertTrue(xml.contains("<UserId>svc-acme</UserId>"));
		assertTrue(xml.contains("<LogonType>Password</LogonType>"));
	}

	@Test
	void rendersMonthlyCalendarTrigger() {
		final String xml = writer.render(base().schedule(Schedule.monthlyAt(15, 6, 30)).build());
		assertTrue(xml.contains("<CalendarTrigger>"));
		assertTrue(xml.contains("<ScheduleByMonth>"));
		assertTrue(xml.contains("<Day>15</Day>"));
		assertTrue(xml.contains("<StartBoundary>2020-01-01T06:30:00</StartBoundary>"));
	}

	@Test
	void rendersCompoundIntervalDuration() {
		// A period that spans hours and minutes must serialize to an ISO-8601 duration (PT1H30M),
		// not "90 minutes" — Task Scheduler only accepts the ISO form.
		final String xml = writer.render(base().schedule(Schedule.every(Duration.ofMinutes(90)))
				.build());
		assertTrue(xml.contains("<Interval>PT1H30M</Interval>"));
	}

	// --- per-user (current-user) tasks ---

	private static ServiceSpec.Builder perUser() {
		return ServiceSpec.builder()
				.id("com.example.job")
				.displayName("Acme Job")
				.command("C:\\app\\job.exe", "--run")
				.asCurrentUser();
	}

	@Test
	void perUserKeepRunningRendersLogonTriggerAsTheCurrentUser() {
		final String xml = writer.render(
				perUser().restart(RestartPolicy.ON_FAILURE).build(), "TESTPC\\tester");
		assertTrue(xml.contains("<LogonTrigger>"), "keep-running per-user => start at logon");
		assertTrue(xml.contains("<UserId>TESTPC\\tester</UserId>"));
		assertTrue(xml.contains("<LogonType>InteractiveToken</LogonType>"));
		assertTrue(xml.contains("<RunLevel>LeastPrivilege</RunLevel>"), "no admin");
		assertTrue(xml.contains("<RestartOnFailure>"), "ON_FAILURE => restart-on-failure");
		assertFalse(xml.contains("<CalendarTrigger>"));
	}

	@Test
	void perUserScheduledRendersTimeTriggerWithInteractiveToken() {
		final String xml = writer.render(
				perUser().schedule(Schedule.dailyAt(3, 30)).build(), "TESTPC\\tester");
		assertTrue(xml.contains("<CalendarTrigger>"), "a scheduled per-user job keeps its trigger");
		assertFalse(xml.contains("<LogonTrigger>"));
		assertTrue(xml.contains("<LogonType>InteractiveToken</LogonType>"));
	}

	@Test
	void keepRunningWithNeverRestartHasNoRestartOnFailure() {
		final String xml = writer.render(
				perUser().restart(RestartPolicy.NEVER).build(), "TESTPC\\tester");
		assertFalse(xml.contains("<RestartOnFailure>"), "NEVER => no auto-restart");
	}
}
