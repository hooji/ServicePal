package com.u1.servicepal.gui;

import com.u1.servicepal.model.RestartPolicy;

/**
 * The plain values collected by the add/edit form, before they are translated into a
 * {@link com.u1.servicepal.model.ServiceSpec} by {@link JobSpecs}. A {@code null}/blank
 * {@code id} means "new job" (the library generates one); a non-null id edits in place.
 *
 * @param id        the existing service id when editing, or {@code null} for a new job
 * @param name      the friendly display name
 * @param command   the program to run (an executable path)
 * @param arguments command-line arguments, whitespace-separated (simple quotes honored)
 * @param folder    the working directory, or blank for none
 * @param autoStart start automatically at login/boot (and start now on save)
 * @param restart   what to do if the process stops
 */
public record JobForm(
		String id,
		String name,
		String command,
		String arguments,
		String folder,
		boolean autoStart,
		RestartPolicy restart) {
}
