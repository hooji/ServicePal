package com.u1.servicepal.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;

/** A small filled circle, used as a status indicator next to a job's name. */
final class CircleIcon implements Icon {

	private final Color color;
	private final int diameter;

	CircleIcon(final Color color, final int diameter) {
		this.color = color;
		this.diameter = diameter;
	}

	@Override
	public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(color);
		g2.fillOval(x, y, diameter, diameter);
		g2.setColor(color.darker());
		g2.drawOval(x, y, diameter, diameter);
		g2.dispose();
	}

	@Override
	public int getIconWidth() {
		return diameter;
	}

	@Override
	public int getIconHeight() {
		return diameter;
	}
}
