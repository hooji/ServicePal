package com.u1.servicepal.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.AmbiguousServiceException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.internal.macos.JobInfo;
import com.u1.servicepal.internal.macos.Launchctl;
import com.u1.servicepal.internal.macos.LaunchdBackend;
import com.u1.servicepal.model.ServiceStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

	@BeforeEach
	void setUp(@TempDir final Path tmp) throws IOException {
		userDir = Files.createDirectory(tmp.resolve("user"));
		sysDir = Files.createDirectory(tmp.resolve("system"));
	}

	private ServiceManager managerWith(final Map<String, JobInfo> jobs) {
		final Launchctl launchctl = () -> jobs;
		final Map<Installation, List<Path>> dirs = Map.of(
				Installation.PER_USER, List.of(userDir),
				Installation.SYSTEM_WIDE, List.of(sysDir));
		return new DefaultServiceManager(new LaunchdBackend(launchctl, dirs));
	}

	@Test
	void resolvesUniqueIdToItsInstallation() throws IOException {
		Files.writeString(userDir.resolve("com.only.user.plist"), plist("com.only.user"));
		final ServiceManager mgr = managerWith(Map.of());

		final ServiceStatus status = mgr.status("com.only.user");
		assertTrue(status.installed());
		assertEquals(Installation.PER_USER, status.installation());
		assertTrue(mgr.isInstalled("com.only.user"));
	}

	@Test
	void absentIdYieldsAbsentStatusAndNullRead() {
		final ServiceManager mgr = managerWith(Map.of());

		assertFalse(mgr.status("com.nope").installed());
		assertNull(mgr.read("com.nope"));
		assertFalse(mgr.isInstalled("com.nope"));
	}

	@Test
	void ambiguousIdInBothInstallationsThrows() throws IOException {
		Files.writeString(userDir.resolve("com.dup.svc.plist"), plist("com.dup.svc"));
		Files.writeString(sysDir.resolve("com.dup.svc.plist"), plist("com.dup.svc"));
		final ServiceManager mgr = managerWith(Map.of());

		assertThrows(AmbiguousServiceException.class, () -> mgr.status("com.dup.svc"));
	}

	@Test
	void listMergesInstallationsAndManagedFiltering() throws IOException {
		Files.writeString(userDir.resolve("com.only.user.plist"), plist("com.only.user"));
		final ServiceManager mgr = managerWith(Map.of());

		assertEquals(1, mgr.list().size());
		assertTrue(mgr.listManaged().isEmpty(), "no managed marker in these plists");
	}

	@Test
	void mutationNotYetImplemented() {
		final ServiceManager mgr = managerWith(Map.of());
		assertThrows(UnsupportedOperationException.class, () -> mgr.start("com.whatever"));
	}
}
