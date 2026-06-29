package com.u1.servicepal.gui;

import com.u1.servicepal.model.RunState;
import java.awt.Color;
import javax.swing.Icon;

/** Colors, status dots, and friendly labels for a {@link RunState} (or a whole {@link Job}). */
final class StatusVisuals {

	private static final Color GREEN = new Color(0x2E, 0xA0, 0x43);
	private static final Color GREY = new Color(0x8A, 0x8A, 0x8A);
	private static final Color RED = new Color(0xC0, 0x39, 0x2B);
	private static final Color AMBER = new Color(0xE0, 0x8A, 0x1E);
	private static final Color BLUE = new Color(0x2D, 0x6C, 0xDF);   // a scheduled (armed) job

	private StatusVisuals() {
	}

	static Color color(final RunState state) {
		return switch (state) {
			case RUNNING -> GREEN;
			case FAILED -> RED;
			case STARTING, STOPPING -> AMBER;
			case STOPPED, UNKNOWN -> GREY;
		};
	}

	static Icon icon(final RunState state) {
		return new CircleIcon(color(state), 11);
	}

	static String label(final RunState state) {
		return switch (state) {
			case RUNNING -> "Running";
			case STOPPED -> "Stopped";
			case FAILED -> "Failed";
			case STARTING -> "Starting…";
			case STOPPING -> "Stopping…";
			case UNKNOWN -> "Unknown";
		};
	}

	// --- job-aware variants: an armed-but-idle scheduled job reads "Scheduled", not "Stopped" ---

	/**
	 * A scheduled job that is neither mid-run nor failed — i.e. armed and waiting for its next firing.
	 * Such a job has no long-running process, so its raw run state is STOPPED/UNKNOWN; the GUI shows
	 * it as "Scheduled" rather than "Stopped".
	 */
	static boolean isScheduledIdle(final Job job) {
		if (!job.scheduled()) {
			return false;
		}
		final RunState state = job.status().state();
		return state == RunState.STOPPED || state == RunState.UNKNOWN;
	}

	static Color color(final Job job) {
		return isScheduledIdle(job) ? BLUE : color(job.status().state());
	}

	static Icon icon(final Job job) {
		return new CircleIcon(color(job), 11);
	}

	static String label(final Job job) {
		return isScheduledIdle(job) ? "Scheduled" : label(job.status().state());
	}
}
