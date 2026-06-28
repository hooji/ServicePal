package com.u1.servicepal.internal.systemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemdBackendTest {

	private static final String UNIT_ID = "com.u1.servicepal.test.svc";
	private static final String UNIT = UNIT_ID + ".service";

	private Path userDir;
	private Path sysDir;
	private RecordingSystemctl systemctl;
	private SystemdBackend backend;

	@BeforeEach
	void setUp(@TempDir final Path tmp) throws IOException {
		userDir = Files.createDirectory(tmp.resolve("user"));
		sysDir = Files.createDirectory(tmp.resolve("system"));
		// our unit reports running; anything else not-found
		systemctl = new RecordingSystemctl((user, unit) -> UNIT.equals(unit)
				? new UnitState("loaded", "active", "running", "enabled", 4321, 0)
				: UnitState.notFound());
		backend = new SystemdBackend(systemctl, Map.of(
				Installation.PER_USER, userDir,
				Installation.SYSTEM_WIDE, sysDir));
	}

	private static ServiceSpec systemSpec() {
		return ServiceSpec.builder()
				.id(UNIT_ID)
				.command("/bin/sleep", "60")
				.asSystemDaemon()
				.autoStart(true)
				.build();
	}

	@Test
	void installWritesUnitAndReloads() throws IOException {
		backend.install(systemSpec(), false);

		final Path file = sysDir.resolve(UNIT);
		assertTrue(Files.isRegularFile(file));
		assertTrue(Files.readString(file).contains("X-ServicePal-Managed=1"));
		assertTrue(systemctl.calls.contains("daemon-reload system"));
	}

	@Test
	void discoversInstalledUnitWithLiveState() {
		backend.install(systemSpec(), false);

		final Discovery d = backend.discover(Installation.SYSTEM_WIDE);
		assertEquals(1, d.services().size());
		final ServiceStatus s = d.services().get(0);
		assertEquals(UNIT_ID, s.id());
		assertTrue(s.managed());
		assertTrue(s.enabled());
		assertEquals(RunState.RUNNING, s.state());
		assertEquals(Integer.valueOf(4321), s.pid());
	}

	@Test
	void lifecycleVerbsHitSystemctl() {
		backend.install(systemSpec(), false);
		backend.enable(UNIT_ID, Installation.SYSTEM_WIDE);
		backend.start(UNIT_ID, Installation.SYSTEM_WIDE);
		backend.restart(UNIT_ID, Installation.SYSTEM_WIDE);
		backend.stop(UNIT_ID, Installation.SYSTEM_WIDE);

		assertTrue(systemctl.calls.contains("enable system " + UNIT));
		assertTrue(systemctl.calls.contains("start system " + UNIT));
		assertTrue(systemctl.calls.contains("restart system " + UNIT));
		assertTrue(systemctl.calls.contains("stop system " + UNIT));
	}

	@Test
	void uninstallStopsDisablesDeletesReloads() {
		backend.install(systemSpec(), false);
		backend.uninstall(UNIT_ID, Installation.SYSTEM_WIDE, false);

		assertFalse(Files.exists(sysDir.resolve(UNIT)));
		assertTrue(systemctl.calls.contains("stop system " + UNIT));
		assertTrue(systemctl.calls.contains("disable system " + UNIT));
	}

	@Test
	void refusesToOverwriteUnmanaged() throws IOException {
		Files.writeString(sysDir.resolve(UNIT), "[Service]\nExecStart=/bin/false\n");
		assertThrows(UnmanagedServiceException.class, () -> backend.install(systemSpec(), false));
		backend.install(systemSpec(), true);   // allowed with the override
		assertTrue(Files.readString(sysDir.resolve(UNIT)).contains("X-ServicePal-Managed=1"));
	}

	@Test
	void adoptingAForeignUnitMarksItAdopted() throws IOException {
		Files.writeString(sysDir.resolve(UNIT), "[Service]\nExecStart=/bin/false\n");
		backend.install(systemSpec(), true);   // adopt (override required)
		assertTrue(Files.readString(sysDir.resolve(UNIT)).contains("X-ServicePal-Adopted=1"));
		assertTrue(backend.status(UNIT_ID, Installation.SYSTEM_WIDE).adopted());
	}

	@Test
	void freshInstallIsManagedButNotAdopted() {
		backend.install(systemSpec(), false);
		final ServiceStatus s = backend.status(UNIT_ID, Installation.SYSTEM_WIDE);
		assertTrue(s.managed());
		assertFalse(s.adopted());
	}

	@Test
	void readUnknownReturnsNull() {
		assertNull(backend.read("com.nope", Installation.SYSTEM_WIDE));
		assertNull(backend.status("com.nope", Installation.SYSTEM_WIDE));
	}

	// --- scheduled jobs (.timer + oneshot .service) ---

	private static ServiceSpec scheduledSpec() {
		return ServiceSpec.builder()
				.id(UNIT_ID)
				.command("/usr/bin/backup", "--nightly")
				.asSystemDaemon()
				.schedule(Schedule.dailyAt(3, 30))
				.build();
	}

	@Test
	void capabilitiesNowAllowScheduling() {
		assertTrue(backend.capabilities().calendarSchedule());
		assertTrue(backend.capabilities().intervalSchedule());
	}

	@Test
	void installScheduledWritesTimerAndOneshotService() throws IOException {
		backend.install(scheduledSpec(), false);

		final String svc = Files.readString(sysDir.resolve(UNIT));
		assertTrue(svc.contains("Type=oneshot"));
		assertFalse(svc.contains("[Install]"), "the oneshot service is triggered by the timer");

		final String timer = Files.readString(sysDir.resolve(UNIT_ID + ".timer"));
		assertTrue(timer.contains("OnCalendar=*-*-* 03:30:00"));
		assertTrue(timer.contains("Persistent=true"));
		assertTrue(timer.contains("WantedBy=timers.target"));
		assertTrue(timer.contains("X-ServicePal-Schedule=calendar:30,3,,,"));
		assertTrue(systemctl.calls.contains("daemon-reload system"));
	}

	@Test
	void intervalScheduleRendersARepeatingTimer() throws IOException {
		backend.install(scheduledSpec().toBuilder().schedule(Schedule.everyMinutes(15)).build(), false);
		final String timer = Files.readString(sysDir.resolve(UNIT_ID + ".timer"));
		assertTrue(timer.contains("OnUnitActiveSec=900s"));
		assertTrue(timer.contains("X-ServicePal-Schedule=interval:900"));
	}

	@Test
	void scheduledLifecycleTargetsTheTimer() {
		backend.install(scheduledSpec(), false);
		backend.enable(UNIT_ID, Installation.SYSTEM_WIDE);
		backend.start(UNIT_ID, Installation.SYSTEM_WIDE);
		backend.stop(UNIT_ID, Installation.SYSTEM_WIDE);
		backend.disable(UNIT_ID, Installation.SYSTEM_WIDE);

		final String timer = UNIT_ID + ".timer";
		assertTrue(systemctl.calls.contains("enable system " + timer));
		assertTrue(systemctl.calls.contains("start system " + timer));
		assertTrue(systemctl.calls.contains("stop system " + timer));
		assertTrue(systemctl.calls.contains("disable system " + timer));
	}

	@Test
	void discoverListsAScheduledJobOnceViaItsTimer() {
		backend.install(scheduledSpec(), false);
		final Discovery d = backend.discover(Installation.SYSTEM_WIDE);
		assertEquals(1, d.services().size(), "the backing oneshot service is not listed separately");
		assertEquals(UNIT_ID, d.services().get(0).id());
	}

	@Test
	void readReconstructsTheScheduleAndCommand() {
		backend.install(scheduledSpec(), false);
		final ServiceSpec back = backend.read(UNIT_ID, Installation.SYSTEM_WIDE);
		assertNotNull(back);
		final CalendarSchedule schedule = assertInstanceOf(CalendarSchedule.class, back.schedule());
		assertEquals(Integer.valueOf(3), schedule.spec().hour());
		assertEquals(Integer.valueOf(30), schedule.spec().minute());
		assertEquals(List.of("/usr/bin/backup", "--nightly"), back.command());
	}

	@Test
	void uninstallScheduledRemovesBothUnits() {
		backend.install(scheduledSpec(), false);
		backend.uninstall(UNIT_ID, Installation.SYSTEM_WIDE, false);
		assertFalse(Files.exists(sysDir.resolve(UNIT)));
		assertFalse(Files.exists(sysDir.resolve(UNIT_ID + ".timer")));
		assertTrue(systemctl.calls.contains("stop system " + UNIT_ID + ".timer"));
		assertTrue(systemctl.calls.contains("disable system " + UNIT_ID + ".timer"));
	}

	@Test
	void scheduledStatusSurfacesTheTimerRunTimes() {
		final java.time.Instant next = java.time.Instant.parse("2026-06-29T03:30:00Z");
		final java.time.Instant last = java.time.Instant.parse("2026-06-28T03:30:00Z");
		final RecordingSystemctl sc = new RecordingSystemctl((user, unit) -> unit.endsWith(".timer")
				? new UnitState("loaded", "active", "waiting", "enabled", null, null, next, last)
				: UnitState.notFound());
		final SystemdBackend b = new SystemdBackend(sc, Map.of(
				Installation.PER_USER, userDir, Installation.SYSTEM_WIDE, sysDir));
		b.install(scheduledSpec(), false);

		final ServiceStatus s = b.status(UNIT_ID, Installation.SYSTEM_WIDE);
		assertEquals(next, s.nextRun());
		assertEquals(last, s.lastRun());
	}

	@Test
	void switchingADaemonToAScheduleDisablesTheOldDaemon() {
		backend.install(systemSpec(), false);          // a managed, enabled daemon
		backend.install(scheduledSpec(), false);       // edit it into a scheduled job
		assertTrue(Files.exists(sysDir.resolve(UNIT_ID + ".timer")));
		assertTrue(systemctl.calls.contains("disable system " + UNIT),
				"the old daemon's enable symlink is removed so it stops auto-starting");
	}
}
