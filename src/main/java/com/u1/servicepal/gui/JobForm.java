package com.u1.servicepal.gui;

import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.Schedule;

/**
 * The plain values collected by the add/edit form, before they are translated into a
 * {@link com.u1.servicepal.model.ServiceSpec} by {@link JobSpecs}. A {@code null}/blank
 * {@code id} means "new job" (the library generates one); a non-null id edits in place.
 *
 * <p>A job is in one of two modes. A {@code null} {@code schedule} is a <em>kept-running</em> job:
 * {@code autoStart} and {@code restart} apply. A non-null {@code schedule} is a <em>scheduled</em>
 * job: it runs on its schedule, so {@code autoStart}/{@code restart} are ignored ({@link JobSpecs}
 * normalizes them).
 *
 * @param id        the existing service id when editing, or {@code null} for a new job
 * @param name      the friendly display name
 * @param command   the program to run (an executable path)
 * @param arguments command-line arguments, whitespace-separated (simple quotes honored)
 * @param folder    the working directory, or blank for none
 * @param autoStart start automatically at login/boot (and start now on save) — kept-running mode
 * @param restart   what to do if the process stops — kept-running mode
 * @param schedule  when to run, or {@code null} for a kept-running job
 */
public record JobForm(
		String id,
		String name,
		String command,
		String arguments,
		String folder,
		boolean autoStart,
		RestartPolicy restart,
		Schedule schedule) {

	/** Convenience for a kept-running ("keep it running") job, with no schedule. */
	public JobForm(final String id, final String name, final String command, final String arguments,
			final String folder, final boolean autoStart, final RestartPolicy restart) {
		this(id, name, command, arguments, folder, autoStart, restart, null);
	}

	/** Whether this form describes a scheduled job (vs. a kept-running one). */
	public boolean scheduled() {
		return schedule != null;
	}
}
