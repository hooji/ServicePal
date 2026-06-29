package com.u1.servicepal.gui;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Platform;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.Schedule;
import com.u1.servicepal.model.ServiceSpec;
import java.time.Instant;
import java.time.ZonedDateTime;

/**
 * Seeds a {@link DemoServiceManager} with a representative, deterministic set of jobs (two
 * running, one stopped, one failed) so screenshots show a populated UI without touching the real
 * OS. Commands and folders are chosen to look native to the given platform.
 */
public final class DemoData {

	private DemoData() {
	}

	public static DemoServiceManager forPlatform(final Platform platform) {
		final Capabilities caps = capabilitiesFor(platform);
		final DemoServiceManager mgr = new DemoServiceManager(platform, caps);
		final boolean win = platform == Platform.WINDOWS;

		mgr.seed(job(caps, "Nightly Backup", "com.example.backup",
				win ? "C:\\Tools\\backup.exe" : "/usr/local/bin/backup", "--daemon",
				win ? "C:\\Backups" : "/var/backups", true, RestartPolicy.ALWAYS),
				RunState.RUNNING, 4821, true, null);

		mgr.seed(job(caps, "Photo Sync", "com.example.photosync",
				win ? "C:\\Program Files\\PhotoSync\\sync.exe" : "/opt/photosync/sync", "--watch",
				null, true, RestartPolicy.ALWAYS),
				RunState.RUNNING, 5190, true, null);

		mgr.seed(job(caps, "Log Shipper", "com.example.logshipper",
				win ? "C:\\Tools\\logship.exe" : "/usr/bin/logship",
				win ? "--config C:\\ProgramData\\logship.conf" : "--config /etc/logship.conf",
				null, false, RestartPolicy.ON_FAILURE),
				RunState.STOPPED, null, false, 0);

		mgr.seed(job(caps, "Web Scraper", "com.example.scraper",
				win ? "C:\\Tools\\scraper.exe" : "/usr/local/bin/scraper", "--interval 30",
				null, true, RestartPolicy.ON_FAILURE),
				RunState.FAILED, null, true, 1);

		// A scheduled job (created here): runs daily, so it reads "Scheduled" with a next/last run
		// rather than "Running"/"Stopped".
		mgr.seedScheduled(scheduledJob(caps, "Database Snapshot", "com.example.dbsnapshot",
				win ? "C:\\Tools\\snapshot.exe" : "/usr/local/bin/snapshot", "--all",
				win ? "C:\\Snapshots" : "/var/snapshots", Schedule.dailyAt(2, 0)),
				RunState.STOPPED, true, dailyAt(2), dailyAt(2).minusSeconds(86400));

		// One job ServicePal adopted: it installed over a service it did not originally create.
		mgr.seed(job(caps, "Mail Indexer", "com.example.mailindex",
				win ? "C:\\Tools\\mailindex.exe" : "/usr/local/bin/mailindex", "--watch",
				null, true, RestartPolicy.ON_FAILURE),
				RunState.RUNNING, 6321, true, null, true, true);

		seedOthers(mgr, platform);
		return mgr;
	}

	/** The next occurrence of {@code hour}:00 local time (a believable next-run for a daily job). */
	private static Instant dailyAt(final int hour) {
		final ZonedDateTime now = ZonedDateTime.now();
		ZonedDateTime next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0);
		if (!next.isAfter(now)) {
			next = next.plusDays(1);
		}
		return next.toInstant();
	}

	/**
	 * Seed a few services ServicePal did <em>not</em> create, so the demo shows the second
	 * ("Other background jobs") section. Names are chosen to look native to each platform.
	 */
	private static void seedOthers(final DemoServiceManager mgr, final Platform platform) {
		switch (platform) {
			case MACOS_LAUNCHD -> {
				mgr.seed(raw("com.google.keystone.agent",
						"/Library/Google/GoogleSoftwareUpdate/.../GoogleSoftwareUpdateAgent", false),
						RunState.RUNNING, 612, true, null, false);
				mgr.seed(raw("com.docker.helper",
						"/Applications/Docker.app/Contents/MacOS/com.docker.helper", false),
						RunState.RUNNING, 988, true, null, false);
				mgr.seed(raw("homebrew.mxcl.postgresql",
						"/opt/homebrew/opt/postgresql/bin/postgres", true),
						RunState.STOPPED, null, false, null, false);
			}
			case LINUX_SYSTEMD -> {
				mgr.seed(raw("cups.service", "/usr/sbin/cupsd -l", true),
						RunState.RUNNING, 743, true, null, false);
				mgr.seed(raw("ssh.service", "/usr/sbin/sshd -D", true),
						RunState.RUNNING, 911, true, null, false);
				mgr.seed(raw("containerd.service", "/usr/bin/containerd", true),
						RunState.STOPPED, null, false, null, false);
			}
			case LINUX_OPENRC -> {
				mgr.seed(raw("sshd", "/usr/sbin/sshd", true),
						RunState.RUNNING, 514, true, null, false);
				mgr.seed(raw("crond", "/usr/sbin/crond -f", true),
						RunState.RUNNING, 540, true, null, false);
				mgr.seed(raw("nginx", "/usr/sbin/nginx", true),
						RunState.STOPPED, null, false, null, false);
			}
			case WINDOWS -> {
				mgr.seed(raw("Spooler", "C:\\Windows\\System32\\spoolsv.exe", true),
						RunState.RUNNING, 1320, true, null, false);
				mgr.seed(raw("W32Time", "C:\\Windows\\System32\\svchost.exe -k LocalService", true),
						RunState.RUNNING, 1564, true, null, false);
				mgr.seed(raw("WSearch", "C:\\Windows\\System32\\SearchIndexer.exe", true),
						RunState.STOPPED, null, false, null, false);
			}
		}
	}

	/** A minimal spec for a discovered (not ServicePal-created) service; its id is its display name. */
	private static ServiceSpec raw(final String id, final String command, final boolean system) {
		if (system) {
			return ServiceSpec.builder().id(id).command(command).asSystemDaemon().build();
		}
		return ServiceSpec.builder().id(id).command(command).asCurrentUser().build();
	}

	private static ServiceSpec job(final Capabilities caps, final String name, final String id,
			final String command, final String arguments, final String folder,
			final boolean autoStart, final RestartPolicy restart) {
		return JobSpecs.fromForm(
				new JobForm(id, name, command, arguments, folder, autoStart, restart), caps);
	}

	private static ServiceSpec scheduledJob(final Capabilities caps, final String name, final String id,
			final String command, final String arguments, final String folder,
			final Schedule schedule) {
		return JobSpecs.fromForm(
				new JobForm(id, name, command, arguments, folder, false, RestartPolicy.NEVER, schedule),
				caps);
	}

	/**
	 * Capabilities mirroring each real backend closely enough for the badge and privilege model.
	 * All four platforms now support calendar + interval scheduling (launchd, systemd {@code .timer},
	 * the OpenRC cron fallback, Windows Task Scheduler).
	 */
	private static Capabilities capabilitiesFor(final Platform platform) {
		return switch (platform) {
			case MACOS_LAUNCHD ->
					new Capabilities(true, true, true, true, true, true, true, true, false);
			case LINUX_SYSTEMD ->
					new Capabilities(true, true, true, true, true, true, true, true, true);
			case LINUX_OPENRC ->
					new Capabilities(false, true, true, true, true, true, false, true, false);
			case WINDOWS ->
					new Capabilities(false, true, true, true, true, true, false, true, true);
		};
	}
}
