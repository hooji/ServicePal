package com.u1.servicepal.internal.macos;

import com.u1.servicepal.model.RunState;

/**
 * Live runtime facts about a launchd service, from {@code launchctl print}.
 *
 * @param state        run state ({@code UNKNOWN} when we can't observe the relevant domain)
 * @param pid          running pid, or {@code null}
 * @param lastExitCode last exit code, or {@code null}
 */
public record ServiceRuntime(RunState state, Integer pid, Integer lastExitCode) {

	public static ServiceRuntime unknown() {
		return new ServiceRuntime(RunState.UNKNOWN, null, null);
	}
}
