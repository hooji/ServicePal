package com.u1.servicepal.internal.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.RunState;
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
	private LaunchdBackend backend;

	@BeforeEach
	void setUp(@TempDir final Path tmp) throws IOException {
		userDir = Files.createDirectory(tmp.resolve("LaunchAgents"));
		sysDir = Files.createDirectory(tmp.resolve("LaunchDaemons"));
		Files.writeString(userDir.resolve("com.example.foo.plist"), MANAGED_FOO);
		Files.writeString(sysDir.resolve("com.acme.backup.plist"), UNMANAGED_BACKUP);

		final Launchctl launchctl = () -> Map.of("com.example.foo", new JobInfo(4242, 0));
		final Map<Installation, List<Path>> dirs = Map.of(
				Installation.PER_USER, List.of(userDir),
				Installation.SYSTEM_WIDE, List.of(sysDir));
		backend = new LaunchdBackend(launchctl, dirs);
	}

	@Test
	void listsPerUserWithLiveState() {
		final List<ServiceStatus> services = backend.list(Installation.PER_USER);
		assertEquals(1, services.size());

		final ServiceStatus foo = services.get(0);
		assertEquals("com.example.foo", foo.id());
		assertEquals(Installation.PER_USER, foo.installation());
		assertTrue(foo.installed());
		assertTrue(foo.managed(), "marker key present");
		assertTrue(foo.enabled(), "RunAtLoad maps to enabled");
		assertEquals(RunState.RUNNING, foo.state());
		assertEquals(Integer.valueOf(4242), foo.pid());
	}

	@Test
	void listsSystemWideUnmanagedStopped() {
		final List<ServiceStatus> services = backend.list(Installation.SYSTEM_WIDE);
		assertEquals(1, services.size());

		final ServiceStatus backup = services.get(0);
		assertEquals("com.acme.backup", backup.id());
		assertFalse(backup.managed());
		assertFalse(backup.enabled());
		assertEquals(RunState.STOPPED, backup.state(), "not in launchctl list");
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
