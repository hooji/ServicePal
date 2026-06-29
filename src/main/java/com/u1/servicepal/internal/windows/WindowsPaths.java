package com.u1.servicepal.internal.windows;

import java.nio.file.Path;

/** Shared Windows locations. The sidecar directory is used by both the backend and the host. */
final class WindowsPaths {

	private WindowsPaths() {
	}

	/** {@code %ProgramData%\ServicePal} — where system-wide sidecar JSON + host logs live. */
	static Path sidecarDir() {
		return Path.of(System.getenv().getOrDefault("ProgramData", "C:\\ProgramData"))
				.resolve("ServicePal");
	}

	/**
	 * {@code %LOCALAPPDATA%\ServicePal} — where per-user sidecar JSON lives (no admin needed). A
	 * per-user job is a current-user Task Scheduler task, so there is no service host reading these.
	 */
	static Path perUserSidecarDir() {
		final String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData != null && !localAppData.isBlank()) {
			return Path.of(localAppData).resolve("ServicePal");
		}
		return Path.of(System.getProperty("user.home", "C:\\Users\\Default"))
				.resolve("AppData").resolve("Local").resolve("ServicePal");
	}
}
