package com.u1.servicepal.gui;

import com.u1.servicepal.model.RestartPolicy;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * The fields of the add/edit form, with no Cancel/Save chrome of its own. Reused by
 * {@link JobDialog} (wrapped in a modal dialog) and by the screenshot harness (shown modelessly),
 * so the captured "Add Job" image is exactly the form the user sees.
 */
final class JobFormPanel extends JPanel {

	private String id;   // preserved across edit; null for a new job
	private final JTextField name = new JTextField(24);
	private final JTextField command = new JTextField(24);
	private final JTextField arguments = new JTextField(24);
	private final JTextField folder = new JTextField(24);
	private final JCheckBox autoStart = new JCheckBox("Start automatically (at login / boot)", true);
	private final JComboBox<RestartPolicy> restart = new JComboBox<>(
			new RestartPolicy[] {RestartPolicy.ALWAYS, RestartPolicy.ON_FAILURE, RestartPolicy.NEVER});

	JobFormPanel() {
		super(new GridBagLayout());
		setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
		restart.setRenderer(new RestartRenderer());

		int row = 0;
		addField(row++, "Name", name, null);
		addField(row++, "Command", command, browseFor(command, JFileChooser.FILES_ONLY));
		addField(row++, "Arguments", arguments, null);
		addField(row++, "Folder", folder, browseFor(folder, JFileChooser.DIRECTORIES_ONLY));
		addSpanning(row++, autoStart);
		addField(row++, "If it stops", restart, null);
	}

	void setForm(final JobForm form) {
		this.id = form.id();
		name.setText(nullToEmpty(form.name()));
		command.setText(nullToEmpty(form.command()));
		arguments.setText(nullToEmpty(form.arguments()));
		folder.setText(nullToEmpty(form.folder()));
		autoStart.setSelected(form.autoStart());
		restart.setSelectedItem(form.restart() == null ? RestartPolicy.ALWAYS : form.restart());
	}

	JobForm getForm() {
		return new JobForm(id, name.getText().trim(), command.getText().trim(),
				arguments.getText().trim(), folder.getText().trim(), autoStart.isSelected(),
				(RestartPolicy) restart.getSelectedItem());
	}

	/** The command field is the only hard requirement. */
	boolean hasCommand() {
		return !command.getText().trim().isEmpty();
	}

	JTextField nameField() {
		return name;
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

	private void addSpanning(final int row, final Component field) {
		final GridBagConstraints c = new GridBagConstraints();
		c.gridx = 1;
		c.gridy = row;
		c.gridwidth = 2;
		c.anchor = GridBagConstraints.WEST;
		c.insets = new Insets(6, 0, 6, 0);
		add(field, c);
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
