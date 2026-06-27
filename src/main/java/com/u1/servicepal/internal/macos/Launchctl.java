package com.u1.servicepal.internal.macos;

import java.nio.file.Path;

/** The launchctl seam (stub in tests). */
public interface Launchctl {

	/**
	 * Live runtime state for one service, queried in the given domain via
	 * {@code launchctl print}. Returns {@link ServiceRuntime#unknown()} when the domain can't
	 * be observed (e.g. the {@code system} domain without root) — never a misleading
	 * {@code STOPPED}.
	 */
	ServiceRuntime runtime(LaunchdDomain domain, String label);

	// --- mutation (modern subcommands) ---

	/** {@code launchctl bootstrap <domain> <plist>} — load/register the service. */
	void bootstrap(LaunchdDomain domain, Path plist);

	/** {@code launchctl bootout <domain>/<label>} — unload the service. */
	void bootout(LaunchdDomain domain, String label);

	/** {@code launchctl kickstart [-k] <domain>/<label>} — run now (or restart). */
	void kickstart(LaunchdDomain domain, String label, boolean restart);

	/** {@code launchctl kill <signal> <domain>/<label>} — signal the running process. */
	void killService(LaunchdDomain domain, String label, String signal);

	/** {@code launchctl enable <domain>/<label>} — allow loading (boot persistence). */
	void enable(LaunchdDomain domain, String label);

	/** {@code launchctl disable <domain>/<label>}. */
	void disable(LaunchdDomain domain, String label);
}
