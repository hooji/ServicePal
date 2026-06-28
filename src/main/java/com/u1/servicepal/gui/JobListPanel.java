package com.u1.servicepal.gui;

import com.u1.servicepal.model.RunState;
import java.awt.Component;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;

/** The master list (left side): a table of jobs with status dots; notifies on selection. */
final class JobListPanel extends JTable {

	private final JobTableModel jobModel = new JobTableModel();
	private final Consumer<Job> onSelect;

	JobListPanel(final Consumer<Job> onSelect) {
		this.onSelect = onSelect;
		setModel(jobModel);
		setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		setRowHeight(28);
		setShowVerticalLines(false);
		setFillsViewportHeight(true);
		setIntercellSpacing(new java.awt.Dimension(0, 0));
		getTableHeader().setReorderingAllowed(false);
		getColumnModel().getColumn(0).setPreferredWidth(230);
		getColumnModel().getColumn(1).setPreferredWidth(110);
		getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
		getColumnModel().getColumn(1).setCellRenderer(new StateRenderer());
		getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				final int row = getSelectedRow();
				this.onSelect.accept(row < 0 ? null : jobModel.jobAt(row));
			}
		});
	}

	void setJobs(final List<Job> jobs) {
		final String previouslySelected = selectedId();
		jobModel.setJobs(jobs);
		if (previouslySelected != null) {
			selectId(previouslySelected);
		}
	}

	String selectedId() {
		final int row = getSelectedRow();
		return row < 0 ? null : jobModel.jobAt(row).id();
	}

	Job selectedJob() {
		final int row = getSelectedRow();
		return row < 0 ? null : jobModel.jobAt(row);
	}

	void selectId(final String id) {
		final int idx = jobModel.indexOfId(id);
		if (idx >= 0) {
			getSelectionModel().setSelectionInterval(idx, idx);
		} else {
			clearSelection();
		}
	}

	void selectFirstIfAny() {
		if (jobModel.getRowCount() > 0 && getSelectedRow() < 0) {
			getSelectionModel().setSelectionInterval(0, 0);
		}
	}

	/** Column 0: a status dot followed by the job's display name. */
	private static final class NameRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value,
				final boolean isSelected, final boolean hasFocus, final int row, final int column) {
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if (value instanceof Job job) {
				setText(job.displayName());
				setIcon(StatusVisuals.icon(job.status().state()));
				setIconTextGap(8);
			}
			setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 0));
			return this;
		}
	}

	/** Column 1: the friendly run-state label, colored by state (the L&F owns the selected row). */
	private static final class StateRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value,
				final boolean isSelected, final boolean hasFocus, final int row, final int column) {
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if (value instanceof RunState state) {
				setText(StatusVisuals.label(state));
				// Color the label by state, but let the L&F own the selected row's foreground.
				if (!isSelected) {
					setForeground(StatusVisuals.color(state));
				}
			}
			return this;
		}
	}
}
