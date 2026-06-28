package com.u1.servicepal.gui;

import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/**
 * Backs the master job list. Each row is either a {@link Header} (a non-selectable section divider)
 * or a {@link Job}. Jobs are split into up to three sections — the ones ServicePal created, the ones
 * it adopted (installed over but did not create), then everything else discovered on the machine.
 * Column 0 carries the row object (a {@link Header} or a {@link Job}); column 1 carries the run
 * state (blank for a header).
 */
final class JobTableModel extends AbstractTableModel {

	/** A non-selectable section header row. */
	record Header(String title, int count) {
	}

	private static final String CREATED = "Created with ServicePal";
	private static final String ADOPTED = "Adopted by ServicePal";
	private static final String OTHERS = "Other background jobs";

	private final List<Object> rows = new ArrayList<>();   // each element is a Header or a Job

	void setJobs(final List<Job> newJobs) {
		rows.clear();
		final List<Job> created = new ArrayList<>();
		final List<Job> adopted = new ArrayList<>();
		final List<Job> others = new ArrayList<>();
		for (final Job job : newJobs) {
			if (!job.managed()) {
				others.add(job);
			} else if (job.adopted()) {
				adopted.add(job);
			} else {
				created.add(job);
			}
		}
		addSection(CREATED, created);
		addSection(ADOPTED, adopted);
		addSection(OTHERS, others);
		fireTableDataChanged();
	}

	private void addSection(final String title, final List<Job> jobs) {
		if (!jobs.isEmpty()) {
			rows.add(new Header(title, jobs.size()));
			rows.addAll(jobs);
		}
	}

	boolean isHeader(final int row) {
		return row >= 0 && row < rows.size() && rows.get(row) instanceof Header;
	}

	/** The job at {@code row}, or {@code null} if that row is a section header. */
	Job jobAt(final int row) {
		return rows.get(row) instanceof Job job ? job : null;
	}

	/** Index of the first job row (skipping the leading header), or -1 if there are no jobs. */
	int firstJobRow() {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i) instanceof Job) {
				return i;
			}
		}
		return -1;
	}

	int indexOfId(final String id) {
		for (int i = 0; i < rows.size(); i++) {
			if (rows.get(i) instanceof Job job && job.id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public int getRowCount() {
		return rows.size();
	}

	@Override
	public int getColumnCount() {
		return 2;
	}

	@Override
	public String getColumnName(final int column) {
		return column == 0 ? "Name" : "Status";
	}

	@Override
	public Object getValueAt(final int rowIndex, final int columnIndex) {
		final Object row = rows.get(rowIndex);
		if (row instanceof Header header) {
			return columnIndex == 0 ? header : "";
		}
		final Job job = (Job) row;
		return columnIndex == 0 ? job : job.status().state();
	}

	@Override
	public Class<?> getColumnClass(final int columnIndex) {
		return Object.class;
	}

	@Override
	public boolean isCellEditable(final int rowIndex, final int columnIndex) {
		return false;
	}
}
