package com.u1.servicepal.internal.systemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
	void readUnknownReturnsNull() {
		assertNull(backend.read("com.nope", Installation.SYSTEM_WIDE));
		assertNull(backend.status("com.nope", Installation.SYSTEM_WIDE));
	}
}
