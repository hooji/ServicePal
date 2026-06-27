package com.u1.servicepal;

/**
 * Is a service set up for one user, or for the whole computer?
 *
 * <p>This is the same choice a software installer offers ("install for all users, or just
 * me?"). It is the cross-platform abstraction of what launchd calls a <em>domain</em>
 * ({@code gui/<uid>} vs {@code system}), systemd calls system-vs-user scope, and Windows
 * splits into per-user vs machine-wide services. It is the same on all four platforms
 * (OpenRC supports {@link #SYSTEM_WIDE} only).
 *
 * <p>You do not set this directly; it is derived from a spec's {@code RunAs} identity. It
 * also serves as the explicit selector on by-id operations.
 */
public enum Installation {

	/** Belongs to one user account; lives in that user's space; needs no admin to manage. */
	PER_USER,

	/** Belongs to the whole machine; lives in system locations; needs admin; can run at boot. */
	SYSTEM_WIDE
}
