package com.u1.servicepal;

import com.u1.servicepal.internal.DefaultServiceManager;
import com.u1.servicepal.internal.Platforms;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.util.List;

/**
 * The single entry point. Obtain one with {@link #getServiceManager()} (implicitly for this
 * platform). Lifecycle calls take just the service {@code id}; the manager auto-resolves the
 * {@link Installation} (PER_USER first, then SYSTEM_WIDE), throwing
 * {@link AmbiguousServiceException} if an id exists in both — pass an explicit
 * {@code Installation} to disambiguate.
 *
 * <p><strong>Build status:</strong> discovery and inspection (list / read / status) are fully
 * implemented for macOS. Mutation (install/start/stop/…) and the systemd/OpenRC/Windows
 * backends throw {@link UnsupportedOperationException} for now (steps 4–5).
 */
public interface ServiceManager {

	// --- construction (implicitly THIS platform) ---

	static ServiceManager getServiceManager() {
		return DefaultServiceManager.create(Platforms.detect());
	}

	/** Explicit platform selection — for tests and cross-platform rendering. */
	static ServiceManager getServiceManager(final Platform platform) {
		return DefaultServiceManager.create(platform);
	}

	Platform platform();

	Capabilities capabilities();

	// --- discovery & inspection ---

	/** All services visible in reachable installations. */
	List<ServiceStatus> list();

	/** Only services this library created (managed-by marker present). */
	List<ServiceStatus> listManaged();

	boolean isManaged(String id);

	boolean isManaged(String id, Installation installation);

	/** Parsed spec, or {@code null} if not installed. */
	ServiceSpec read(String id);

	ServiceSpec read(String id, Installation installation);

	/** Verbatim native definition text, or {@code null} if not installed. */
	String readNative(String id);

	String readNative(String id, Installation installation);

	/** Never {@code null}; {@link ServiceStatus#installed()} is false if absent. */
	ServiceStatus status(String id);

	ServiceStatus status(String id, Installation installation);

	boolean isInstalled(String id);

	boolean isInstalled(String id, Installation installation);

	// --- definition lifecycle (UPSERT) — implemented in step 4 ---

	void install(ServiceSpec spec);

	void install(ServiceSpec spec, boolean yesDoThisToAServiceIDidNotCreate);

	void uninstall(String id);

	void uninstall(String id, boolean yesDoThisToAServiceIDidNotCreate);

	// --- run-now vs boot-persistence (separate axes) — implemented in step 4 ---

	void enable(String id);

	void disable(String id);

	void start(String id);

	void stop(String id);

	void restart(String id);

	// --- convenience — implemented in step 4 ---

	void installEnableStart(ServiceSpec spec);
}
