package com.u1.servicepal.model;

import com.u1.servicepal.Installation;
import java.time.Instant;

/**
 * A small, honest snapshot of a service. Weak platforms populate what they can and leave the
 * rest {@code null} rather than fabricating.
 *
 * @param id           the service id
 * @param installation where it lives, or {@code null} if not installed
 * @param installed    whether a definition exists
 * @param enabled      boot/login persistence (may be approximate on some platforms)
 * @param managed      whether this library now manages it (our marker present)
 * @param adopted      whether we manage it but did <em>not</em> originally create it — i.e. we
 *                     installed over a service that already existed (a second, side-band marker).
 *                     Always {@code false} when {@code managed} is false.
 * @param state        coarse run state
 * @param pid          process id, or {@code null} if not running / unknown
 * @param lastExitCode last exit code, or {@code null} if unknown
 * @param raw          verbatim native status text, or {@code null}
 * @param nextRun      the next scheduled run, or {@code null} (unknown / not scheduled). Available
 *                     on Windows (Task Scheduler), systemd timers (calendar), and OpenRC/cron
 *                     (computed); launchd does not expose it.
 * @param lastRun      the last run, or {@code null} (unknown / never run). Available on Windows and
 *                     systemd timers; cron does not record it and launchd does not expose it.
 */
public record ServiceStatus(
		String id,
		Installation installation,
		boolean installed,
		boolean enabled,
		boolean managed,
		boolean adopted,
		RunState state,
		Integer pid,
		Integer lastExitCode,
		String raw,
		Instant nextRun,
		Instant lastRun) {

	/** Convenience: a status with no scheduled run times (the common, non-scheduled case). */
	public ServiceStatus(final String id, final Installation installation, final boolean installed,
			final boolean enabled, final boolean managed, final boolean adopted, final RunState state,
			final Integer pid, final Integer lastExitCode, final String raw) {
		this(id, installation, installed, enabled, managed, adopted, state, pid, lastExitCode, raw,
				null, null);
	}

	/** Convenience for the common case of a service we did not adopt ({@code adopted = false}). */
	public ServiceStatus(final String id, final Installation installation, final boolean installed,
			final boolean enabled, final boolean managed, final RunState state, final Integer pid,
			final Integer lastExitCode, final String raw) {
		this(id, installation, installed, enabled, managed, false, state, pid, lastExitCode, raw);
	}

	/** This status with scheduled run times attached (used by the scheduling backends). */
	public ServiceStatus withRunTimes(final Instant next, final Instant last) {
		return new ServiceStatus(id, installation, installed, enabled, managed, adopted, state, pid,
				lastExitCode, raw, next, last);
	}

	/** A status for an id that is not installed. */
	public static ServiceStatus absent(final String id) {
		return new ServiceStatus(id, null, false, false, false, RunState.UNKNOWN, null, null, null);
	}
}
