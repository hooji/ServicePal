package com.u1.servicepal;

import java.util.List;

/** A native command (launchctl / systemctl / rc-service / sc) failed. Carries diagnostics. */
public final class NativeCommandException extends ServiceException {

	private static final long serialVersionUID = 1L;

	private final List<String> command;
	private final int exitCode;
	private final String stderr;

	public NativeCommandException(final List<String> command, final int exitCode,
			final String stderr) {
		super("command " + command + " failed with exit code " + exitCode
				+ (stderr == null || stderr.isBlank() ? "" : ": " + stderr.strip()));
		this.command = List.copyOf(command);
		this.exitCode = exitCode;
		this.stderr = stderr;
	}

	public List<String> command() {
		return command;
	}

	public int exitCode() {
		return exitCode;
	}

	public String stderr() {
		return stderr;
	}
}
