package com.u1.servicepal.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.AmbiguousServiceException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.ServiceNotFoundException;
import com.u1.servicepal.WrongPlatformOptionsException;
import com.u1.servicepal.internal.macos.LaunchdDir;
import com.u1.servicepal.internal.macos.LaunchdDomain;
import com.u1.servicepal.internal.macos.LaunchdBackend;
import com.u1.servicepal.internal.macos.RecordingLaunchctl;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import com.u1.servicepal.model.options.SystemdOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultServiceManagerTest {

	private static String plist(final String label) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
				<plist version="1.0">
				<dict>
					<key>Label</key><string>%s</string>
					<key>ProgramArguments</key><array><string>/bin/true</string></array>
				</dict>
				</plist>
				""".formatted(label);
	}

	private Path userDir;
	private Path sysDir;
	private RecordingLaunchctl launchctl;

	@BeforeEach
	void setUp(@TempDir final Path tmp) throws IOException {
		userDir = Files.createDirectory(tmp.resolve("user"));
		sysDir = Files.createDirectory(tmp.resolve("system"));
	}

	private ServiceManager manager() {
		launchctl = new RecordingLaunchctl();
		final List<LaunchdDir> dirs = List.of(
				new LaunchdDir(userDir, Installation.PER_USER, LaunchdDomain.GUI),
				new LaunchdDir(sysDir, Installation.SYSTEM_WIDE, LaunchdDomain.SYSTEM));
		return new DefaultServiceManager(new LaunchdBackend(launchctl, dirs));
	}

	@Test
	void resolvesUniqueIdToItsInstallation() throws IOException {
		Files.writeString(userDir.resolve("com.only.user.plist"), plist("com.only.user"));
		final ServiceManager mgr = manager();

		final ServiceStatus status = mgr.status("com.only.user");
		assertTrue(status.installed());
		assertEquals(Installation.PER_USER, status.installation());
		assertTrue(mgr.isInstalled("com.only.user"));
	}

	@Test
	void absentIdYieldsAbsentStatusAndNullRead() {
		final ServiceManager mgr = manager();

		assertFalse(mgr.status("com.nope").installed());
		assertNull(mgr.read("com.nope"));
		assertFalse(mgr.isInstalled("com.nope"));
	}

	@Test
	void ambiguousIdInBothInstallationsThrows() throws IOException {
		Files.writeString(userDir.resolve("com.dup.svc.plist"), plist("com.dup.svc"));
		Files.writeString(sysDir.resolve("com.dup.svc.plist"), plist("com.dup.svc"));
		final ServiceManager mgr = manager();

		assertThrows(AmbiguousServiceException.class, () -> mgr.status("com.dup.svc"));
	}

	@Test
	void discoverMergesInstallationsAndManagedFiltering() throws IOException {
		Files.writeString(userDir.resolve("com.only.user.plist"), plist("com.only.user"));
		final ServiceManager mgr = manager();

		assertEquals(1, mgr.list().size());
		assertEquals(1, mgr.discover().services().size());
		assertTrue(mgr.discover().unreadable().isEmpty());
		assertTrue(mgr.listManaged().isEmpty(), "no managed marker in these plists");
	}

	@Test
	void installThenResolveAndStart() {
		final ServiceManager mgr = manager();
		mgr.install(ServiceSpec.builder()
				.id("com.u1.servicepal.test.svc")
				.command("/bin/sleep", "60")
				.asCurrentUser()
				.build());

		assertTrue(Files.isRegularFile(userDir.resolve("com.u1.servicepal.test.svc.plist")));
		mgr.start("com.u1.servicepal.test.svc");   // by-id auto-resolves to PER_USER
		assertTrue(launchctl.calls.contains("kickstart GUI com.u1.servicepal.test.svc"));
	}

	@Test
	void installEnableStartDoesAllThree() {
		final ServiceManager mgr = manager();
		mgr.installEnableStart(ServiceSpec.builder()
				.id("com.u1.servicepal.test.svc")
				.command("/bin/sleep", "60")
				.asCurrentUser()
				.build());

		final Path file = userDir.resolve("com.u1.servicepal.test.svc.plist");
		assertTrue(launchctl.calls.contains("bootstrap GUI " + file));
		assertTrue(launchctl.calls.contains("enable GUI com.u1.servicepal.test.svc"));
		assertTrue(launchctl.calls.contains("kickstart GUI com.u1.servicepal.test.svc"));
	}

	@Test
	void foreignPlatformOptionsRejected() {
		final ServiceManager mgr = manager();   // macOS backend
		final ServiceSpec spec = ServiceSpec.builder()
				.command("/bin/true")
				.systemd(SystemdOptions.builder().build())
				.build();
		assertThrows(WrongPlatformOptionsException.class, () -> mgr.install(spec));
	}

	@Test
	void mutatingUnknownIdThrowsNotFound() {
		final ServiceManager mgr = manager();
		assertThrows(ServiceNotFoundException.class, () -> mgr.start("com.nope"));
		assertThrows(ServiceNotFoundException.class, () -> mgr.uninstall("com.nope"));
	}
}
