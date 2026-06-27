package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.UnsupportedFeatureException;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import com.u1.servicepal.model.options.WindowsOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsBackendTest {

	private static final String ID = "com.u1.servicepal.test.svc";
	private static final String JAVAW = "C:\\jdk\\bin\\javaw.exe";
	private static final String JAR = "C:\\app\\servicepal.jar";

	private Path sidecarDir;
	private RecordingScm scm;
	private RecordingTaskScheduler tasks;
	private WindowsBackend backend;

	@BeforeEach
	void setUp(@TempDir final Path tmp) {
		sidecarDir = tmp.resolve("ServicePal");
		scm = new RecordingScm();
		tasks = new RecordingTaskScheduler();
		backend = new WindowsBackend(scm, tasks, sidecarDir, JAVAW, JAR);
	}

	private static ServiceSpec daemon() {
		return ServiceSpec.builder()
				.id(ID)
				.command("C:\\app\\api.exe", "--port", "8080")
				.asSystemDaemon()
				.autoStart(true)
				.build();
	}

	private static ServiceSpec scheduled() {
		return ServiceSpec.builder()
				.id(ID)
				.command("C:\\app\\job.exe")
				.asSystemDaemon()
				.schedule(Schedule.dailyAt(3, 0))
				.build();
	}

	@Test
	void installDaemonWritesSidecarAndCreatesService() throws IOException {
		backend.install(daemon(), false);

		final Path sidecar = sidecarDir.resolve(ID + ".json");
		assertTrue(Files.isRegularFile(sidecar));
		assertTrue(Files.readString(sidecar).contains(SidecarReader.MANAGED_VALUE));
		assertTrue(scm.services.contains(ID));
		assertEquals(ServiceStartType.AUTO, scm.lastStartType);   // autoStart=true
		assertNull(scm.lastAccount, "system daemon => LocalSystem (null account)");
		// binPath invokes our FFM host with native access enabled, for this id.
		assertTrue(scm.lastBinPath.contains(JAVAW));
		assertTrue(scm.lastBinPath.contains("--enable-native-access=ALL-UNNAMED"));
		assertTrue(scm.lastBinPath.contains(ServiceHost.class.getName()));
		assertTrue(scm.lastBinPath.contains("--id " + ID));
	}

	@Test
	void installScheduledCreatesTaskNotService() {
		backend.install(scheduled(), false);

		assertTrue(tasks.tasks.contains(ID));
		assertFalse(scm.services.contains(ID), "a scheduled job must not become a service");
		assertTrue(tasks.lastXml.contains("<CalendarTrigger>"));
	}

	@Test
	void daemonLifecycleRoutesToScm() {
		backend.install(daemon(), false);
		backend.start(ID, Installation.SYSTEM_WIDE);
		backend.stop(ID, Installation.SYSTEM_WIDE);
		backend.enable(ID, Installation.SYSTEM_WIDE);
		backend.disable(ID, Installation.SYSTEM_WIDE);

		assertTrue(scm.calls.contains("start " + ID));
		assertTrue(scm.calls.contains("stop " + ID));
		assertTrue(scm.calls.contains("setStartType " + ID + " " + ServiceStartType.AUTO));
		assertTrue(scm.calls.contains("setStartType " + ID + " " + ServiceStartType.DEMAND));
	}

	@Test
	void scheduledLifecycleRoutesToTaskScheduler() {
		backend.install(scheduled(), false);
		backend.start(ID, Installation.SYSTEM_WIDE);
		backend.stop(ID, Installation.SYSTEM_WIDE);
		backend.disable(ID, Installation.SYSTEM_WIDE);

		assertTrue(tasks.calls.contains("run " + ID));
		assertTrue(tasks.calls.contains("end " + ID));
		assertTrue(tasks.calls.contains("setEnabled " + ID + " false"));
	}

	@Test
	void uninstallDaemonStopsDeletesAndRemovesSidecar() {
		backend.install(daemon(), false);
		backend.uninstall(ID, Installation.SYSTEM_WIDE, false);

		assertFalse(Files.exists(sidecarDir.resolve(ID + ".json")));
		assertTrue(scm.calls.contains("stop " + ID));
		assertTrue(scm.calls.contains("delete " + ID));
	}

	@Test
	void refusesToOverwriteUnmanagedService() {
		scm.services.add(ID);   // a pre-existing service we didn't create (no sidecar)
		assertThrows(UnmanagedServiceException.class, () -> backend.install(daemon(), false));
		backend.install(daemon(), true);   // allowed with the override
		assertTrue(Files.exists(sidecarDir.resolve(ID + ".json")));
	}

	@Test
	void perUserInstallFailsFast() {
		final ServiceSpec perUser = ServiceSpec.builder()
				.id(ID).command("C:\\app\\api.exe").asCurrentUser().build();
		assertThrows(UnsupportedFeatureException.class, () -> backend.install(perUser, false));
	}

	@Test
	void statusReportsLiveStateForManagedDaemon() {
		backend.install(daemon(), false);
		final ServiceStatus s = backend.status(ID, Installation.SYSTEM_WIDE);
		assertEquals(RunState.RUNNING, s.state());
		assertEquals(Integer.valueOf(1234), s.pid());
		assertTrue(s.managed());
		assertTrue(s.enabled());   // autoStart=true
	}

	@Test
	void discoverListsManagedServices() {
		backend.install(daemon(), false);
		assertEquals(1, backend.discover(Installation.SYSTEM_WIDE).services().size());
	}

	@Test
	void readUnknownReturnsNull() {
		assertNull(backend.read("com.nope", Installation.SYSTEM_WIDE));
		assertNull(backend.status("com.nope", Installation.SYSTEM_WIDE));
	}

	@Test
	void readReconstructsInstalledDaemonFromSidecar() {
		backend.install(daemon().toBuilder().asUser("svc-acme").build(), false);
		final ServiceSpec back = backend.read(ID, Installation.SYSTEM_WIDE);
		assertNotNull(back);
		assertEquals(ID, back.id());
		assertEquals(List.of("C:\\app\\api.exe", "--port", "8080"), back.command());
		// The sidecar records the spec's run-as (the bare name), not the SCM-qualified account.
		assertEquals(RunAs.namedUser("svc-acme"), back.runAs());
		assertTrue(back.autoStart());
	}

	@Test
	void readNativeReturnsSidecarJson() {
		backend.install(daemon(), false);
		final String raw = backend.readNative(ID, Installation.SYSTEM_WIDE);
		assertNotNull(raw);
		assertTrue(raw.contains(ID));
		assertTrue(raw.contains(SidecarReader.MANAGED_VALUE));
		assertNull(backend.readNative("com.nope", Installation.SYSTEM_WIDE));
	}

	@Test
	void upsertReconcilesAccountStartTypeAndDisplayNameInPlace() {
		backend.install(daemon(), false);   // fresh create (LocalSystem)
		// Re-install the same id as a different account + display name.
		final ServiceSpec changed = daemon().toBuilder()
				.displayName("Renamed").asUser("svc-acme").build();
		backend.install(changed, false);

		assertTrue(scm.calls.contains("updateConfig " + ID), "upsert reconciles in place");
		assertFalse(scm.calls.contains("delete " + ID), "no delete+recreate race");
		assertEquals(".\\svc-acme", scm.lastAccount);   // qualified to the local machine
		assertEquals("Renamed", scm.lastDisplayName);
		assertEquals(ServiceStartType.AUTO, scm.lastStartType);   // autoStart=true
	}

	@Test
	void qualifiesBareNamedUserAccountToLocalMachine() {
		backend.install(daemon().toBuilder().asUser("svc-acme").build(), false);
		assertEquals(".\\svc-acme", scm.lastAccount);
	}

	@Test
	void leavesDomainQualifiedAccountAsGiven() {
		backend.install(daemon().toBuilder().asUser("CORP\\svc").build(), false);
		assertEquals("CORP\\svc", scm.lastAccount);
	}

	@Test
	void windowsOptionsAccountOverridesDerivedAccount() {
		final ServiceSpec spec = daemon().toBuilder()
				.windows(WindowsOptions.builder().account("NT AUTHORITY\\LocalService").build())
				.build();
		backend.install(spec, false);
		assertEquals("NT AUTHORITY\\LocalService", scm.lastAccount);
	}

	@Test
	void delayedAutoStartTypeIsRouted() {
		final ServiceSpec spec = daemon().toBuilder()
				.windows(WindowsOptions.builder()
						.startType(WindowsOptions.StartType.DELAYED_AUTO).build())
				.build();
		backend.install(spec, false);
		assertEquals(ServiceStartType.AUTO_DELAYED, scm.lastStartType);
		assertTrue(scm.lastStartType.delayed());
	}

	@Test
	void buildBinPathQuotesAndTargetsTheHost() {
		final String binPath = WindowsBackend.buildBinPath(JAVAW, JAR, ID);
		assertEquals("\"" + JAVAW + "\" --enable-native-access=ALL-UNNAMED -cp \"" + JAR + "\" "
				+ ServiceHost.class.getName() + " --id " + ID, binPath);
	}
}
