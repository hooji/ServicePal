package com.u1.servicepal.gui;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/** The detail side (right): the selected job's properties, live status, and action buttons. */
final class JobDetailPanel extends JPanel {

	private static final String EMPTY = "empty";
	private static final String DETAIL = "detail";

	private final JLabel title = new JLabel();
	private final JLabel statusValue = new JLabel();
	private final JLabel commandValue = new JLabel();
	private final JLabel folderValue = new JLabel();
	private final JLabel autoStartValue = new JLabel();
	private final JLabel restartValue = new JLabel();
	private final JButton startBtn = new JButton("Start");
	private final JButton stopBtn = new JButton("Stop");
	private final JButton restartBtn = new JButton("Restart");
	private final JButton editBtn = new JButton("Edit…");
	private final JButton removeBtn = new JButton("Remove");

	JobDetailPanel(final JobActions actions) {
		super(new CardLayout());
		setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

		final JLabel emptyHint = new JLabel("Select a job to see its details, "
				+ "or click “+ Add Job” to create one.", SwingConstants.CENTER);
		emptyHint.setEnabled(false);

		add(emptyHint, EMPTY);
		add(buildDetail(actions), DETAIL);
		((CardLayout) getLayout()).show(this, EMPTY);

		startBtn.addActionListener(e -> actions.startSelected());
		stopBtn.addActionListener(e -> actions.stopSelected());
		restartBtn.addActionListener(e -> actions.restartSelected());
		editBtn.addActionListener(e -> actions.editSelected());
		removeBtn.addActionListener(e -> actions.removeSelected());
	}

	private JPanel buildDetail(final JobActions actions) {
		final JPanel panel = new JPanel(new BorderLayout(0, 16));

		title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 6f));
		panel.add(title, BorderLayout.NORTH);

		final JPanel grid = new JPanel(new GridBagLayout());
		addRow(grid, 0, "Status", statusValue);
		addRow(grid, 1, "Command", commandValue);
		addRow(grid, 2, "Folder", folderValue);
		addRow(grid, 3, "Start automatically", autoStartValue);
		addRow(grid, 4, "If it stops", restartValue);
		panel.add(grid, BorderLayout.CENTER);

		final JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
		buttons.add(startBtn);
		buttons.add(Box.createHorizontalStrut(6));
		buttons.add(stopBtn);
		buttons.add(Box.createHorizontalStrut(6));
		buttons.add(restartBtn);
		buttons.add(Box.createHorizontalGlue());
		buttons.add(editBtn);
		buttons.add(Box.createHorizontalStrut(6));
		buttons.add(removeBtn);
		panel.add(buttons, BorderLayout.SOUTH);
		return panel;
	}

	private static void addRow(final JPanel grid, final int row, final String label,
			final JLabel value) {
		final GridBagConstraints key = new GridBagConstraints();
		key.gridx = 0;
		key.gridy = row;
		key.anchor = GridBagConstraints.NORTHWEST;
		key.insets = new Insets(4, 0, 4, 14);
		final JLabel keyLabel = new JLabel(label);
		keyLabel.setEnabled(false);
		grid.add(keyLabel, key);

		final GridBagConstraints val = new GridBagConstraints();
		val.gridx = 1;
		val.gridy = row;
		val.weightx = 1.0;
		val.fill = GridBagConstraints.HORIZONTAL;
		val.anchor = GridBagConstraints.NORTHWEST;
		val.insets = new Insets(4, 0, 4, 0);
		grid.add(value, val);
	}

	/** Show a job, or clear the panel when {@code job} is {@code null}. */
	void showJob(final Job job) {
		if (job == null) {
			((CardLayout) getLayout()).show(this, EMPTY);
			return;
		}
		final ServiceStatus status = job.status();
		final ServiceSpec spec = job.spec();
		title.setIcon(StatusVisuals.icon(status.state()));
		title.setIconTextGap(10);
		title.setText(job.displayName());

		statusValue.setText(statusText(status));
		statusValue.setForeground(StatusVisuals.color(status.state()));
		commandValue.setText(spec == null ? "(unreadable)" : String.join(" ", spec.command()));
		folderValue.setText(spec == null || spec.workingDirectory() == null
				? "—" : spec.workingDirectory().toString());
		autoStartValue.setText(status.enabled() ? "Yes" : "No");
		restartValue.setText(spec == null ? "—" : restartLabel(spec.restart()));

		final boolean running = status.state() == RunState.RUNNING;
		startBtn.setEnabled(!running);
		stopBtn.setEnabled(running);
		restartBtn.setEnabled(running);
		((CardLayout) getLayout()).show(this, DETAIL);
	}

	private static String statusText(final ServiceStatus status) {
		final String base = StatusVisuals.label(status.state());
		if (status.pid() != null) {
			return base + "  (pid " + status.pid() + ")";
		}
		if (status.state() == RunState.FAILED && status.lastExitCode() != null) {
			return base + "  (exit " + status.lastExitCode() + ")";
		}
		return base;
	}

	static String restartLabel(final RestartPolicy policy) {
		return switch (policy) {
			case ALWAYS -> "Always restart";
			case ON_FAILURE -> "Restart if it crashes";
			case NEVER -> "Leave stopped";
		};
	}
}
