package com.u1.servicepal.internal;

import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Runtime detection of the host's service subsystem. */
public final class Platforms {

	private Platforms() {
	}

	public static Platform detect() {
		final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("mac") || os.contains("darwin")) {
			return Platform.MACOS_LAUNCHD;
		}
		if (os.contains("win")) {
			return Platform.WINDOWS;
		}
		// Treat everything else as Linux/Unix and detect the init system.
		if (Files.isDirectory(Path.of("/run/systemd/system"))) {
			return Platform.LINUX_SYSTEMD;
		}
		if (Files.isRegularFile(Path.of("/sbin/openrc"))
				|| Files.isRegularFile(Path.of("/usr/sbin/openrc"))
				|| onPath("rc-service")) {
			return Platform.LINUX_OPENRC;
		}
		throw new ServiceException("No supported init system detected. PID 1 is not systemd, and"
				+ " OpenRC was not found. (Init-less container?) Supported: macOS launchd, Linux"
				+ " systemd, Linux OpenRC, Windows.");
	}

	private static boolean onPath(final String executable) {
		final String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (final String dir : path.split(File.pathSeparator)) {
			if (dir.isBlank()) {
				continue;
			}
			if (Files.isRegularFile(Path.of(dir, executable))) {
				return true;
			}
		}
		return false;
	}
}
