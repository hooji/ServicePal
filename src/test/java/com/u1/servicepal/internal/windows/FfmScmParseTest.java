package com.u1.servicepal.internal.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.u1.servicepal.model.RunState;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link FfmScm#parseEnumeration} against a hand-built {@code ENUM_SERVICE_STATUS_PROCESSW}
 * buffer. This validates the struct offsets and the relativized {@code lpServiceName} pointer-follow
 * off-Windows (no {@code advapi32}), so only the real {@code EnumServicesStatusExW} call needs the
 * windows runner. The layout matches 64-bit Windows: two 8-byte LPWSTR pointers, then a 36-byte
 * {@code SERVICE_STATUS_PROCESS}, tail-padded to a 56-byte stride.
 */
class FfmScmParseTest {

	private static final long ENTRY = 56;
	private static final long OFF_STATE = 16 + 4;     // status base (after 2 pointers) + dwCurrentState
	private static final long OFF_EXIT = 16 + 12;     // + dwWin32ExitCode
	private static final long OFF_PID = 16 + 28;      // + dwProcessId

	@Test
	void parsesNameStateAndPidFromTheBuffer() {
		try (Arena a = Arena.ofConfined()) {
			final byte[] s0 = wide("Spooler");
			final byte[] s1 = wide("W32Time");
			final long str0 = ENTRY * 2;               // strings packed after the two entries
			final long str1 = str0 + s0.length;
			final long total = str1 + s1.length;
			final MemorySegment buf = a.allocate(total, ValueLayout.ADDRESS.byteSize());
			final long base = buf.address();

			// entry 0 — a running service with a pid
			buf.set(ValueLayout.ADDRESS, 0, MemorySegment.ofAddress(base + str0));
			buf.set(ValueLayout.JAVA_INT, OFF_STATE, ServiceControlStatus.SERVICE_RUNNING);
			buf.set(ValueLayout.JAVA_INT, OFF_PID, 1234);
			// entry 1 — a stopped service (pid 0 -> null) with an exit code
			buf.set(ValueLayout.ADDRESS, ENTRY, MemorySegment.ofAddress(base + str1));
			buf.set(ValueLayout.JAVA_INT, ENTRY + OFF_STATE, ServiceControlStatus.SERVICE_STOPPED);
			buf.set(ValueLayout.JAVA_INT, ENTRY + OFF_EXIT, 5);
			buf.set(ValueLayout.JAVA_INT, ENTRY + OFF_PID, 0);

			MemorySegment.copy(s0, 0, buf, ValueLayout.JAVA_BYTE, str0, s0.length);
			MemorySegment.copy(s1, 0, buf, ValueLayout.JAVA_BYTE, str1, s1.length);

			final List<ScmService> out = FfmScm.parseEnumeration(buf, 2);

			assertEquals(2, out.size());
			assertEquals("Spooler", out.get(0).name());
			assertEquals(RunState.RUNNING, out.get(0).status().state());
			assertEquals(Integer.valueOf(1234), out.get(0).status().pid());
			assertEquals("W32Time", out.get(1).name());
			assertEquals(RunState.STOPPED, out.get(1).status().state());
			assertNull(out.get(1).status().pid(), "a stopped service reports no pid");
		}
	}

	@Test
	void parsesAnEmptyBuffer() {
		try (Arena a = Arena.ofConfined()) {
			assertEquals(List.of(), FfmScm.parseEnumeration(a.allocate(8, 8), 0));
		}
	}

	/** A NUL-terminated UTF-16LE (WCHAR) string, as Windows stores service names. */
	private static byte[] wide(final String s) {
		return (s + "\0").getBytes(StandardCharsets.UTF_16LE);
	}
}
