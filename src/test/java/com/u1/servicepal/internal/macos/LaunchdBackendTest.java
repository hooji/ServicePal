package com.u1.servicepal.internal.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LaunchdBackendTest {

	private static final String MANAGED_FOO = """
			<?xml version="1.0" encoding="UTF-8"?>
			<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
			<plist version="1.0">
			<dict>
				<key>Label</key><string>com.example.foo</string>
				<key>ProgramArguments</key>
				<array><string>/usr/local/bin/foo</string><string>--daily</string></array>
				<key>RunAtLoad</key><true/>
				<key>KeepAlive</key><true/>
				<key>StandardOutPath</key><string>/tmp/foo.log</string>
				<key>com.u1.servicepal.Managed</key><string>0.1.0</string>
			</dict>
			</plist>
			""";

	private static final String UNMANAGED_BACKUP = """
			<?xml version="1.0" encoding="UTF-8"?>
			<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
			<plist version="1.0">
			<dict>
				<key>Label</key><string>com.acme.backup</string>
				<key>ProgramArguments</key><array><string>/usr/local/bin/backup</string></array>
				<key>StartCalendarInterval</key>
				<dict><key>Hour</key><integer>3</integer><key>Minute</key><integer>0</integer></dict>
			</dict>
			</plist>
			""";

	private Path userDir;
	private Path sysDir;
	private RecordingLaunchctl launchctl;
	private LaunchdBackend backend;

	@BeforeEach
	void setUp(@TempDir final Path tmp) throws IOException {
		userDir = Files.createDirectory(tmp.resolve("LaunchAgents"));
		sysDir = Files.createDirectory(tmp.resolve("LaunchDaemons"));
		Files.writeString(userDir.resolve("com.example.foo.plist"), MANAGED_FOO);
		Files.writeString(sysDir.resolve("com.acme.backup.plist"), UNMANAGED_BACKUP);
		Files.writeString(sysDir.resolve("com.broken.bad.plist"), "this is not a plist");

		// foo is running; everything else reports stopped.
		launchctl = new RecordingLaunchctl((domain, label) -> "com.example.foo".equals(label)
				? new ServiceRuntime(RunState.RUNNING, 4242, 0)
				: new ServiceRuntime(RunState.STOPPED, null, null));
		final List<LaunchdDir> dirs = List.of(
				new LaunchdDir(userDir, Installation.PER_USER, LaunchdDomain.GUI),
				new LaunchdDir(sysDir, Installation.SYSTEM_WIDE, LaunchdDomain.SYSTEM));
		backend = new LaunchdBackend(launchctl, dirs);
	}

	private static ServiceSpec sleepSpec() {
		return ServiceSpec.builder()
				.id("com.u1.servicepal.test.sleeper")
				.command("/bin/sleep", "60")
				.asCurrentUser()
				.autoStart(true)
				.build();
	}

	@Test
	void installWritesManagedPlistAndBootstraps() throws IOException {
		backend.install(sleepSpec(), false);

		final Path file = userDir.resolve("com.u1.servicepal.test.sleeper.plist");
		assertTrue(Files.isRegularFile(file));
		assertTrue(Files.readString(file).contains("com.u1.servicepal.Managed"));
		assertTrue(launchctl.calls.contains("bootstrap GUI " + file));

		boolean found = false;
		for (final ServiceStatus s : backend.discover(Installation.PER_USER).services()) {
			if (s.id().equals("com.u1.servicepal.test.sleeper") && s.managed()) {
				found = true;
			}
		}
		assertTrue(found, "installed service should be discoverable and managed");
	}

	@Test
	void uninstallBootsOutAndDeletes() {
		backend.install(sleepSpec(), false);
		backend.uninstall("com.u1.servicepal.test.sleeper", Installation.PER_USER, false);

		final Path file = userDir.resolve("com.u1.servicepal.test.sleeper.plist");
		assertFalse(Files.exists(file));
		assertTrue(launchctl.calls.contains("bootout GUI com.u1.servicepal.test.sleeper"));
	}

	@Test
	void installRefusesToOverwriteUnmanaged() throws IOException {
		final Path file = userDir.resolve("com.u1.servicepal.test.sleeper.plist");
		Files.writeString(file, UNMANAGED_BACKUP); // an unmanaged plist already at the target

		assertThrows(com.u1.servicepal.UnmanagedServiceException.class,
				() -> backend.install(sleepSpec(), false));
		// ...unless explicitly allowed
		backend.install(sleepSpec(), true);
		assertTrue(Files.readString(file).contains("com.u1.servicepal.Managed"));
	}

	@Test
	void upsertRoundTripsDisplayNameChangesWithNoStaleKey() {
		final String id = "com.u1.servicepal.test.named";

		// 1. Install with no explicit display name → it defaults to the id (the generated-name case).
		backend.install(ServiceSpec.builder().id(id).command("/bin/sleep", "60")
				.asCurrentUser().build(), false);
		assertEquals(id, backend.read(id, Installation.PER_USER).displayName(), "defaults to id");

		// 2. Edit: assign a real name. The upsert rewrites the plist, so the name now round-trips.
		backend.install(ServiceSpec.builder().id(id).displayName("My Backup")
				.command("/bin/sleep", "60").asCurrentUser().build(), false);
		assertEquals("My Backup", backend.read(id, Installation.PER_USER).displayName());

		// 3. Edit again: change the name. The new value replaces the old.
		backend.install(ServiceSpec.builder().id(id).displayName("Nightly Backup")
				.command("/bin/sleep", "60").asCurrentUser().build(), false);
		assertEquals("Nightly Backup", backend.read(id, Installation.PER_USER).displayName());

		// 4. Edit again: clear the name (it falls back to the id). Because install rewrites the whole
		//    plist (it does not merge), the side-band key is dropped — not left behind as stale.
		backend.install(ServiceSpec.builder().id(id).command("/bin/sleep", "60")
				.asCurrentUser().build(), false);
		assertEquals(id, backend.read(id, Installation.PER_USER).displayName(), "no stale name");
		assertFalse(backend.readNative(id, Installation.PER_USER).contains(PlistReader.DISPLAY_NAME_KEY),
				"the display-name key is removed when the name is cleared");
	}

	@Test
	void lifecycleVerbsTargetTheRightDomain() {
		backend.install(sleepSpec(), false);
		final String id = "com.u1.servicepal.test.sleeper";
		backend.start(id, Installation.PER_USER);
		backend.restart(id, Installation.PER_USER);
		backend.stop(id, Installation.PER_USER);
		backend.enable(id, Installation.PER_USER);
		backend.disable(id, Installation.PER_USER);

		assertTrue(launchctl.calls.contains("kickstart GUI " + id));
		assertTrue(launchctl.calls.contains("kickstart -k GUI " + id));
		assertTrue(launchctl.calls.contains("kill SIGTERM GUI " + id));
		assertTrue(launchctl.calls.contains("enable GUI " + id));
		assertTrue(launchctl.calls.contains("disable GUI " + id));
	}

	@Test
	void discoversPerUserWithLiveState() {
		final Discovery d = backend.discover(Installation.PER_USER);
		assertEquals(1, d.services().size());
		assertTrue(d.unreadable().isEmpty());

		final ServiceStatus foo = d.services().get(0);
		assertEquals("com.example.foo", foo.id());
		assertEquals(Installation.PER_USER, foo.installation());
		assertTrue(foo.managed(), "marker key present");
		assertTrue(foo.enabled(), "RunAtLoad maps to enabled");
		assertEquals(RunState.RUNNING, foo.state());
		assertEquals(Integer.valueOf(4242), foo.pid());
	}

	@Test
	void reportsUnreadableDefinitionsByName() {
		final Discovery d = backend.discover(Installation.SYSTEM_WIDE);

		assertEquals(1, d.services().size(), "only the readable backup is a service");
		assertEquals("com.acme.backup", d.services().get(0).id());
		assertEquals(1, d.unreadable().size());
		assertTrue(d.unreadable().get(0).endsWith("com.broken.bad.plist"));
	}

	@Test
	void systemServiceUnmanagedStopped() {
		final ServiceStatus backup = backend.discover(Installation.SYSTEM_WIDE).services().get(0);
		assertFalse(backup.managed());
		assertFalse(backup.enabled());
		assertEquals(RunState.STOPPED, backup.state());
		assertNull(backup.pid());
	}

	@Test
	void readsManagedSpec() {
		final ServiceSpec foo = backend.read("com.example.foo", Installation.PER_USER);
		assertEquals(List.of("/usr/local/bin/foo", "--daily"), foo.command());
		assertEquals(RestartPolicy.ALWAYS, foo.restart());
		assertTrue(foo.autoStart());
		assertEquals(Path.of("/tmp/foo.log"), foo.stdout());
		assertEquals(RunAs.currentUser(), foo.runAs());
	}

	@Test
	void readsScheduleAndSystemIdentity() {
		final ServiceSpec backup = backend.read("com.acme.backup", Installation.SYSTEM_WIDE);
		assertEquals(RunAs.systemDaemon(), backup.runAs());
		final CalendarSchedule schedule = assertInstanceOf(CalendarSchedule.class, backup.schedule());
		assertEquals(Integer.valueOf(3), schedule.spec().hour());
		assertEquals(Integer.valueOf(0), schedule.spec().minute());
	}

	@Test
	void readNativeReturnsVerbatim() {
		final String raw = backend.readNative("com.example.foo", Installation.PER_USER);
		assertTrue(raw.contains("com.example.foo"));
		assertTrue(raw.contains("com.u1.servicepal.Managed"));
	}

	@Test
	void readUnknownReturnsNull() {
		assertNull(backend.read("com.nope", Installation.PER_USER));
		assertNull(backend.status("com.nope", Installation.PER_USER));
	}
}
