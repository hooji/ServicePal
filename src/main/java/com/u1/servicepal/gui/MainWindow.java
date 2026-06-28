package com.u1.servicepal.gui;

import com.u1.servicepal.ServiceManager;
import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JFrame;

/** The application window: a {@link JobsController}'s content inside a sized, titled frame. */
final class MainWindow extends JFrame {

	private final JobsController controller;

	MainWindow(final ServiceManager manager) {
		super("ServicePal");
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		this.controller = new JobsController(manager, this);
		setContentPane((JComponent) controller.component());
		setMinimumSize(new Dimension(720, 440));
		setPreferredSize(new Dimension(940, 560));
		pack();
		setLocationRelativeTo(null);
	}

	JobsController controller() {
		return controller;
	}
}
