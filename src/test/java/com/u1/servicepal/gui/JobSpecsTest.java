package com.u1.servicepal.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Installation;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobSpecsTest {

	private static final Capabilities PER_USER =
			new Capabilities(true, true, true, true, true, true, true, true, false);
	private static final Capabilities SYSTEM_ONLY =
			new Capabilities(false, true, true, false, false, true, false, true, true);

	@Test
	void buildsCommandFromProgramAndArguments() {
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm(null, "Echo", "/bin/echo", "hello world", "", false,
						RestartPolicy.NEVER), PER_USER);

		assertEquals(List.of("/bin/echo", "hello", "world"), spec.command());
	}

	@Test
	void autoPrivilegeModelPrefersPerUserWhenSupported() {
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm(null, "X", "/bin/true", "", "", true, RestartPolicy.ALWAYS), PER_USER);

		assertEquals(RunAs.Kind.CURRENT_USER, spec.runAs().kind());
		assertEquals(Installation.PER_USER, spec.runAs().installation());
	}

	@Test
	void autoPrivilegeModelFallsBackToSystemDaemon() {
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm(null, "X", "/bin/true", "", "", true, RestartPolicy.ALWAYS), SYSTEM_ONLY);

		assertEquals(RunAs.Kind.SYSTEM_DAEMON, spec.runAs().kind());
		assertEquals(Installation.SYSTEM_WIDE, spec.runAs().installation());
	}

	@Test
	void mapsNameFolderRestartAndAutoStart() {
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm(null, "Nightly Backup", "/usr/local/bin/backup", "--daemon",
						"/var/backups", true, RestartPolicy.ON_FAILURE), PER_USER);

		assertEquals("Nightly Backup", spec.displayName());
		// Compare Path objects, not strings: Path.toString() uses the OS separator, so a string
		// comparison would (correctly) differ on Windows (\var\backups). Both sides go through
		// Path.of on the running OS, so this stays platform-independent.
		assertEquals(Path.of("/var/backups"), spec.workingDirectory());
		assertEquals(RestartPolicy.ON_FAILURE, spec.restart());
		assertTrue(spec.autoStart());
	}

	@Test
	void blankNameAndFolderUseDefaults() {
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm(null, "  ", "/bin/true", "", "   ", false, RestartPolicy.NEVER),
				PER_USER);

		assertEquals(spec.id(), spec.displayName(), "blank name falls back to the id");
		assertNull(spec.workingDirectory(), "blank folder is null");
	}

	@Test
	void preservesExplicitIdForEdits() {
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm("com.example.kept", "X", "/bin/true", "", "", false,
						RestartPolicy.NEVER), PER_USER);

		assertEquals("com.example.kept", spec.id());
	}

	@Test
	void generatesIdWhenAbsent() {
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm(null, "X", "/bin/true", "", "", false, RestartPolicy.NEVER), PER_USER);

		assertTrue(spec.id().startsWith("com.u1.servicepal."));
	}

	@Test
	void tokenizeHonorsDoubleQuotes() {
		assertEquals(List.of("--msg", "hello world", "-v"),
				JobSpecs.tokenize("--msg \"hello world\" -v"));
	}

	@Test
	void tokenizeOfBlankIsEmpty() {
		assertTrue(JobSpecs.tokenize("   ").isEmpty());
		assertTrue(JobSpecs.tokenize(null).isEmpty());
	}

	@Test
	void scheduledFormSetsScheduleAndNormalizesKeepRunningFields() {
		// The form's autoStart/restart are deliberately "keep running" values; a scheduled job must
		// ignore them (and avoid the builder's schedule + ALWAYS guard) — it is a oneshot on a timer.
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm(null, "Backup", "/usr/bin/backup", "--nightly", "", true,
						RestartPolicy.ALWAYS, Schedule.dailyAt(3, 30)), PER_USER);

		final CalendarSchedule schedule = assertInstanceOf(CalendarSchedule.class, spec.schedule());
		assertEquals(Integer.valueOf(3), schedule.spec().hour());
		assertEquals(Integer.valueOf(30), schedule.spec().minute());
		assertFalse(spec.autoStart(), "a scheduled job does not run at load");
		assertEquals(RestartPolicy.NEVER, spec.restart(), "scheduled jobs are oneshot (NEVER)");
	}

	@Test
	void keptRunningFormHasNoSchedule() {
		final ServiceSpec spec = JobSpecs.fromForm(
				new JobForm(null, "X", "/bin/true", "", "", true, RestartPolicy.ALWAYS), PER_USER);

		assertNull(spec.schedule());
		assertTrue(spec.autoStart());
	}
}
