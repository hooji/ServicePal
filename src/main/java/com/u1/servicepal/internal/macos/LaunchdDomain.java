package com.u1.servicepal.internal.macos;

/**
 * The launchd <em>runtime</em> domain a service is managed in — distinct from our
 * {@code Installation}. Daemons (in {@code LaunchDaemons}) live in the {@code system} domain;
 * agents (in either {@code LaunchAgents}) live in a per-user {@code gui/<uid>} domain. This
 * determines the {@code launchctl print} target used to read live state.
 */
public enum LaunchdDomain {

	/** {@code system/<label>} — requires root to observe. */
	SYSTEM,

	/** {@code gui/<uid>/<label>} — the invoking user's GUI session. */
	GUI
}
