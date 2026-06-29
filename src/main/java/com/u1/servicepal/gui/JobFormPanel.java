package com.u1.servicepal.gui;

import com.u1.servicepal.model.RestartPolicy;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

/**
 * The fields of the add/edit form, with no Cancel/Save chrome of its own. Reused by
 * {@link JobDialog} (wrapped in a modal dialog) and by the screenshot harness (shown modelessly),
 * so the captured "Add Job" image is exactly the form the user sees.
 *
 * <p>When the platform supports scheduling, a <em>mode</em> toggle picks between "Keep it running"
 * (the {@code autoStart} + {@code restart} fields) and "On a schedule" (a {@link SchedulePanel}).
 * Where scheduling is unsupported the toggle is hidden and only the keep-running fields show.
 */
final class JobFormPanel extends JPanel {

	private static final String KEEP = "keep";
	private static final String SCHEDULE = "schedule";

	private final boolean schedulingSupported;

	private String id;   // preserved across edit; null for a new job
	private final JTextField name = new JTextField(24);
	private final JTextField command = new JTextField(24);
	private final JTextField arguments = new JTextField(24);
	private final JTextField folder = new JTextField(24);

	private final JRadioButton keepMode = new JRadioButton("Keep it running", true);
	private final JRadioButton scheduledMode = new JRadioButton("On a schedule");
	private final JPanel modeCards = new JPanel(new java.awt.CardLayout());

	private final JCheckBox autoStart = new JCheckBox("Start automatically (at login / boot)", true);
	private final JComboBox<RestartPolicy> restart = new JComboBox<>(
			new RestartPolicy[] {RestartPolicy.ALWAYS, RestartPolicy.ON_FAILURE, RestartPolicy.NEVER});
	private final SchedulePanel schedule = new SchedulePanel();

	JobFormPanel(final boolean schedulingSupported) {
		super(new GridBagLayout());
		this.schedulingSupported = schedulingSupported;
		setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
		restart.setRenderer(new RestartRenderer());

		int row = 0;
		addField(row++, "Name", name, null);
		addField(row++, "Command", command, browseFor(command, JFileChooser.FILES_ONLY));
		addField(row++, "Arguments", arguments, null);
		addField(row++, "Folder", folder, browseFor(folder, JFileChooser.DIRECTORIES_ONLY));
		addModeSection(row);
	}

	void setForm(final JobForm form) {
		this.id = form.id();
		name.setText(nullToEmpty(form.name()));
		command.setText(nullToEmpty(form.command()));
		arguments.setText(nullToEmpty(form.arguments()));
		folder.setText(nullToEmpty(form.folder()));
		autoStart.setSelected(form.autoStart());
		restart.setSelectedItem(form.restart() == null ? RestartPolicy.ALWAYS : form.restart());
		if (schedulingSupported && form.scheduled()) {
			scheduledMode.setSelected(true);
			schedule.setSchedule(form.schedule());
		} else {
			keepMode.setSelected(true);
		}
		showMode();
	}

	JobForm getForm() {
		final boolean scheduled = schedulingSupported && scheduledMode.isSelected();
		return new JobForm(id, name.getText().trim(), command.getText().trim(),
				arguments.getText().trim(), folder.getText().trim(), autoStart.isSelected(),
				(RestartPolicy) restart.getSelectedItem(),
				scheduled ? schedule.getSchedule() : null);
	}

	/** The command field is the only hard requirement. */
	boolean hasCommand() {
		return !command.getText().trim().isEmpty();
	}

