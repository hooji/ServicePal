package com.u1.servicepal.model;

import java.time.Duration;

/** A monotonic "every N" schedule. */
public record IntervalSchedule(Duration period) implements Schedule {

	public IntervalSchedule {
		if (period == null || period.isZero() || period.isNegative()) {
			throw new IllegalArgumentException("period must be positive");
		}
	}
}
