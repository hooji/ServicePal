package com.u1.servicepal.gui;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Platform;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;

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

		return mgr;
	}

	private static ServiceSpec job(final Capabilities caps, final String name, final String id,
			final String command, final String arguments, final String folder,
			final boolean autoStart, final RestartPolicy restart) {
		return JobSpecs.fromForm(
				new JobForm(id, name, command, arguments, folder, autoStart, restart), caps);
	}

	/** Capabilities mirroring each real backend closely enough for the badge and privilege model. */
	private static Capabilities capabilitiesFor(final Platform platform) {
		return switch (platform) {
			case MACOS_LAUNCHD ->
					new Capabilities(true, true, true, true, true, true, true, true, false);
			case LINUX_SYSTEMD ->
					new Capabilities(true, true, true, false, false, true, true, true, true);
			case LINUX_OPENRC ->
					new Capabilities(false, true, true, false, false, true, false, true, false);
			case WINDOWS ->
					new Capabilities(false, true, true, true, true, true, false, true, true);
		};
	}
}
