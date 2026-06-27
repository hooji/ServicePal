package com.u1.servicepal.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import org.junit.jupiter.api.Test;

class ServiceSpecTest {

	@Test
	void appliesDefaults() {
		final ServiceSpec spec = ServiceSpec.builder().command("/bin/true").build();

		assertTrue(spec.id().startsWith("com.u1.servicepal."), "id should be generated");
		assertEquals(spec.id(), spec.displayName(), "displayName defaults to id");
		assertEquals(RunAs.currentUser(), spec.runAs(), "default identity is current user");
		assertEquals(RestartPolicy.NEVER, spec.restart());
		assertTrue(spec.environment().isEmpty());
		assertNull(spec.schedule());
		assertNull(spec.workingDirectory());
	}

	@Test
	void keepsExplicitIdAndDisplayName() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.svc")
				.displayName("My Service")
				.command("/bin/true")
				.build();

		assertEquals("com.example.svc", spec.id());
		assertEquals("My Service", spec.displayName());
	}

	@Test
	void requiresCommand() {
		assertThrows(IllegalArgumentException.class, () -> ServiceSpec.builder().build());
	}

	@Test
	void rejectsScheduledAlwaysRestart() {
		assertThrows(IllegalArgumentException.class, () -> ServiceSpec.builder()
				.command("/bin/true")
				.schedule(Schedule.dailyAt(3, 0))
				.restart(RestartPolicy.ALWAYS)
				.build());
	}

	@Test
	void runAsDerivesInstallation() {
		assertEquals(Installation.PER_USER, RunAs.currentUser().installation());
		assertEquals(Installation.SYSTEM_WIDE, RunAs.namedUser("www-data").installation());
		assertEquals(Installation.SYSTEM_WIDE, RunAs.systemDaemon().installation());
		assertThrows(IllegalArgumentException.class, () -> RunAs.namedUser("  "));
	}

	@Test
	void toBuilderRoundTrips() {
		final ServiceSpec original = ServiceSpec.builder()
				.id("com.example.svc")
				.command("/bin/old")
				.asSystemDaemon()
				.build();

		final ServiceSpec modified = original.toBuilder().command("/bin/new", "--flag").build();

		assertEquals("com.example.svc", modified.id());
		assertEquals(java.util.List.of("/bin/new", "--flag"), modified.command());
		assertSame(original.runAs().kind(), modified.runAs().kind());
	}
}
