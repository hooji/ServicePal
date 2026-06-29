package com.u1.servicepal.internal.windows;

/**
 * One entry from a machine-wide service enumeration ({@code EnumServicesStatusExW}): the service
 * (key) name plus its live status. Used by {@link WindowsBackend#discover} to surface third-party
 * services — the ones ServicePal did not create — in the "other background jobs" view.
 *
 * @param name   the service key name (e.g. {@code "Spooler"})
 * @param status its live {@link ServiceControlStatus} (state + pid + last exit code)
 */
public record ScmService(String name, ServiceControlStatus status) {
}
