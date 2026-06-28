package com.u1.servicepal.internal.openrc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.UnsupportedFeatureException;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import com.u1.servicepal.model.options.OpenRcOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenRcBackendTest {

	private static final String ID = "com.u1.servicepal.test.svc";

	private Path initdDir;
	private Path runlevelsDir;
	private Path runDir;
	private RecordingRcService rc;
	private RecordingCron cron;
	private OpenRcBackend backend;

	@BeforeEach
	void setUp(@TempDir final Path tmp) throws IOException {
		initdDir = Files.createDirectory(tmp.resolve("init.d"));
		runlevelsDir = Files.createDirectory(tmp.resolve("runlevels"));
		Files.createDirectory(runlevelsDir.resolve("default"));
		runDir = Files.createDirectory(tmp.resolve("run"));
		// our service reports started; anything else not-found
		rc = new RecordingRcService(name -> ID.equals(name) ? new RcStatus("started")
				: RcStatus.notFound());
		cron = new RecordingCron();
		backend = new OpenRcBackend(rc, cron, initdDir, runlevelsDir, runDir);
	}

	private static ServiceSpec systemSpec() {
		return ServiceSpec.builder()
				.id(ID)
				.command("/bin/sleep", "60")
				.asSystemDaemon()
				.autoStart(true)
				.build();
	}

	@Test
	void installWritesExecutableScript() throws IOException {
		backend.install(systemSpec(), false);

		final Path file = initdDir.resolve(ID);
		assertTrue(Files.isRegularFile(file));
		final String script = Files.readString(file);
		assertTrue(script.contains("# X-ServicePal-Managed: 1"));
		assertTrue(script.contains("command=\"/bin/sleep\""));
		assertTrue(script.contains("pidfile="));
		// On a POSIX filesystem the script must be executable; skip the check off-POSIX (Windows CI).
		if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
			assertTrue(Files.isExecutable(file), "init script should be executable");
		}
	}

	@Test
	void discoversInstalledServiceWithLiveState() throws IOException {
		backend.install(systemSpec(), false);
		// Simulate `rc-update add ... default` having wired it into a runlevel, and a live pidfile.
		Files.writeString(runlevelsDir.resolve("default").resolve(ID), "");
		Files.writeString(runDir.resolve(ID + ".pid"), "4321\n");

		final Discovery d = backend.discover(Installation.SYSTEM_WIDE);
		assertEquals(1, d.services().size());
		final ServiceStatus s = d.services().get(0);
		assertEquals(ID, s.id());
		assertTrue(s.managed());
		assertTrue(s.enabled());
		assertEquals(RunState.RUNNING, s.state());
		assertEquals(Integer.valueOf(4321), s.pid());
	}

	@Test
	void lifecycleVerbsHitRcService() {
		backend.install(systemSpec(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);
		backend.start(ID, Installation.SYSTEM_WIDE);
		backend.restart(ID, Installation.SYSTEM_WIDE);
		backend.stop(ID, Installation.SYSTEM_WIDE);

		assertTrue(rc.calls.contains("add " + ID + " default"));
		assertTrue(rc.calls.contains("start " + ID));
		assertTrue(rc.calls.contains("restart " + ID));
		assertTrue(rc.calls.contains("stop " + ID));
	}

	@Test
	void enableHonorsCustomRunlevel() {
		final ServiceSpec spec = systemSpec().toBuilder()
				.openrc(OpenRcOptions.builder().runlevel("boot").build())
				.build();
		backend.install(spec, false);
		backend.enable(ID, Installation.SYSTEM_WIDE);

		assertTrue(rc.calls.contains("add " + ID + " boot"));
	}

	@Test
	void uninstallStopsDelsAndDeletes() {
		backend.install(systemSpec(), false);
		backend.uninstall(ID, Installation.SYSTEM_WIDE, false);

		assertFalse(Files.exists(initdDir.resolve(ID)));
		assertTrue(rc.calls.contains("stop " + ID));
		assertTrue(rc.calls.contains("del " + ID + " default"));
	}

	@Test
	void refusesToOverwriteUnmanaged() throws IOException {
		Files.writeString(initdDir.resolve(ID), "#!/sbin/openrc-run\ncommand=/bin/false\n");
		assertThrows(UnmanagedServiceException.class, () -> backend.install(systemSpec(), false));
		backend.install(systemSpec(), true);   // allowed with the override
		assertTrue(Files.readString(initdDir.resolve(ID)).contains("# X-ServicePal-Managed: 1"));
	}

	@Test
	void adoptingAForeignScriptMarksItAdopted() throws IOException {
		Files.writeString(initdDir.resolve(ID), "#!/sbin/openrc-run\ncommand=/bin/false\n");
		backend.install(systemSpec(), true);   // adopt (override required)
		assertTrue(Files.readString(initdDir.resolve(ID)).contains("# X-ServicePal-Adopted: 1"));
		assertTrue(backend.status(ID, Installation.SYSTEM_WIDE).adopted());
	}

	@Test
	void freshInstallIsManagedButNotAdopted() {
		backend.install(systemSpec(), false);
		final ServiceStatus s = backend.status(ID, Installation.SYSTEM_WIDE);
		assertTrue(s.managed());
		assertFalse(s.adopted());
	}

	@Test
	void readUnknownReturnsNull() {
		assertNull(backend.read("com.nope", Installation.SYSTEM_WIDE));
		assertNull(backend.status("com.nope", Installation.SYSTEM_WIDE));
	}

	// --- scheduled jobs (cron fallback) ---

	private static ServiceSpec scheduledSpec() {
		return ServiceSpec.builder()
				.id(ID)
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
	void installScheduledWritesTheRecordButNoRunlevelOrCronYet() throws IOException {
		backend.install(scheduledSpec(), false);

		final String script = Files.readString(initdDir.resolve(ID));
		assertTrue(script.contains("# X-ServicePal-Schedule: calendar:30,3,,,"));
		assertTrue(rc.calls.isEmpty(), "a scheduled job is not wired into a runlevel");
		assertFalse(cron.crontab.contains(ID), "the cron entry is added on enable, not install");
	}

	@Test
	void enablingAScheduledJobArmsACronEntry() {
		backend.install(scheduledSpec(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);

		assertTrue(cron.crontab.contains("# X-ServicePal: " + ID));
		assertTrue(cron.crontab.contains("30 3 * * * /usr/bin/backup --nightly"));
		assertTrue(backend.status(ID, Installation.SYSTEM_WIDE).enabled());
	}

	@Test
	void disablingAScheduledJobRemovesItsCronEntryButKeepsTheRecord() {
		backend.install(scheduledSpec(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);
		backend.disable(ID, Installation.SYSTEM_WIDE);

		assertFalse(cron.crontab.contains(ID), "the cron entry is gone");
		assertTrue(Files.isRegularFile(initdDir.resolve(ID)), "the definition record remains");
		assertFalse(backend.status(ID, Installation.SYSTEM_WIDE).enabled());
	}

	@Test
	void readReconstructsTheScheduleAndCommand() {
		backend.install(scheduledSpec(), false);
		final ServiceSpec back = backend.read(ID, Installation.SYSTEM_WIDE);
		assertNotNull(back);
		final CalendarSchedule schedule = assertInstanceOf(CalendarSchedule.class, back.schedule());
		assertEquals(Integer.valueOf(3), schedule.spec().hour());
		assertEquals(Integer.valueOf(30), schedule.spec().minute());
		assertEquals(List.of("/usr/bin/backup", "--nightly"), back.command());
	}

	@Test
	void startStopRestartAreNoOpsForAScheduledJob() {
		backend.install(scheduledSpec(), false);
		backend.start(ID, Installation.SYSTEM_WIDE);
		backend.stop(ID, Installation.SYSTEM_WIDE);
		backend.restart(ID, Installation.SYSTEM_WIDE);
		assertTrue(rc.calls.isEmpty(), "a scheduled job has no rc-service process to start/stop");
	}

	@Test
	void intervalScheduleUsesACronStep() {
		backend.install(scheduledSpec().toBuilder().schedule(Schedule.everyMinutes(15)).build(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);
		assertTrue(cron.crontab.contains("*/15 * * * * /usr/bin/backup --nightly"));
	}

	@Test
	void inexpressibleIntervalFailsFast() {
		// every 7 minutes has no cron form (7 doesn't divide 60).
		final ServiceSpec weird = scheduledSpec().toBuilder().schedule(Schedule.everyMinutes(7)).build();
		assertThrows(UnsupportedFeatureException.class, () -> backend.install(weird, false));
	}

	@Test
	void uninstallScheduledRemovesTheRecordAndCronEntry() {
		backend.install(scheduledSpec(), false);
		backend.enable(ID, Installation.SYSTEM_WIDE);
		backend.uninstall(ID, Installation.SYSTEM_WIDE, false);
		assertFalse(Files.exists(initdDir.resolve(ID)));
		assertFalse(cron.crontab.contains(ID));
	}

	@Test
	void scheduledStatusComputesNextRunWhenArmed() {
		backend.install(scheduledSpec(), false);
		assertNull(backend.status(ID, Installation.SYSTEM_WIDE).nextRun(),
				"not armed yet (no cron entry) -> no next run");
		backend.enable(ID, Installation.SYSTEM_WIDE);
		final ServiceStatus s = backend.status(ID, Installation.SYSTEM_WIDE);
		assertNotNull(s.nextRun(), "a daily schedule always has a next run once armed");
		assertNull(s.lastRun(), "cron does not record a last run");
	}

	@Test
	void switchingADaemonToAScheduleRemovesItFromItsRunlevel() {
		backend.install(systemSpec(), false);          // a daemon
		backend.enable(ID, Installation.SYSTEM_WIDE);   // rc-update add
		backend.install(scheduledSpec(), false);        // edit into a scheduled job
		assertTrue(rc.calls.contains("del " + ID + " default"),
				"the old daemon is removed from its runlevel so it stops booting");
	}

	@Test
	void perUserDiscoveryIsEmpty() {
		// OpenRC has no per-user installation; discovering it yields nothing rather than throwing.
		assertTrue(backend.discover(Installation.PER_USER).services().isEmpty());
	}
}
