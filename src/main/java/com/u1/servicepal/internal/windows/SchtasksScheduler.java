package com.u1.servicepal.internal.windows;

import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.internal.exec.CommandResult;
import com.u1.servicepal.internal.exec.CommandRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Drives {@code schtasks.exe} via subprocess. */
public final class SchtasksScheduler implements TaskScheduler {

	private final CommandRunner runner;

	public SchtasksScheduler(final CommandRunner runner) {
		this.runner = runner;
	}

	@Override
	public boolean exists(final String name) {
		return runner.run(List.of("schtasks", "/query", "/tn", name)).ok();
	}

	@Override
	public void create(final String name, final String xml, final String account,
			final String password) {
		final Path file = writeTempXml(xml);
		try {
			final List<String> cmd = new ArrayList<>(List.of(
					"schtasks", "/create", "/tn", name, "/xml", file.toString(), "/f"));
			if (account != null) {
				cmd.add("/ru");
				cmd.add(account);
				if (password != null) {
					cmd.add("/rp");
					cmd.add(password);
				}
			}
			mutate(cmd);
		} finally {
			try {
				Files.deleteIfExists(file);
			} catch (final IOException ignored) {
				// temp file cleanup is best-effort
			}
		}
	}

	@Override
	public void delete(final String name) {
		mutate(List.of("schtasks", "/delete", "/tn", name, "/f"));
	}

	@Override
	public void run(final String name) {
		mutate(List.of("schtasks", "/run", "/tn", name));
	}

	@Override
	public void end(final String name) {
		mutate(List.of("schtasks", "/end", "/tn", name));
	}

	@Override
	public void setEnabled(final String name, final boolean enabled) {
		mutate(List.of("schtasks", "/change", "/tn", name, enabled ? "/enable" : "/disable"));
	}

	@Override
	public boolean isRunning(final String name) {
		final CommandResult res = runner.run(
				List.of("schtasks", "/query", "/tn", name, "/fo", "list"));
		if (!res.ok() || res.stdout() == null) {
			return false;
		}
		for (final String raw : res.stdout().split("\n")) {
			final String line = raw.strip().toLowerCase(Locale.ROOT);
			if (line.startsWith("status:") && line.contains("running")) {
				return true;
			}
		}
		return false;
	}

	@Override
	public TaskRunTimes runTimes(final String name) {
		// PowerShell's Get-ScheduledTaskInfo exposes NextRunTime/LastRunTime as DateTime; emit them
		// as round-trip ISO-8601 (UTC) so parsing is locale-independent (unlike schtasks /v text).
		final String escaped = name.replace("'", "''");
		final String script = "$ErrorActionPreference='SilentlyContinue';"
				+ " $i = Get-ScheduledTaskInfo -TaskName '" + escaped + "';"
				+ " if ($i) {"
				+ "  if ($i.NextRunTime) { 'NEXT=' + $i.NextRunTime.ToUniversalTime().ToString('o') };"
				+ "  if ($i.LastRunTime -and $i.LastRunTime.Year -gt 1999) {"
				+ "   'LAST=' + $i.LastRunTime.ToUniversalTime().ToString('o') } }";
		final CommandResult res = runner.run(
				List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script));
		if (!res.ok() || res.stdout() == null) {
			return TaskRunTimes.UNKNOWN;
		}
		Instant next = null;
		Instant last = null;
		for (final String raw : res.stdout().split("\n")) {
			final String line = raw.strip();
			if (line.startsWith("NEXT=")) {
				next = parseInstant(line.substring("NEXT=".length()));
			} else if (line.startsWith("LAST=")) {
				last = parseInstant(line.substring("LAST=".length()));
			}
		}
		return new TaskRunTimes(next, last);
	}

	private static Instant parseInstant(final String iso) {
		try {
			return Instant.parse(iso.strip());
		} catch (final DateTimeParseException e) {
			return null;
		}
	}

	/** Task Scheduler expects UTF-16; write the XML to a temp file with a UTF-16LE BOM. */
	private static Path writeTempXml(final String xml) {
		try {
			final Path file = Files.createTempFile("servicepal-task-", ".xml");
			final byte[] body = xml.getBytes(StandardCharsets.UTF_16LE);
			final byte[] out = new byte[body.length + 2];
			out[0] = (byte) 0xFF;   // UTF-16LE byte-order mark
			out[1] = (byte) 0xFE;
			System.arraycopy(body, 0, out, 2, body.length);
			Files.write(file, out);
			return file;
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write temp task XML", e);
		}
	}

	private void mutate(final List<String> command) {
		final CommandResult res = runner.run(command);
		if (!res.ok()) {
			throw new NativeCommandException(command, res.exitCode(), res.stderr());
		}
	}
}
