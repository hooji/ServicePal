package com.u1.servicepal.gui;

/**
 * The user-invokable actions, shared by the toolbar and the detail panel and implemented by the
 * {@link JobsController}. Lifecycle actions operate on the currently selected job.
 */
interface JobActions {

	void addJob();

	void editSelected();

	void removeSelected();

	void startSelected();

	void stopSelected();

	void restartSelected();

	void refresh();
}
