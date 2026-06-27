package com.u1.servicepal;

/**
 * What the current platform/backend can actually do. Queried before {@code install()} so
 * unsupported features fail fast rather than degrading silently.
 *
 * <p>Modelled as an immutable record; each accessor is a plain boolean feature flag.
 */
public record Capabilities(
		boolean perUserInstall,
		boolean systemWideInstall,
		boolean namedUser,
		boolean calendarSchedule,
		boolean intervalSchedule,
		boolean keepAlive,
		boolean conditionalKeepAlive,
		boolean logFileRedirection,
		boolean structuredStatus) {
}
