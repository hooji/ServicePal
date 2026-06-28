package com.u1.servicepal.internal.openrc;

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
import com.u1.servicepal.model.options.OpenRcOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenRcBackendTest {

	private static final String ID = "com.u1.servicepal.test.svc";

	private Path initdDir;
	private Path runlevelsDir;
	private Path runDir;
	private RecordingRcService rc;
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
		backend = new OpenRcBackend(rc, initdDir, runlevelsDir, runDir);
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

	@Test
	void perUserDiscoveryIsEmpty() {
		// OpenRC has no per-user installation; discovering it yields nothing rather than throwing.
		assertTrue(backend.discover(Installation.PER_USER).services().isEmpty());
	}
}
