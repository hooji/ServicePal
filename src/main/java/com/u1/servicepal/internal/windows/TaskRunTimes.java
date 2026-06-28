package com.u1.servicepal.internal.windows;

import java.time.Instant;

/** A scheduled task's next and last run times (either may be {@code null} = unknown / never). */
public record TaskRunTimes(Instant next, Instant last) {

	public static final TaskRunTimes UNKNOWN = new TaskRunTimes(null, null);
}
