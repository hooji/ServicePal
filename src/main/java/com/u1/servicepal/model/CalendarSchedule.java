package com.u1.servicepal.model;

/** A wall-clock (calendar) schedule. */
public record CalendarSchedule(CalendarSpec spec) implements Schedule {

	public CalendarSchedule {
		if (spec == null) {
			throw new IllegalArgumentException("spec must not be null");
		}
	}
}
