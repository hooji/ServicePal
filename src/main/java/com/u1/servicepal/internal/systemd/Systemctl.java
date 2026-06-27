package com.u1.servicepal.internal.systemd;

/**
 * The systemctl seam (stub in tests). {@code user} selects the per-user manager
 * ({@code systemctl --user}) vs the system manager.
 */
public interface Systemctl {

	/** Re-read unit files from disk. Required after writing/editing a unit. */
	void daemonReload(boolean user);

	void enable(boolean user, String unit);

	void disable(boolean user, String unit);

	void start(boolean user, String unit);

	void stop(boolean user, String unit);

	void restart(boolean user, String unit);

	/** Machine-readable status via {@code systemctl show}. Never null. */
	UnitState show(boolean user, String unit);
}
