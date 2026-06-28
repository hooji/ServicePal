package com.u1.servicepal.internal.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dd.plist.NSDictionary;
import com.u1.servicepal.Installation;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.options.MacOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlistWriterTest {

	private final PlistWriter writer = new PlistWriter();
	private final PlistReader reader = new PlistReader();

	@Test
	void roundTripsCoreFieldsThroughTheReader(@TempDir final Path tmp) throws IOException {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.svc")
				.command("/usr/local/bin/app", "--flag")
				.asCurrentUser()
				.autoStart(true)
				.restart(RestartPolicy.ON_FAILURE)
				.env("KEY", "VAL")
				.workingDirectory(Path.of("/var/tmp"))
				.stdout(Path.of("/tmp/o.log"))
				.build();

		final Path file = tmp.resolve("com.example.svc.plist");
		Files.writeString(file, writer.render(spec));

		final NSDictionary dict = reader.parseFile(file);
		assertTrue(reader.isManaged(dict), "writer stamps the managed marker");
		assertTrue(reader.runAtLoad(dict));

		final ServiceSpec back = reader.toSpec(dict, Installation.PER_USER, "fallback");
		assertEquals("com.example.svc", back.id());
		assertEquals(List.of("/usr/local/bin/app", "--flag"), back.command());
		assertEquals(RestartPolicy.ON_FAILURE, back.restart());
		assertEquals(Path.of("/var/tmp"), back.workingDirectory());
		assertEquals(Path.of("/tmp/o.log"), back.stdout());
		assertEquals("VAL", back.environment().get("KEY"));
	}

	@Test
	void roundTripsCalendarSchedule(@TempDir final Path tmp) throws IOException {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.cron")
				.command("/usr/local/bin/backup")
				.schedule(Schedule.dailyAt(3, 30))
				.build();

		final Path file = tmp.resolve("com.example.cron.plist");
		Files.writeString(file, writer.render(spec));

		final ServiceSpec back = reader.toSpec(reader.parseFile(file), Installation.SYSTEM_WIDE, "x");
		final CalendarSchedule schedule = assertInstanceOf(CalendarSchedule.class, back.schedule());
		assertEquals(Integer.valueOf(3), schedule.spec().hour());
		assertEquals(Integer.valueOf(30), schedule.spec().minute());
	}

	@Test
	void roundTripsDisplayNameViaSideBandKey(@TempDir final Path tmp) throws IOException {
		// launchd has no native friendly name; a user-supplied displayName must survive a read.
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.u1.servicepal.abc123")
				.displayName("My Backup")
				.command("/usr/local/bin/backup")
				.build();

		final String xml = writer.render(spec);
		assertTrue(xml.contains(PlistReader.DISPLAY_NAME_KEY), "writer persists the display name");

		final Path file = tmp.resolve("svc.plist");
		Files.writeString(file, xml);
		final ServiceSpec back = reader.toSpec(reader.parseFile(file), Installation.PER_USER, "x");
		assertEquals("My Backup", back.displayName(), "displayName round-trips");
		assertEquals("com.u1.servicepal.abc123", back.id());
	}

	@Test
	void omitsDisplayNameKeyWhenItEqualsTheId(@TempDir final Path tmp) throws IOException {
		// No displayName set → it defaults to the id; the side-band key is then redundant.
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.svc")
				.command("/bin/true")
				.build();

		final String xml = writer.render(spec);
		assertFalse(xml.contains(PlistReader.DISPLAY_NAME_KEY), "no redundant display-name key");

		final Path file = tmp.resolve("svc.plist");
		Files.writeString(file, xml);
		final ServiceSpec back = reader.toSpec(reader.parseFile(file), Installation.PER_USER, "x");
		assertEquals("com.example.svc", back.displayName());
	}

	@Test
	void mapsAlwaysKeepAliveAndMacOptions() {
		final ServiceSpec spec = ServiceSpec.builder()
				.id("com.example.daemon")
				.command("/bin/true")
				.restart(RestartPolicy.ALWAYS)
				.mac(MacOptions.builder()
						.processType(MacOptions.ProcessType.BACKGROUND)
						.lowPriorityIO(true)
						.build())
				.build();

		final String xml = writer.render(spec);
		assertTrue(xml.contains("<key>KeepAlive</key>"));
		assertTrue(xml.contains("<key>ProcessType</key>"));
		assertTrue(xml.contains("Background"));
		assertTrue(xml.contains("<key>LowPriorityIO</key>"));
	}
}
