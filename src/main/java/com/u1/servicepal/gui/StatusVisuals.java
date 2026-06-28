package com.u1.servicepal.gui;

import com.u1.servicepal.model.RunState;
import java.awt.Color;
import javax.swing.Icon;

/** Colors, status dots, and friendly labels for a {@link RunState}. */
final class StatusVisuals {

	// Tuned for legibility on the dark theme.
	private static final Color GREEN = new Color(0x4C, 0xC0, 0x5E);
	private static final Color GREY = new Color(0xA8, 0xAD, 0xB3);
	private static final Color RED = new Color(0xE0, 0x5A, 0x4C);
	private static final Color AMBER = new Color(0xE0, 0x9A, 0x3A);

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
}
