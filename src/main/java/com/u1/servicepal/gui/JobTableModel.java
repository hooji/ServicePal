package com.u1.servicepal.gui;

import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/** Backs the master job list: one row per job, column 0 = the {@link Job}, column 1 = its state. */
final class JobTableModel extends AbstractTableModel {

	private final List<Job> jobs = new ArrayList<>();

	void setJobs(final List<Job> newJobs) {
		jobs.clear();
		jobs.addAll(newJobs);
		fireTableDataChanged();
	}

	Job jobAt(final int row) {
		return jobs.get(row);
	}

	int indexOfId(final String id) {
		for (int i = 0; i < jobs.size(); i++) {
			if (jobs.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	@Override
	public int getRowCount() {
		return jobs.size();
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
		final Job job = jobs.get(rowIndex);
		return columnIndex == 0 ? job : job.status().state();
	}

	@Override
	public Class<?> getColumnClass(final int columnIndex) {
		return columnIndex == 0 ? Job.class : Object.class;
	}

	@Override
	public boolean isCellEditable(final int rowIndex, final int columnIndex) {
		return false;
	}
}
