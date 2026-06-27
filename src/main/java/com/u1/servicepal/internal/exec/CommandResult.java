package com.u1.servicepal.internal.exec;

/** The outcome of running a native command. */
public record CommandResult(int exitCode, String stdout, String stderr) {

	public boolean ok() {
		return exitCode == 0;
	}
}
