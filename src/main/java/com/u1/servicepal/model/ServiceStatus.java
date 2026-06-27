package com.u1.servicepal.model;

import com.u1.servicepal.Installation;

/**
 * A small, honest snapshot of a service. Weak platforms populate what they can and leave the
 * rest {@code null} rather than fabricating.
 *
 * @param id           the service id
 * @param installation where it lives, or {@code null} if not installed
 * @param installed    whether a definition exists
 * @param enabled      boot/login persistence (may be approximate on some platforms)
 * @param managed      whether this library created it (marker present)
 * @param state        coarse run state
 * @param pid          process id, or {@code null} if not running / unknown
 * @param lastExitCode last exit code, or {@code null} if unknown
 * @param raw          verbatim native status text, or {@code null}
 */
public record ServiceStatus(
		String id,
		Installation installation,
		boolean installed,
		boolean enabled,
		boolean managed,
		RunState state,
		Integer pid,
		Integer lastExitCode,
		String raw) {

	/** A status for an id that is not installed. */
	public static ServiceStatus absent(final String id) {
		return new ServiceStatus(id, null, false, false, false, RunState.UNKNOWN, null, null, null);
	}
}
