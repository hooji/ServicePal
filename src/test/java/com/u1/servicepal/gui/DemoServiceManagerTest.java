package com.u1.servicepal.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Platform;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import org.junit.jupiter.api.Test;

class DemoServiceManagerTest {

	private static final Capabilities CAPS =
			new Capabilities(true, true, true, true, true, true, true, true, false);

	private DemoServiceManager newManager() {
		return new DemoServiceManager(Platform.MACOS_LAUNCHD, CAPS);
	}

	private static ServiceSpec spec(final boolean autoStart) {
		return ServiceSpec.builder()
				.id("com.example.demo")
				.command("/bin/sleep", "60")
				.autoStart(autoStart)
				.restart(RestartPolicy.ALWAYS)
				.build();
	}

	@Test
	void installThenStartThenStop() {
		final DemoServiceManager manager = newManager();
		manager.install(spec(false));

		assertTrue(manager.isInstalled("com.example.demo"));
		assertEquals(RunState.STOPPED, manager.status("com.example.demo").state());

		manager.start("com.example.demo");
		final ServiceStatus running = manager.status("com.example.demo");
		assertEquals(RunState.RUNNING, running.state());
		assertNotNull(running.pid());

		manager.stop("com.example.demo");
		final ServiceStatus stopped = manager.status("com.example.demo");
		assertEquals(RunState.STOPPED, stopped.state());
		assertNull(stopped.pid());
	}

	@Test
	void installEnableStartReachesRunning() {
		final DemoServiceManager manager = newManager();
		manager.installEnableStart(spec(true));

		final ServiceStatus status = manager.status("com.example.demo");
		assertEquals(RunState.RUNNING, status.state());
		assertTrue(status.enabled());
		assertTrue(status.managed());
	}

	@Test
	void enableAndDisableToggleBootPersistence() {
		final DemoServiceManager manager = newManager();
		manager.install(spec(false));
		assertFalse(manager.status("com.example.demo").enabled());

		manager.enable("com.example.demo");
		assertTrue(manager.status("com.example.demo").enabled());

		manager.disable("com.example.demo");
		assertFalse(manager.status("com.example.demo").enabled());
	}

	@Test
	void listManagedReflectsInstalls() {
		final DemoServiceManager manager = newManager();
		assertTrue(manager.listManaged().isEmpty());

		manager.install(spec(false));
		assertEquals(1, manager.listManaged().size());

		manager.uninstall("com.example.demo");
		assertTrue(manager.listManaged().isEmpty());
		assertFalse(manager.isInstalled("com.example.demo"));
	}

	@Test
	void statusOfAbsentIsNotInstalled() {
		final ServiceStatus status = newManager().status("nope");
		assertFalse(status.installed());
		assertEquals(RunState.UNKNOWN, status.state());
	}

	@Test
	void seedPopulatesReadableState() {
		final DemoServiceManager manager = newManager();
		manager.seed(spec(true), RunState.RUNNING, 1234, true, null);

		final ServiceStatus status = manager.status("com.example.demo");
		assertEquals(RunState.RUNNING, status.state());
		assertEquals(1234, status.pid());
		assertNotNull(manager.read("com.example.demo"));
	}

	@Test
	void unmanagedSeedShowsInListButNotListManaged() {
		final DemoServiceManager manager = newManager();
		final ServiceSpec other = ServiceSpec.builder().id("com.other.svc").command("/bin/x").build();
		manager.seed(other, RunState.RUNNING, 77, true, null, false);

		assertEquals(1, manager.list().size(), "list() includes discovered (unmanaged) services");
		assertTrue(manager.listManaged().isEmpty(), "listManaged() excludes unmanaged services");
		assertFalse(manager.isManaged("com.other.svc"));
		assertFalse(manager.status("com.other.svc").managed());
	}

	@Test
	void freshInstallIsManagedButNotAdopted() {
		final DemoServiceManager manager = newManager();
		manager.install(spec(true));
		assertTrue(manager.status("com.example.demo").managed());
		assertFalse(manager.status("com.example.demo").adopted());
	}

	@Test
	void installingOverAForeignServiceAdoptsIt() {
		final DemoServiceManager manager = newManager();
		manager.seed(ServiceSpec.builder().id("com.other.svc").command("/bin/x").build(),
				RunState.RUNNING, 5, true, null, false);
		assertFalse(manager.status("com.other.svc").managed());

		manager.install(ServiceSpec.builder().id("com.other.svc").command("/bin/y").build(), true);
		assertTrue(manager.status("com.other.svc").managed());
		assertTrue(manager.status("com.other.svc").adopted(), "we manage it but did not create it");
	}
}
