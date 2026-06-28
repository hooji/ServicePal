package com.u1.servicepal.internal.openrc;

import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.ServiceSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses an OpenRC init script (POSIX shell sourcing {@code openrc-run}) into our model.
 * Best-effort: only the shell variables we render are recognized (the verbatim text is always
 * available via {@code readNative}). Our two marker comments are folded into the same map under
 * {@link #MANAGED_MARKER} / {@link #RUNLEVEL_MARKER} so callers can treat them uniformly.
 */
public final class OpenRcScriptReader {

	/** Marker comment stamped into scripts we manage. */
	public static final String MANAGED_MARKER = "X-ServicePal-Managed";

	/** Marker comment set when we install over a service we did not create. */
	public static final String ADOPTED_MARKER = "X-ServicePal-Adopted";

	/** Marker comment recording the runlevel chosen at install (for enable/disable). */
	public static final String RUNLEVEL_MARKER = "X-ServicePal-Runlevel";

	/** Runlevel used when a script carries no {@link #RUNLEVEL_MARKER}. */
	public static final String DEFAULT_RUNLEVEL = "default";

	public Map<String, String> parseFile(final Path file) {
		try {
			return parse(Files.readString(file));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read script " + file, e);
		}
	}

	/**
	 * Read a script into a flat key&rarr;value map: shell {@code KEY=value} assignments (with the
	 * value dequoted, and an optional {@code export } prefix stripped) plus our marker comments.
	 * Lines we can't classify (the shebang, {@code depend()}, braces) are ignored.
	 */
	public Map<String, String> parse(final String text) {
		final Map<String, String> values = new LinkedHashMap<>();
		for (final String raw : text.split("\n")) {
			final String line = raw.strip();
			if (line.startsWith("#")) {
				captureMarker(line.substring(1).strip(), values);
				continue;
			}
			if (line.isEmpty()) {
				continue;
			}
			String assignment = line;
			if (assignment.startsWith("export ")) {
				assignment = assignment.substring("export ".length()).strip();
			}
			final int eq = assignment.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			final String key = assignment.substring(0, eq).strip();
			if (!isIdentifier(key)) {
				continue;   // e.g. "depend() {" or other shell — not a simple assignment
			}
			values.put(key, dequote(assignment.substring(eq + 1).strip()));
		}
		return values;
	}

	private static void captureMarker(final String comment, final Map<String, String> values) {
		final int colon = comment.indexOf(':');
		if (colon <= 0) {
			return;
		}
		final String key = comment.substring(0, colon).strip();
		if (key.equals(MANAGED_MARKER) || key.equals(RUNLEVEL_MARKER)
				|| key.equals(ADOPTED_MARKER)) {
			values.put(key, comment.substring(colon + 1).strip());
		}
	}

	public boolean isManaged(final Map<String, String> script) {
		return script.containsKey(MANAGED_MARKER);
	}

	/** Whether we manage this script but did not originally create it. */
	public boolean isAdopted(final Map<String, String> script) {
		return script.containsKey(ADOPTED_MARKER);
	}

	/** The runlevel recorded in the script, or {@link #DEFAULT_RUNLEVEL} if none. */
	public String runlevel(final Map<String, String> script) {
		final String value = script.get(RUNLEVEL_MARKER);
		return (value == null || value.isBlank()) ? DEFAULT_RUNLEVEL : value;
	}

	/**
	 * Map a parsed script into a {@link ServiceSpec}. {@code autoStart} is supplied by the
	 * backend (it is a filesystem fact — whether the service is wired into a runlevel — not
	 * something the script itself records).
	 */
	public ServiceSpec toSpec(final Map<String, String> script, final Installation installation,
			final String id, final boolean autoStart) {
		final ServiceSpec.Builder b = ServiceSpec.builder().id(id);

		final String description = script.get("description");
		if (description != null) {
			b.displayName(description);
		}

		b.command(commandOf(script));

		final String user = script.get("command_user");
		if (user != null && !user.isBlank()) {
			final int colon = user.indexOf(':');
			b.runAs(RunAs.namedUser(colon >= 0 ? user.substring(0, colon) : user));
		} else {
			b.asSystemDaemon();   // OpenRC is SYSTEM_WIDE; no command_user means root
		}

		final String dir = script.get("directory");
		if (dir != null) {
			b.workingDirectory(Path.of(dir));
		}
		b.restart(restartOf(script));
		b.autoStart(autoStart);
		return b.build();
	}

	private static List<String> commandOf(final Map<String, String> script) {
		final String command = script.get("command");
		if (command == null || command.isBlank()) {
			throw new DefinitionIOException("script has no command", null);
		}
		final List<String> parts = new ArrayList<>();
		parts.add(command);
		final String args = script.get("command_args");
		if (args != null && !args.isBlank()) {
			for (final String token : args.split("\\s+")) {
				if (!token.isEmpty()) {
					parts.add(token);
				}
			}
		}
		return parts;
	}

	private static RestartPolicy restartOf(final Map<String, String> script) {
		final String supervisor = script.get("supervisor");
		if (supervisor != null && supervisor.contains("supervise-daemon")) {
			// We can't perfectly recover ON_FAILURE vs ALWAYS (supervise-daemon respawns on any
			// exit); treat an explicit unlimited respawn as ALWAYS, everything else as ON_FAILURE.
			return "0".equals(script.get("respawn_max")) ? RestartPolicy.ALWAYS
					: RestartPolicy.ON_FAILURE;
		}
		return RestartPolicy.NEVER;
	}

	private static boolean isIdentifier(final String key) {
		if (key.isEmpty()) {
			return false;
		}
		for (int i = 0; i < key.length(); i++) {
			final char c = key.charAt(i);
			final boolean ok = c == '_' || Character.isLetterOrDigit(c);
			if (!ok || (i == 0 && Character.isDigit(c))) {
				return false;
			}
		}
		return true;
	}

	private static String dequote(final String value) {
		if (value.length() >= 2 && value.charAt(0) == '"'
				&& value.charAt(value.length() - 1) == '"') {
			final String inner = value.substring(1, value.length() - 1);
			final StringBuilder sb = new StringBuilder(inner.length());
			for (int i = 0; i < inner.length(); i++) {
				final char c = inner.charAt(i);
				if (c == '\\' && i + 1 < inner.length()) {
					final char next = inner.charAt(i + 1);
					if (next == '\\' || next == '"' || next == '$' || next == '`') {
						sb.append(next);
						i++;
						continue;
					}
				}
				sb.append(c);
			}
			return sb.toString();
		}
		return value;
	}
}
