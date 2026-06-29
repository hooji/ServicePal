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
	private final JLabel managedNote = new JLabel();
	private final JLabel statusValue = new JLabel();
	private final JLabel commandValue = new JLabel();
	private final JLabel folderValue = new JLabel();
	private final JLabel autoStartValue = new JLabel();
	private final JLabel restartValue = new JLabel();
	private final JLabel scheduleValue = new JLabel();
	private final JLabel nextRunValue = new JLabel();
	private final JLabel lastRunValue = new JLabel();
	private final JPanel grid = new JPanel(new GridBagLayout());
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
		title.setAlignmentX(LEFT_ALIGNMENT);
		managedNote.setAlignmentX(LEFT_ALIGNMENT);
		managedNote.setEnabled(false);   // muted hint, only shown for unmanaged services
		final JPanel heading = new JPanel();
		heading.setLayout(new BoxLayout(heading, BoxLayout.PAGE_AXIS));
		heading.add(title);
		heading.add(Box.createVerticalStrut(4));
		heading.add(managedNote);
		panel.add(heading, BorderLayout.NORTH);

		// The grid rows are (re)built per job in showJob: a kept-running job shows "Start
		// automatically" / "If it stops"; a scheduled job shows "Schedule" / "Next run" / "Last run".
		final JPanel gridHolder = new JPanel(new BorderLayout());
		gridHolder.add(grid, BorderLayout.NORTH);
		panel.add(gridHolder, BorderLayout.CENTER);

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
		title.setIcon(StatusVisuals.icon(job));
		title.setIconTextGap(10);
		title.setText(job.displayName());

		managedNote.setText(provenanceNote(job));
		managedNote.setVisible(managedNote.getText() != null);

		statusValue.setText(statusText(job));
		statusValue.setForeground(StatusVisuals.color(job));
		commandValue.setText(spec == null ? "(unreadable)" : String.join(" ", spec.command()));
		folderValue.setText(spec == null || spec.workingDirectory() == null
				? "—" : spec.workingDirectory().toString());
		layoutRows(job);

		// Every job is actionable; editing/removing a foreign one is confirmed by the controller.
		// A scheduled job has no "running" process to start/stop by hand — it runs on its schedule —
		// so the run buttons stay disabled (Edit/Remove still apply).
		final boolean running = status.state() == RunState.RUNNING;
		final boolean scheduled = job.scheduled();
		startBtn.setEnabled(!scheduled && !running);
		stopBtn.setEnabled(!scheduled && running);
		restartBtn.setEnabled(!scheduled && running);
		editBtn.setEnabled(true);
		removeBtn.setEnabled(true);
		((CardLayout) getLayout()).show(this, DETAIL);
	}

	/** Rebuild the property rows for this job: scheduled jobs show schedule + run times. */
	private void layoutRows(final Job job) {
		final ServiceSpec spec = job.spec();
		grid.removeAll();
		addRow(grid, 0, "Status", statusValue);
		addRow(grid, 1, "Command", commandValue);
		addRow(grid, 2, "Folder", folderValue);
		if (job.scheduled()) {
			scheduleValue.setText(ScheduleText.summary(job.schedule()));
			nextRunValue.setText(ScheduleText.runTime(job.status().nextRun()));
			lastRunValue.setText(ScheduleText.runTime(job.status().lastRun()));
			addRow(grid, 3, "Schedule", scheduleValue);
			addRow(grid, 4, "Next run", nextRunValue);
			addRow(grid, 5, "Last run", lastRunValue);
		} else {
			autoStartValue.setText(job.status().enabled() ? "Yes" : "No");
			restartValue.setText(spec == null ? "—" : restartLabel(spec.restart()));
			addRow(grid, 3, "Start automatically", autoStartValue);
			addRow(grid, 4, "If it stops", restartValue);
		}
		grid.revalidate();
		grid.repaint();
	}

	private static String statusText(final Job job) {
		final ServiceStatus status = job.status();
		final String base = StatusVisuals.label(job);
		if (status.pid() != null) {
			return base + "  (pid " + status.pid() + ")";
		}
		if (status.state() == RunState.FAILED && status.lastExitCode() != null) {
			return base + "  (exit " + status.lastExitCode() + ")";
		}
		return base;
	}

	/** A muted provenance line for adopted / foreign jobs, or {@code null} for ones we created. */
	private static String provenanceNote(final Job job) {
		if (!job.managed()) {
			return "Not created with ServicePal — editing it rewrites it in ServicePal's format.";
		}
		if (job.adopted()) {
			return "Adopted by ServicePal — originally created outside ServicePal.";
		}
		return null;
	}

	static String restartLabel(final RestartPolicy policy) {
		return switch (policy) {
			case ALWAYS -> "Always restart";
			case ON_FAILURE -> "Restart if it crashes";
			case NEVER -> "Leave stopped";
		};
	}
}
