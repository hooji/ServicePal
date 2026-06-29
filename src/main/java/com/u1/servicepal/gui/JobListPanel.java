package com.u1.servicepal.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The master list (left side): a table of jobs with status dots, grouped into up to three
 * non-selectable sections (created here, adopted, then everything else discovered on the machine).
 * Header rows are skipped by mouse and keyboard selection; notifies on the selected {@link Job}.
 */
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

	/**
	 * Redirect selection away from section-header rows (mouse clicks and arrow keys alike) to the
	 * nearest job row in the direction of travel, so headers never appear selected.
	 */
	@Override
	public void changeSelection(final int row, final int column, final boolean toggle,
			final boolean extend) {
		if (jobModel.isHeader(row)) {
			final int current = getSelectedRow();
			int target = row > current ? nextJobRow(row) : prevJobRow(row);
			if (target < 0) {
				target = row > current ? prevJobRow(row) : nextJobRow(row);
			}
			if (target < 0) {
				return;
			}
			super.changeSelection(target, column, toggle, extend);
			return;
		}
		super.changeSelection(row, column, toggle, extend);
	}

	private int nextJobRow(final int from) {
		for (int r = from + 1; r < jobModel.getRowCount(); r++) {
			if (!jobModel.isHeader(r)) {
				return r;
			}
		}
		return -1;
	}

	private int prevJobRow(final int from) {
		for (int r = from - 1; r >= 0; r--) {
			if (!jobModel.isHeader(r)) {
				return r;
			}
		}
		return -1;
	}

	void setJobs(final List<Job> jobs) {
		final String previouslySelected = selectedId();
		jobModel.setJobs(jobs);
		if (previouslySelected != null) {
			selectId(previouslySelected);
		}
	}

	String selectedId() {
		final Job job = selectedJob();
		return job == null ? null : job.id();
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
		if (getSelectedRow() < 0) {
			final int first = jobModel.firstJobRow();
			if (first >= 0) {
				getSelectionModel().setSelectionInterval(first, first);
			}
		}
	}

	/** A muted top divider line, used to set off each section header. */
	private static Border headerBorder(final int left) {
		Color line = UIManager.getColor("Separator.foreground");
		if (line == null) {
			line = UIManager.getColor("controlShadow");
		}
		if (line == null) {
			line = Color.GRAY;
		}
		return BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, line),
				BorderFactory.createEmptyBorder(4, left, 2, 0));
	}

	/** Column 0: a section header, or a status dot followed by the job's display name. */
	private static final class NameRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value,
				final boolean isSelected, final boolean hasFocus, final int row, final int column) {
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if (value instanceof JobTableModel.Header header) {
				setIcon(null);
				setText(header.title() + "    " + header.count());
				setFont(getFont().deriveFont(Font.BOLD));
				setForeground(UIManager.getColor("Label.disabledForeground"));
				setBorder(headerBorder(8));
				return this;
			}
			setFont(getFont().deriveFont(Font.PLAIN));   // reset: the renderer is reused across rows
			if (value instanceof Job job) {
				setText(job.displayName());
				setIcon(StatusVisuals.icon(job));
				setIconTextGap(8);
			}
			setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
			return this;
		}
	}

	/** Column 1: the run-state label (or "Scheduled") colored by state, or blank on a section header. */
	private static final class StateRenderer extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(final JTable table, final Object value,
				final boolean isSelected, final boolean hasFocus, final int row, final int column) {
			super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			if (value instanceof Job job) {
				setText(StatusVisuals.label(job));
				setBorder(BorderFactory.createEmptyBorder());
				// Color the label by state, but let the L&F own the selected row's foreground.
				if (!isSelected) {
					setForeground(StatusVisuals.color(job));
				}
			} else {
				setText("");   // section header: continue its divider line across this column
				setBorder(headerBorder(0));
			}
			return this;
		}
	}
}
