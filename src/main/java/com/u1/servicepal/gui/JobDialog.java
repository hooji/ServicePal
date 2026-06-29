package com.u1.servicepal.gui;

import com.u1.servicepal.model.RestartPolicy;
import java.awt.BorderLayout;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

/**
 * The modal Add/Edit dialog: a {@link JobFormPanel} plus Cancel/Save. {@link #showDialog} blocks
 * and returns the entered {@link JobForm}, or {@code null} if the user cancelled.
 */
final class JobDialog extends JDialog {

	private final JobFormPanel form;
	private JobForm result;

	private JobDialog(final Window owner, final String title, final JobForm initial,
			final boolean schedulingSupported) {
		super(owner, title, ModalityType.APPLICATION_MODAL);
		form = new JobFormPanel(schedulingSupported);
		form.setForm(initial);

		final JButton cancel = new JButton("Cancel");
		final JButton save = new JButton("Save");
		cancel.addActionListener(e -> {
			result = null;
			dispose();
		});
		save.addActionListener(e -> {
			if (!form.hasCommand()) {
				JOptionPane.showMessageDialog(this, "Please enter a command to run.",
						"Missing command", JOptionPane.WARNING_MESSAGE);
				return;
			}
			result = form.getForm();
			dispose();
		});

		final JPanel buttons = new JPanel();
		buttons.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
		buttons.add(Box.createHorizontalGlue());
		buttons.add(cancel);
		buttons.add(Box.createHorizontalStrut(8));
		buttons.add(save);

		final JPanel content = new JPanel(new BorderLayout());
		content.add(form, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);
		getRootPane().setDefaultButton(save);
		pack();
		setLocationRelativeTo(owner);
	}

	/** Show modally; returns the entered form, or {@code null} if cancelled. */
	static JobForm showDialog(final Window owner, final String title, final JobForm initial,
			final boolean schedulingSupported) {
		final JobDialog dialog = new JobDialog(owner, title, initial, schedulingSupported);
		dialog.setVisible(true);   // blocks until disposed
		return dialog.result;
	}

	/** A blank form for a new job, with sensible defaults. */
	static JobForm blankForm() {
		return new JobForm(null, "", "", "", "", true, RestartPolicy.ALWAYS);
	}

	/**
	 * Build the same dialog but <em>modeless</em> and pre-filled, for the screenshot harness to
	 * show, capture, and dispose without blocking. Not used by the interactive app.
	 */
	static JobDialog buildForScreenshot(final Window owner, final JobForm initial,
			final boolean schedulingSupported) {
		final JobDialog dialog = new JobDialog(owner, "Add Job", initial, schedulingSupported);
		dialog.setModal(false);
		return dialog;
	}
}
