package com.u1.servicepal.gui;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.ServiceSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, display-free translation between the GUI's {@link JobForm} and the library's
 * {@link ServiceSpec}. Kept apart from the Swing code so it unit-tests with no display.
 *
 * <p>This is where the GUI's one piece of cross-platform policy lives — the <em>auto privilege
 * model</em>: install per-user where the platform supports it (no admin needed; macOS and
 * systemd), and fall back to a system daemon where it does not (Windows and OpenRC, which are
 * system-wide only). Because of this, the rest of the GUI never has to mention {@code RunAs} or
 * {@code Installation}; the UI is identical on every platform.
 */
public final class JobSpecs {

	private JobSpecs() {
	}

	/** Build a spec from a form, choosing the run-as identity from the platform's capabilities. */
	public static ServiceSpec fromForm(final JobForm form, final Capabilities caps) {
		final List<String> command = new ArrayList<>();
		command.add(form.command() == null ? "" : form.command().trim());
		command.addAll(tokenize(form.arguments()));

		final ServiceSpec.Builder b = ServiceSpec.builder().command(command);
		if (form.scheduled()) {
			// A scheduled job runs on its schedule: it is not a kept-running daemon, so it does not
			// run at load and has no keep-alive (the builder also forbids schedule + RestartPolicy
			// .ALWAYS). The schedule itself is the trigger; arming it is the GUI's "enable".
			b.schedule(form.schedule()).autoStart(false).restart(RestartPolicy.NEVER);
		} else {
			b.autoStart(form.autoStart()).restart(form.restart());
		}
		if (form.id() != null && !form.id().isBlank()) {
			b.id(form.id());
		}
		final String name = form.name() == null ? "" : form.name().trim();
		if (!name.isEmpty()) {
			b.displayName(name);
		}
		final String folder = form.folder() == null ? "" : form.folder().trim();
		if (!folder.isEmpty()) {
			b.workingDirectory(Path.of(folder));
		}
		applyIdentity(b, caps);
		return b.build();
	}

	/** Auto privilege model: per-user where supported, otherwise a system daemon. */
	static void applyIdentity(final ServiceSpec.Builder b, final Capabilities caps) {
		if (caps.perUserInstall()) {
			b.asCurrentUser();
		} else {
			b.asSystemDaemon();
		}
	}

	/** Split an argument string on whitespace, honoring simple double-quoted segments. */
	static List<String> tokenize(final String s) {
		final List<String> out = new ArrayList<>();
		if (s == null) {
			return out;
		}
		final StringBuilder cur = new StringBuilder();
		boolean inQuote = false;
		boolean has = false;
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			if (c == '"') {
				inQuote = !inQuote;
				has = true;
			} else if (Character.isWhitespace(c) && !inQuote) {
				if (has) {
					out.add(cur.toString());
					cur.setLength(0);
					has = false;
				}
			} else {
				cur.append(c);
				has = true;
			}
		}
		if (has) {
			out.add(cur.toString());
		}
		return out;
	}
}
