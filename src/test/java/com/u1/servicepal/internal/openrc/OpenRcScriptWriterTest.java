package com.u1.servicepal.internal.openrc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.u1.servicepal.Installation;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.options.OpenRcOptions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenRcScriptWriterTest {

	private final OpenRcScriptWriter writer = new OpenRcScriptWriter();
	private final OpenRcScriptReader reader = new OpenRcScriptReader();

	@Test
	void rendersSuperviseDaemonForAlways() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.api")
				.displayName("Acme API")
				.command("/usr/local/bin/api", "--port", "8080")
				.asUser("www-data")
				.restart(RestartPolicy.ALWAYS)
				.env("LOG", "info")
				// A separator-free filename so Path.toString() is identical on every OS (an
				// absolute path would flip to backslashes on a Windows CI runner, which quote()
				// then escapes — a test artifact; a real OpenRC host always uses forward slashes).
				.stdout(Path.of("api.log"))
				.build();

		final String script = writer.render(spec, "default", "/run/com.example.api.pid");

		assertTrue(script.startsWith("#!/sbin/openrc-run\n"), "has the openrc-run shebang");
		assertTrue(script.contains("# X-ServicePal-Managed: 1"));
		assertTrue(script.contains("# X-ServicePal-Runlevel: default"));
		assertTrue(script.contains("description=\"Acme API\""));
		assertTrue(script.contains("command=\"/usr/local/bin/api\""));
		assertTrue(script.contains("command_args=\"--port 8080\""));
		assertTrue(script.contains("command_user=\"www-data\""));
		assertTrue(script.contains("supervisor=supervise-daemon"));
		assertTrue(script.contains("respawn_max=0"));
		assertTrue(script.contains("export LOG=\"info\""));
		assertTrue(script.contains("output_log=\"api.log\""));
		assertTrue(script.contains("pidfile=\"/run/com.example.api.pid\""));
		// supervise-daemon manages foregrounding itself — no command_background.
		assertFalse(script.contains("command_background"));
	}

	@Test
	void rendersStartStopDaemonForNever() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.oneoff")
				.command("/bin/run-once")
				.asSystemDaemon()
				.restart(RestartPolicy.NEVER)
				.build();

		final String script = writer.render(spec, "default", "/run/com.example.oneoff.pid");

		assertTrue(script.contains("command_background=true"));
		assertFalse(script.contains("supervisor="));
		assertFalse(script.contains("respawn_max"));
	}

	@Test
	void omitsDependWhenNoDependencies() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.bare").command("/bin/true").asSystemDaemon().build();
		final String script = writer.render(spec, "default", "/run/com.example.bare.pid");
		// An empty `depend() {}` is a shell syntax error — it must be omitted entirely.
		assertFalse(script.contains("depend()"));
	}

	@Test
	void emitsDependWhenDependenciesGiven() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.net").command("/bin/true").asSystemDaemon()
				.openrc(OpenRcOptions.builder().need("net").build()).build();
		final String script = writer.render(spec, "default", "/run/com.example.net.pid");
		assertTrue(script.contains("depend() {"));
		assertTrue(script.contains("\tneed net"));
	}

	@Test
	void roundTripsThroughReader() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.worker")
				.command("/usr/local/bin/worker", "--queue", "jobs")
				.asUser("worker")
				.restart(RestartPolicy.ON_FAILURE)
				.workingDirectory(Path.of("/srv/worker"))
				.build();

		final Map<String, String> parsed =
				reader.parse(writer.render(spec, "default", "/run/com.example.worker.pid"));
		assertTrue(reader.isManaged(parsed));
		assertEquals("default", reader.runlevel(parsed));

		final ServiceSpec back =
				reader.toSpec(parsed, Installation.SYSTEM_WIDE, "com.example.worker", false);
		assertEquals(List.of("/usr/local/bin/worker", "--queue", "jobs"), back.command());
		assertEquals(RestartPolicy.ON_FAILURE, back.restart());
		assertEquals(RunAs.namedUser("worker"), back.runAs());
		assertEquals(Path.of("/srv/worker"), back.workingDirectory());
	}

	@Test
	void rendersErrorLogForStderr() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.api")
				.command("/usr/local/bin/api")
				.asSystemDaemon()
				// Separator-free filename so Path.toString() is identical on every CI OS (see the
				// note in rendersSuperviseDaemonForAlways).
				.stderr(Path.of("api.err"))
				.build();

		final String script = writer.render(spec, "default", "/run/com.example.api.pid");
		assertTrue(script.contains("error_log=\"api.err\""));
	}

	@Test
	void openRcSupervisorOptionOverridesTheRestartPolicyDefault() {
		// RestartPolicy.NEVER would normally pick start-stop-daemon; an explicit supervisor option
		// forces supervise-daemon anyway. Also confirms the chosen runlevel reaches the marker.
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.api")
				.command("/usr/local/bin/api")
				.asSystemDaemon()
				.restart(RestartPolicy.NEVER)
				.openrc(OpenRcOptions.builder()
						.supervisor(OpenRcOptions.Supervisor.SUPERVISE_DAEMON).build())
				.build();

		final String script = writer.render(spec, "boot", "/run/com.example.api.pid");
		assertTrue(script.contains("supervisor=supervise-daemon"));
		assertFalse(script.contains("command_background"));
		assertTrue(script.contains("# X-ServicePal-Runlevel: boot"));
	}
}
