package com.u1.servicepal.internal.systemd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.options.SystemdOptions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnitWriterTest {

	private final UnitWriter writer = new UnitWriter();
	private final UnitReader reader = new UnitReader();

	@Test
	void rendersExpectedKeys() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.api")
				.displayName("Acme API")
				.command("/usr/local/bin/api", "--port", "8080")
				.asSystemDaemon()
				.restart(RestartPolicy.ALWAYS)
				.env("LOG", "info")
				.systemd(SystemdOptions.builder()
						.type(SystemdOptions.Type.NOTIFY)
						.after("network-online.target")
						.build())
				.build();

		final String unit = writer.render(spec, false);

		assertTrue(unit.contains("Description=Acme API"));
		assertTrue(unit.contains("X-ServicePal-Managed=1"));
		assertTrue(unit.contains("Type=notify"));
		assertTrue(unit.contains("ExecStart=/usr/local/bin/api --port 8080"));
		assertTrue(unit.contains("Restart=always"));
		assertTrue(unit.contains("StartLimitIntervalSec=0"));
		assertTrue(unit.contains("After=network-online.target"));
		assertTrue(unit.contains("Environment=\"LOG=info\""));
		assertTrue(unit.contains("WantedBy=multi-user.target"));
	}

	@Test
	void roundTripsThroughReader() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.worker")
				.command("/usr/local/bin/worker")
				.asUser("www-data")
				.restart(RestartPolicy.ON_FAILURE)
				.workingDirectory(Path.of("/srv/worker"))
				.build();

		final Map<String, String> unit = reader.parse(writer.render(spec, false));
		assertTrue(reader.isManaged(unit));

		final ServiceSpec back = reader.toSpec(unit, Installation.SYSTEM_WIDE, "com.example.worker");
		assertEquals(List.of("/usr/local/bin/worker"), back.command());
		assertEquals(RestartPolicy.ON_FAILURE, back.restart());
		assertEquals(RunAs.namedUser("www-data"), back.runAs());
		assertEquals(Path.of("/srv/worker"), back.workingDirectory());
	}

	@Test
	void userInstallWantsDefaultTarget() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.agent")
				.command("/usr/local/bin/agent")
				.asCurrentUser()
				.build();
		assertTrue(writer.render(spec, true).contains("WantedBy=default.target"));
	}
}
