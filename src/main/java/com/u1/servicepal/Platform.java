package com.u1.servicepal;

/**
 * The OS service subsystem this library targets, detected at runtime.
 *
 * <p>systemd and OpenRC are modelled as <em>separate</em> platforms (each with its own
 * backend), not as one pluggable Linux platform.
 */
public enum Platform {

	MACOS_LAUNCHD,
	LINUX_SYSTEMD,
	LINUX_OPENRC,
	WINDOWS
}