	/** The mode toggle (if shown) and the card for the selected mode, spanning the form's width. */
	private void addModeSection(final int row) {
		final JPanel section = new JPanel();
		section.setLayout(new javax.swing.BoxLayout(section, javax.swing.BoxLayout.PAGE_AXIS));

		if (schedulingSupported) {
			final ButtonGroup group = new ButtonGroup();
			group.add(keepMode);
			group.add(scheduledMode);
			keepMode.addActionListener(e -> showMode());
			scheduledMode.addActionListener(e -> showMode());
			final JPanel modes = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
			modes.setAlignmentX(LEFT_ALIGNMENT);
			modes.add(keepMode);
			modes.add(javax.swing.Box.createHorizontalStrut(12));
			modes.add(scheduledMode);
			section.add(modes);
			section.add(javax.swing.Box.createVerticalStrut(8));
		}

		modeCards.setAlignmentX(LEFT_ALIGNMENT);
		modeCards.add(keepCard(), KEEP);
		modeCards.add(scheduleCard(), SCHEDULE);
		section.add(modeCards);

		final GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 3;
		c.weightx = 1.0;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.insets = new Insets(8, 0, 0, 0);
		add(section, c);
	}

	private JPanel keepCard() {
		final JPanel card = new JPanel(new GridBagLayout());

		final GridBagConstraints auto = new GridBagConstraints();
		auto.gridx = 0;
		auto.gridy = 0;
		auto.gridwidth = 2;
		auto.anchor = GridBagConstraints.WEST;
		auto.insets = new Insets(2, 0, 6, 0);
		card.add(autoStart, auto);

		final GridBagConstraints label = new GridBagConstraints();
		label.gridx = 0;
		label.gridy = 1;
		label.anchor = GridBagConstraints.WEST;
		label.insets = new Insets(2, 0, 2, 12);
		card.add(new JLabel("If it stops"), label);

		final GridBagConstraints field = new GridBagConstraints();
		field.gridx = 1;
		field.gridy = 1;
		field.anchor = GridBagConstraints.WEST;
		card.add(restart, field);
		return card;
	}

	private JPanel scheduleCard() {
		final JPanel card = new JPanel(new java.awt.BorderLayout());
		card.add(schedule, java.awt.BorderLayout.WEST);
		return card;
	}

	private void showMode() {
		final String key = schedulingSupported && scheduledMode.isSelected() ? SCHEDULE : KEEP;
		((java.awt.CardLayout) modeCards.getLayout()).show(modeCards, key);
	}

	private void addField(final int row, final String label, final Component field,
			final JButton browse) {
		final GridBagConstraints l = new GridBagConstraints();
		l.gridx = 0;
		l.gridy = row;
		l.anchor = GridBagConstraints.WEST;
		l.insets = new Insets(6, 0, 6, 12);
		add(new JLabel(label), l);

		final GridBagConstraints f = new GridBagConstraints();
		f.gridx = 1;
		f.gridy = row;
		f.weightx = 1.0;
		f.fill = GridBagConstraints.HORIZONTAL;
		f.insets = new Insets(6, 0, 6, browse == null ? 0 : 8);
		add(field, f);

		if (browse != null) {
			final GridBagConstraints b = new GridBagConstraints();
			b.gridx = 2;
			b.gridy = row;
			b.insets = new Insets(6, 0, 6, 0);
			add(browse, b);
		}
	}

	private JButton browseFor(final JTextField target, final int selectionMode) {
		final JButton button = new JButton("Browse…");
		button.addActionListener(e -> {
			final JFileChooser chooser = new JFileChooser();
			chooser.setFileSelectionMode(selectionMode);
			if (!target.getText().trim().isEmpty()) {
				chooser.setSelectedFile(new File(target.getText().trim()));
			}
			if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
				target.setText(chooser.getSelectedFile().getAbsolutePath());
			}
		});
		return button;
	}

	private static String nullToEmpty(final String s) {
		return s == null ? "" : s;
	}

	/** Renders the restart policy with the same friendly labels the detail panel uses. */
	private static final class RestartRenderer extends DefaultListCellRenderer {
		@Override
		public Component getListCellRendererComponent(final JList<?> list, final Object value,
				final int index, final boolean isSelected, final boolean cellHasFocus) {
			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			if (value instanceof RestartPolicy policy) {
				setText(JobDetailPanel.restartLabel(policy));
			}
			return this;
		}
	}
}
