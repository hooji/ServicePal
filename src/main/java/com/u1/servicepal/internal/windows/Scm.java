package com.u1.servicepal.internal.windows;

import java.util.List;

/**
 * The Service Control Manager seam (stub in tests). Backed by {@code advapi32} via FFM
 * ({@link FfmScm}); a {@code RecordingScm} fake lets the {@link WindowsBackend} unit-test
 * off-Windows. All operations target machine-wide ({@code SYSTEM_WIDE}) services — the only
 * installation Windows supports in v1.
 */
public interface Scm {

	/** Does a service with this name exist? (OpenService succeeds.) */
	boolean exists(String name);

	/**
	 * Create a service.
	 *
	 * @param name        the service (key) name
	 * @param displayName the human-readable display name
	 * @param binPath     the full {@code ImagePath} (our service host invocation)
	 * @param startType   auto / demand / disabled
	 * @param account     logon account ({@code null} = {@code LocalSystem})
	 * @param password    the account password ({@code null} for LocalSystem / virtual accounts)
	 * @param dependsOn   service dependencies (may be empty)
	 */
	void create(String name, String displayName, String binPath, ServiceStartType startType,
			String account, String password, List<String> dependsOn);

	/** Delete a service ({@code DeleteService}; deferred until handles close). */
	void delete(String name);

	/** Start a service ({@code StartServiceW}). */
	void start(String name);

	/** Send the STOP control ({@code ControlService}). */
	void stop(String name);

	/** Change only the start type ({@code ChangeServiceConfigW}); used by enable/disable. */
	void setStartType(String name, ServiceStartType startType);

	/**
	 * Reconcile an existing service's config in place ({@code ChangeServiceConfigW}) — used by an
	 * upsert so a changed account / displayName / start type actually reaches the SCM record
	 * (avoids the delete-then-recreate {@code ERROR_SERVICE_MARKED_FOR_DELETE} hazard).
	 *
	 * @param account {@code null} resets the logon account to {@code LocalSystem}
	 */
	void updateConfig(String name, String binPath, ServiceStartType startType, String account,
			String password, String displayName);

	/** Set the description ({@code ChangeServiceConfig2W}); carries our managed marker too. */
	void setDescription(String name, String description);

	/** Live status ({@code QueryServiceStatusEx}), or {@code null} if the service is not installed. */
	ServiceControlStatus queryStatus(String name);

	/**
	 * Enumerate every Win32 service on this machine ({@code EnumServicesStatusExW}) — name + live
	 * status. Used for machine-wide discovery so third-party services (the ones ServicePal did not
	 * create) appear alongside the managed ones. Returns an empty list if there are none.
	 */
	List<ScmService> enumerate();
}
