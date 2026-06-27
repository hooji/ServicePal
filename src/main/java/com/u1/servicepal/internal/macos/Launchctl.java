package com.u1.servicepal.internal.macos;

/** The launchctl seam (stub in tests). */
public interface Launchctl {

	/**
	 * Live runtime state for one service, queried in the given domain via
	 * {@code launchctl print}. Returns {@link ServiceRuntime#unknown()} when the domain can't
	 * be observed (e.g. the {@code system} domain without root) — never a misleading
	 * {@code STOPPED}.
	 */
	ServiceRuntime runtime(LaunchdDomain domain, String label);
}
