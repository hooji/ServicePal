package com.u1.servicepal.internal.macos;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import com.u1.servicepal.model.RunState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads live service state with domain-targeted {@code launchctl print <domain>/<label>}.
 *
 * <p>The legacy {@code launchctl list} only enumerates the caller's session domain, so it never
 * sees {@code system}-domain daemons (even under {@code sudo} from a terminal, which keeps the
 * GUI session's bootstrap). {@code print} takes an explicit domain target, so it reads the
 * right one. The {@code system} domain still requires root; without it we honestly report
 * {@code UNKNOWN} rather than guessing {@code STOPPED}.
 *
 * <p>Mutation (which will use the modern bootstrap/kickstart subcommands) arrives in step 4.
 */
public final class DefaultLaunchctl implements Launchctl {

	private final CommandRunner runner;
	private String uid;
	private boolean uidResolved;
	private Boolean root;

	public DefaultLaunchctl(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public ServiceRuntime runtime(final LaunchdDomain domain, final String label) {
		final String target;
		if (domain == LaunchdDomain.SYSTEM) {
			if (!isRoot()) {
				// Can't read the system domain without root; don't pretend it's stopped.
				return ServiceRuntime.unknown();
			}
			target = "system/" + label;
		} else {
			final String u = uid();
			if (u == null) {
				return ServiceRuntime.unknown();
			}
			target = "gui/" + u + "/" + label;
		}

		final CommandResult res = runner.run(List.of("launchctl", "print", target));
		if (res.ok()) {
			return parsePrint(res.stdout());
		}
		final String message = ((res.stdout() == null ? "" : res.stdout())
				+ (res.stderr() == null ? "" : res.stderr())).toLowerCase(Locale.ROOT);
		if (message.contains("could not find")) {
			// The domain is observable and the service simply isn't loaded there.
			return new ServiceRuntime(RunState.STOPPED, null, null);
		}
		return ServiceRuntime.unknown();
	}

	@Override
	public void bootstrap(final LaunchdDomain domain, final Path plist) {
		mutate(List.of("launchctl", "bootstrap", domainTarget(domain), plist.toString()));
	}

	@Override
	public void bootout(final LaunchdDomain domain, final String label) {
		mutate(List.of("launchctl", "bootout", serviceTarget(domain, label)));
	}

	@Override
	public void kickstart(final LaunchdDomain domain, final String label, final boolean restart) {
		final List<String> cmd = new ArrayList<>(List.of("launchctl", "kickstart"));
		if (restart) {
			cmd.add("-k");
		}
		cmd.add(serviceTarget(domain, label));
		mutate(cmd);
	}

	@Override
	public void killService(final LaunchdDomain domain, final String label, final String signal) {
		mutate(List.of("launchctl", "kill", signal, serviceTarget(domain, label)));
	}

	@Override
	public void enable(final LaunchdDomain domain, final String label) {
		mutate(List.of("launchctl", "enable", serviceTarget(domain, label)));
	}

	@Override
	public void disable(final LaunchdDomain domain, final String label) {
		mutate(List.of("launchctl", "disable", serviceTarget(domain, label)));
	}

	private void mutate(final List<String> command) {
		final CommandResult res = runner.run(command);
		if (!res.ok()) {
			throw new NativeCommandException(command, res.exitCode(), res.stderr());
		}
	}

	private String domainTarget(final LaunchdDomain domain) {
		if (domain == LaunchdDomain.SYSTEM) {
			return "system";
		}
		final String u = uid();
		if (u == null) {
			throw new com.u1.servicepal.ServiceException(
					"cannot resolve uid for the gui domain");
		}
		return "gui/" + u;
	}

	private String serviceTarget(final LaunchdDomain domain, final String label) {
		return domainTarget(domain) + "/" + label;
	}

	/** Parse the key lines of {@code launchctl print} output. Package-visible for testing. */
	static ServiceRuntime parsePrint(final String out) {
		if (out == null) {
			return ServiceRuntime.unknown();
		}
		String stateWord = null;
		Integer pid = null;
		Integer lastExit = null;
		for (final String raw : out.split("\n")) {
			final String line = raw.strip();
			if (line.startsWith("state = ")) {
				stateWord = line.substring("state = ".length()).strip();
			} else if (line.startsWith("pid = ")) {
				pid = parseIntOrNull(line.substring("pid = ".length()));
			} else if (line.startsWith("last exit code = ")) {
				lastExit = parseIntOrNull(line.substring("last exit code = ".length()));
			}
		}
		// A live `pid = ` in `launchctl print` means launchd is tracking a running process — the
		// authoritative "is it running now?" signal. macOS 26 (Tahoe) changed the `state = ...`
		// wording, so an exact "running" match is unreliable: trust the pid when present, and fall
		// back to a lenient state-word check only when there is no pid.
		final RunState state;
		if (pid != null) {
			state = RunState.RUNNING;
		} else if (stateWord != null) {
			state = stateWord.startsWith("running") ? RunState.RUNNING : RunState.STOPPED;
		} else {
			state = RunState.STOPPED;
		}
		return new ServiceRuntime(state, pid, lastExit);
	}

	private static Integer parseIntOrNull(final String s) {
		final String t = s.strip();
		try {
			return Integer.valueOf(t);
		} catch (final NumberFormatException e) {
			return null;
		}
	}

	private String uid() {
		if (!uidResolved) {
			final CommandResult r = runner.run(List.of("id", "-u"));
			uid = (r.ok() && r.stdout() != null) ? r.stdout().strip() : null;
			uidResolved = true;
		}
		return uid;
	}

	private boolean isRoot() {
		if (root == null) {
			root = "0".equals(uid());
		}
		return root;
	}
}
