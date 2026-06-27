package com.u1.servicepal.internal.exec;

import com.u1.servicepal.ServiceException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Runs commands via {@link ProcessBuilder}. Reads stdout/stderr fully (commands here are
 * small and short-lived). */
public final class DefaultCommandRunner implements CommandRunner {

	@Override
	public CommandResult run(final List<String> command) {
		final ProcessBuilder pb = new ProcessBuilder(command);
		pb.redirectErrorStream(false);
		try {
			final Process process = pb.start();
			final String stdout = readFully(process.getInputStream());
			final String stderr = readFully(process.getErrorStream());
			final int exit = process.waitFor();
			return new CommandResult(exit, stdout, stderr);
		} catch (final IOException e) {
			throw new ServiceException("failed to run command " + command, e);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ServiceException("interrupted running command " + command, e);
		}
	}

	private static String readFully(final InputStream in) throws IOException {
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
