package com.u1.servicepal.internal.windows;

/**
 * The Task Scheduler seam (stub in tests). Scheduled jobs ({@code spec.schedule() != null}) go
 * here instead of the SCM, because a scheduled task's action is a plain {@code CreateProcess} of
 * any command — no SCM control protocol, so no service host is needed. Backed by
 * {@code schtasks.exe} ({@link SchtasksScheduler}); a {@code RecordingTaskScheduler} fake lets
 * the backend unit-test off-Windows.
 */
public interface TaskScheduler {

	boolean exists(String name);

	/**
	 * Register a task from a full XML definition.
	 *
	 * @param name     the task name
	 * @param xml      the Task Scheduler XML
	 * @param account  run-as account ({@code null} = the XML principal, e.g. SYSTEM)
	 * @param password the account password ({@code null} unless a named user needs one)
	 */
	void create(String name, String xml, String account, String password);

	void delete(String name);

	/** Run the task now. */
	void run(String name);

	/** Stop a running task. */
	void end(String name);

	/** Enable or disable the task (boot/trigger persistence). */
	void setEnabled(String name, boolean enabled);

	/** Is an instance of the task currently running? */
	boolean isRunning(String name);

	/** The task's next + last run times (best-effort; {@link TaskRunTimes#UNKNOWN} if unavailable). */
	TaskRunTimes runTimes(String name);
}
