package com.u1.servicepal.internal.systemd;

import java.time.Instant;

/**
 * Machine-readable runtime state from {@code systemctl show}. Fields are the raw systemd
 * property values; {@link SystemdBackend} maps them onto our model.
 *
 * @param loadState     {@code loaded} / {@code not-found} / {@code masked} / …
 * @param activeState   {@code active} / {@code inactive} / {@code failed} / {@code activating} …
 * @param subState      type-specific (e.g. {@code running}, {@code exited}, {@code dead})
 * @param unitFileState {@code enabled} / {@code disabled} / {@code static} / …
 * @param mainPid       main process id, or {@code null} / {@code 0} when not running
 * @param execMainStatus last exit status of the main process, or {@code null}
 * @param nextElapseRealtime a timer's next elapse (from {@code NextElapseUSecRealtime}), or
 *                     {@code null} — only calendar timers have a realtime next elapse
 * @param lastTrigger  a timer's last trigger (from {@code LastTriggerUSec}), or {@code null}
 */
public record UnitState(
		String loadState,
		String activeState,
		String subState,
		String unitFileState,
		Integer mainPid,
		Integer execMainStatus,
		Instant nextElapseRealtime,
		Instant lastTrigger) {

	/** Convenience: state without timer run times (the common, non-timer case). */
	public UnitState(final String loadState, final String activeState, final String subState,
			final String unitFileState, final Integer mainPid, final Integer execMainStatus) {
		this(loadState, activeState, subState, unitFileState, mainPid, execMainStatus, null, null);
	}

	/** A unit systemd doesn't know about (not installed). */
	public static UnitState notFound() {
		return new UnitState("not-found", "inactive", "dead", null, null, null);
	}

	public boolean loaded() {
		return "loaded".equals(loadState);
	}
}
