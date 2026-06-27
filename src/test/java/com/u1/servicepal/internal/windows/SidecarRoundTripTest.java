package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SidecarRoundTripTest {

	private final SidecarWriter writer = new SidecarWriter();
	private final SidecarReader reader = new SidecarReader();

	@Test
	void roundTripsAServiceSpec() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.api")
				.displayName("Acme API")
				.command("C:\\app\\api.exe", "--port", "8080")
				.asUser("svc-acme")
				.restart(RestartPolicy.ON_FAILURE)
				.workingDirectory(Path.of("C:\\app"))
				.env("LOG", "info")
				.autoStart(true)
				.build();

		final Map<String, Object> sidecar = reader.parse(writer.render(spec, false));
		assertTrue(reader.isManaged(sidecar));
		assertEquals(SidecarReader.KIND_SERVICE, reader.kind(sidecar));
		assertTrue(reader.autoStart(sidecar));

		final ServiceSpec back = reader.toSpec(sidecar, "com.example.api");
		assertEquals(List.of("C:\\app\\api.exe", "--port", "8080"), back.command());
		assertEquals(RunAs.namedUser("svc-acme"), back.runAs());
		assertEquals(RestartPolicy.ON_FAILURE, back.restart());
		assertEquals(Path.of("C:\\app"), back.workingDirectory());
		assertEquals("info", back.environment().get("LOG"));
		assertEquals("Acme API", back.displayName());
	}

	@Test
	void marksScheduledJobsAsTasks() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.job")
				.command("C:\\app\\job.exe")
				.asSystemDaemon()
				.build();
		final Map<String, Object> sidecar = reader.parse(writer.render(spec, true));
		assertEquals(SidecarReader.KIND_TASK, reader.kind(sidecar));
	}

	@Test
	void roundTripsAnIntervalSchedule() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.poll")
				.command("C:\\app\\poll.exe")
				.asSystemDaemon()
				.schedule(Schedule.every(Duration.ofMinutes(5)))
				.build();
		final ServiceSpec back = reader.toSpec(reader.parse(writer.render(spec, true)),
				"com.example.poll");
		assertTrue(back.schedule() instanceof IntervalSchedule);
		assertEquals(Duration.ofMinutes(5), ((IntervalSchedule) back.schedule()).period());
	}

	@Test
	void roundTripsACalendarSchedule() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.nightly")
				.command("C:\\app\\nightly.exe")
				.asSystemDaemon()
				.schedule(Schedule.dailyAt(3, 30))
				.build();
		final ServiceSpec back = reader.toSpec(reader.parse(writer.render(spec, true)),
				"com.example.nightly");
		assertTrue(back.schedule() instanceof CalendarSchedule);
		final CalendarSchedule calendar = (CalendarSchedule) back.schedule();
		assertEquals(Integer.valueOf(3), calendar.spec().hour());
		assertEquals(Integer.valueOf(30), calendar.spec().minute());
	}

	@Test
	void unmanagedSidecarIsRecognized() {
		// A JSON object without our marker must not read as managed.
		final Map<String, Object> foreign = reader.parse("{ \"id\": \"x\" }");
		assertFalse(reader.isManaged(foreign));
	}

	@Test
	void roundTripsLogPathsAndSpecialCharacterDescription() {
		// A description with embedded quotes and a newline must survive the JSON round-trip, and the
		// stdout/stderr log paths must come back intact (Path-vs-Path comparison is separator-safe
		// on every CI OS).
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.api")
				.command("C:\\app\\api.exe")
				.asSystemDaemon()
				.description("a \"quoted\" description\nwith a newline")
				.stdout(Path.of("C:\\logs\\out.log"))
				.stderr(Path.of("C:\\logs\\err.log"))
				.build();

		final ServiceSpec back = reader.toSpec(reader.parse(writer.render(spec, false)),
				"com.example.api");
		assertEquals("a \"quoted\" description\nwith a newline", back.description());
		assertEquals(Path.of("C:\\logs\\out.log"), back.stdout());
		assertEquals(Path.of("C:\\logs\\err.log"), back.stderr());
	}
}
