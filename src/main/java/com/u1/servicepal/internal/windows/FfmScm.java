package com.u1.servicepal.internal.windows;

import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.ServiceException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link Scm} backed by {@code advapi32.dll} through the Foreign Function &amp; Memory API. Hand-
 * written bindings for the ~10 SCM functions we need (cheaper than a jextract build dependency).
 * Compiles on any platform — only construction (the {@code Advapi32.dll} lookup) and the calls
 * run on Windows, and only {@code createDefault()} constructs this (tests use a stub), so the
 * rest of the library still builds and unit-tests off-Windows.
 *
 * <p>Each call captures {@code GetLastError} so failures carry the Win32 error code (e.g. 5 =
 * access denied, 1072 = marked for delete). Service handles are always closed.
 */
public final class FfmScm implements Scm {

	// --- dwDesiredAccess (SCM) ---
	private static final int SC_MANAGER_CONNECT = 0x0001;
	private static final int SC_MANAGER_CREATE_SERVICE = 0x0002;
	private static final int SC_MANAGER_ENUMERATE_SERVICE = 0x0004;

	// --- EnumServicesStatusExW parameters ---
	private static final int SC_ENUM_PROCESS_INFO = 0;
	private static final int SERVICE_WIN32 = 0x00000030;     // own-process | share-process (not drivers)
	private static final int SERVICE_STATE_ALL = 0x00000003;  // active | inactive
	private static final int ERROR_MORE_DATA = 234;
	private static final int MAX_ENUM_ITERATIONS = 10_000;   // defensive cap (machines have ~hundreds)

	// --- dwDesiredAccess (service) ---
	private static final int SERVICE_QUERY_STATUS = 0x0004;
	private static final int SERVICE_START = 0x0010;
	private static final int SERVICE_STOP = 0x0020;
	private static final int SERVICE_CHANGE_CONFIG = 0x0002;
	private static final int DELETE = 0x00010000;

	// --- create parameters ---
	private static final int SERVICE_WIN32_OWN_PROCESS = 0x00000010;
	private static final int SERVICE_ERROR_NORMAL = 0x00000001;
	private static final int SERVICE_NO_CHANGE = 0xFFFFFFFF;

	// --- control / config ---
	private static final int SERVICE_CONTROL_STOP = 0x00000001;
	private static final int SC_STATUS_PROCESS_INFO = 0;
	private static final int SERVICE_CONFIG_DESCRIPTION = 1;
	private static final int SERVICE_CONFIG_DELAYED_AUTO_START_INFO = 3;
	private static final String LOCAL_SYSTEM = "LocalSystem";

	// --- error codes we treat specially ---
	private static final int ERROR_SERVICE_DOES_NOT_EXIST = 1060;
	private static final int ERROR_SERVICE_ALREADY_RUNNING = 1056;
	private static final int ERROR_SERVICE_NOT_ACTIVE = 1062;

	// SERVICE_STATUS_PROCESS field offsets (all DWORDs, no padding).
	private static final long OFF_CURRENT_STATE = 4;
	private static final long OFF_WIN32_EXIT = 12;
	private static final long OFF_PROCESS_ID = 28;
	private static final long SERVICE_STATUS_PROCESS_SIZE = 36;
	private static final long SERVICE_STATUS_SIZE = 28;

	private final Arena arena = Arena.ofShared();   // keeps Advapi32 loaded + handles valid
	private final Linker linker = Linker.nativeLinker();
	private final MemoryLayout captureLayout = Linker.Option.captureStateLayout();
	private final VarHandle lastErrorHandle =
			captureLayout.varHandle(MemoryLayout.PathElement.groupElement("GetLastError"));

	private final MethodHandle openSCManager;
	private final MethodHandle createService;
	private final MethodHandle openService;
	private final MethodHandle startService;
	private final MethodHandle controlService;
	private final MethodHandle deleteService;
	private final MethodHandle changeServiceConfig;
	private final MethodHandle changeServiceConfig2;
	private final MethodHandle queryServiceStatusEx;
	private final MethodHandle enumServicesStatusEx;
	private final MethodHandle closeServiceHandle;

	public FfmScm() {
		final SymbolLookup advapi32 = SymbolLookup.libraryLookup("Advapi32.dll", arena);
		final Linker.Option capture = Linker.Option.captureCallState("GetLastError");
		final ValueLayout.OfInt i = ValueLayout.JAVA_INT;
		final AddressLayout p = ValueLayout.ADDRESS;

		openSCManager = handle(advapi32, capture, "OpenSCManagerW",
				FunctionDescriptor.of(p, p, p, i));
		createService = handle(advapi32, capture, "CreateServiceW",
				FunctionDescriptor.of(p, p, p, p, i, i, i, i, p, p, p, p, p, p));
		openService = handle(advapi32, capture, "OpenServiceW",
				FunctionDescriptor.of(p, p, p, i));
		startService = handle(advapi32, capture, "StartServiceW",
				FunctionDescriptor.of(i, p, i, p));
		controlService = handle(advapi32, capture, "ControlService",
				FunctionDescriptor.of(i, p, i, p));
		deleteService = handle(advapi32, capture, "DeleteService",
				FunctionDescriptor.of(i, p));
		changeServiceConfig = handle(advapi32, capture, "ChangeServiceConfigW",
				FunctionDescriptor.of(i, p, i, i, i, p, p, p, p, p, p, p));
		changeServiceConfig2 = handle(advapi32, capture, "ChangeServiceConfig2W",
				FunctionDescriptor.of(i, p, i, p));
		queryServiceStatusEx = handle(advapi32, capture, "QueryServiceStatusEx",
				FunctionDescriptor.of(i, p, i, p, i, p));
		enumServicesStatusEx = handle(advapi32, capture, "EnumServicesStatusExW",
				FunctionDescriptor.of(i, p, i, i, i, p, i, p, p, p, p));
		closeServiceHandle = handle(advapi32, capture, "CloseServiceHandle",
				FunctionDescriptor.of(i, p));
	}

	private MethodHandle handle(final SymbolLookup lib, final Linker.Option capture,
			final String name, final FunctionDescriptor descriptor) {
		final MemorySegment address = lib.find(name)
				.orElseThrow(() -> new ServiceException("advapi32 is missing " + name));
		return linker.downcallHandle(address, descriptor, capture);
	}

	@Override
	public boolean exists(final String name) {
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment capture = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, capture, SC_MANAGER_CONNECT);
			try {
				final MemorySegment service = (MemorySegment) openService.invoke(
						capture, scm, wide(a, name), SERVICE_QUERY_STATUS);
				if (isNull(service)) {
					return false;
				}
				close(capture, service);
				return true;
			} finally {
				close(capture, scm);
			}
		} catch (final ServiceException e) {
			throw e;
		} catch (final Throwable t) {
			throw new ServiceException("OpenServiceW failed for " + name, t);
		}
	}

	@Override
	public void create(final String name, final String displayName, final String binPath,
			final ServiceStartType startType, final String account, final String password,
			final List<String> dependsOn) {
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment capture = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, capture,
					SC_MANAGER_CONNECT | SC_MANAGER_CREATE_SERVICE);
			try {
				final MemorySegment service = (MemorySegment) createService.invoke(capture,
						scm,
						wide(a, name),
						wide(a, displayName),
						SERVICE_QUERY_STATUS | SERVICE_START | SERVICE_STOP | SERVICE_CHANGE_CONFIG
								| DELETE,
						SERVICE_WIN32_OWN_PROCESS,
						startType.code(),
						SERVICE_ERROR_NORMAL,
						wide(a, binPath),
						MemorySegment.NULL,                       // load order group
						MemorySegment.NULL,                       // tag id
						wideMulti(a, dependsOn),                  // dependencies (double-null)
						account == null ? MemorySegment.NULL : wide(a, account),
						password == null ? MemorySegment.NULL : wide(a, password));
				if (isNull(service)) {
					throw fail("CreateServiceW", name, capture);
				}
				if (startType.delayed()) {
					setDelayedAutoStart(a, capture, service, name);
				}
				close(capture, service);
			} finally {
				close(capture, scm);
			}
		} catch (final ServiceException e) {
			throw e;
		} catch (final Throwable t) {
			throw new ServiceException("CreateServiceW failed for " + name, t);
		}
	}

	@Override
	public void delete(final String name) {
		withService(name, DELETE, (a, capture, service) -> {
			final int ok = (int) deleteService.invoke(capture, service);
			if (ok == 0) {
				throw fail("DeleteService", name, capture);
			}
			return null;
		});
	}

	@Override
	public void start(final String name) {
		withService(name, SERVICE_START, (a, capture, service) -> {
			final int ok = (int) startService.invoke(capture, service, 0, MemorySegment.NULL);
			if (ok == 0 && lastError(capture) != ERROR_SERVICE_ALREADY_RUNNING) {
				throw fail("StartServiceW", name, capture);
			}
			return null;
		});
	}

	@Override
	public void stop(final String name) {
		withService(name, SERVICE_STOP, (a, capture, service) -> {
			final MemorySegment status = a.allocate(SERVICE_STATUS_SIZE);
			final int ok = (int) controlService.invoke(capture, service, SERVICE_CONTROL_STOP,
					status);
			if (ok == 0 && lastError(capture) != ERROR_SERVICE_NOT_ACTIVE) {
				throw fail("ControlService", name, capture);
			}
			return null;
		});
	}

	@Override
	public void setStartType(final String name, final ServiceStartType startType) {
		withService(name, SERVICE_CHANGE_CONFIG, (a, capture, service) -> {
			final int ok = (int) changeServiceConfig.invoke(capture, service,
					SERVICE_NO_CHANGE,           // dwServiceType
					startType.code(),            // dwStartType
					SERVICE_NO_CHANGE,           // dwErrorControl
					MemorySegment.NULL,          // lpBinaryPathName
					MemorySegment.NULL,          // lpLoadOrderGroup
					MemorySegment.NULL,          // lpdwTagId
					MemorySegment.NULL,          // lpDependencies
					MemorySegment.NULL,          // lpServiceStartName
					MemorySegment.NULL,          // lpPassword
					MemorySegment.NULL);         // lpDisplayName
			if (ok == 0) {
				throw fail("ChangeServiceConfigW", name, capture);
			}
			if (startType.delayed()) {
				setDelayedAutoStart(a, capture, service, name);
			}
			return null;
		});
	}

	@Override
	public void updateConfig(final String name, final String binPath,
			final ServiceStartType startType, final String account, final String password,
			final String displayName) {
		withService(name, SERVICE_CHANGE_CONFIG, (a, capture, service) -> {
			// ChangeServiceConfigW treats a NULL lpServiceStartName as "no change", so to actually
			// reset the account to LocalSystem we must pass the literal name (unlike CreateServiceW,
			// where NULL means LocalSystem).
			final String startName = account != null ? account : LOCAL_SYSTEM;
			final int ok = (int) changeServiceConfig.invoke(capture, service,
					SERVICE_NO_CHANGE,                                          // dwServiceType
					startType.code(),                                           // dwStartType
					SERVICE_NO_CHANGE,                                          // dwErrorControl
					wide(a, binPath),                                           // lpBinaryPathName
					MemorySegment.NULL,                                         // lpLoadOrderGroup
					MemorySegment.NULL,                                         // lpdwTagId
					MemorySegment.NULL,                                         // lpDependencies
					wide(a, startName),                                         // lpServiceStartName
					password != null ? wide(a, password) : MemorySegment.NULL,  // lpPassword
					displayName != null ? wide(a, displayName) : MemorySegment.NULL);  // lpDisplayName
			if (ok == 0) {
				throw fail("ChangeServiceConfigW", name, capture);
			}
			if (startType.delayed()) {
				setDelayedAutoStart(a, capture, service, name);
			}
			return null;
		});
	}

	@Override
	public void setDescription(final String name, final String description) {
		withService(name, SERVICE_CHANGE_CONFIG, (a, capture, service) -> {
			// SERVICE_DESCRIPTION { LPWSTR lpDescription; } — a single pointer.
			final MemorySegment info = a.allocate(ValueLayout.ADDRESS);
			info.set(ValueLayout.ADDRESS, 0, wide(a, description));
			final int ok = (int) changeServiceConfig2.invoke(capture, service,
					SERVICE_CONFIG_DESCRIPTION, info);
			if (ok == 0) {
				throw fail("ChangeServiceConfig2W", name, capture);
			}
			return null;
		});
	}

	@Override
	public ServiceControlStatus queryStatus(final String name) {
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment capture = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, capture, SC_MANAGER_CONNECT);
			try {
				final MemorySegment service = (MemorySegment) openService.invoke(
						capture, scm, wide(a, name), SERVICE_QUERY_STATUS);
				if (isNull(service)) {
					return null;   // not installed (or no access)
				}
				try {
					final MemorySegment buffer = a.allocate(SERVICE_STATUS_PROCESS_SIZE);
					final MemorySegment needed = a.allocate(ValueLayout.JAVA_INT);
					final int ok = (int) queryServiceStatusEx.invoke(capture, service,
							SC_STATUS_PROCESS_INFO, buffer,
							(int) SERVICE_STATUS_PROCESS_SIZE, needed);
					if (ok == 0) {
						throw fail("QueryServiceStatusEx", name, capture);
					}
					final int state = buffer.get(ValueLayout.JAVA_INT, OFF_CURRENT_STATE);
					final int exit = buffer.get(ValueLayout.JAVA_INT, OFF_WIN32_EXIT);
					final int pid = buffer.get(ValueLayout.JAVA_INT, OFF_PROCESS_ID);
					return ServiceControlStatus.of(state, pid, exit);
				} finally {
					close(capture, service);
				}
			} finally {
				close(capture, scm);
			}
		} catch (final ServiceException e) {
			throw e;
		} catch (final Throwable t) {
			throw new ServiceException("QueryServiceStatusEx failed for " + name, t);
		}
	}

	@Override
	public List<ScmService> enumerate() {
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment capture = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, capture, SC_MANAGER_ENUMERATE_SERVICE);
			try {
				final List<ScmService> out = new ArrayList<>();
				final MemorySegment needed = a.allocate(ValueLayout.JAVA_INT);
				final MemorySegment returned = a.allocate(ValueLayout.JAVA_INT);
				final MemorySegment resume = a.allocate(ValueLayout.JAVA_INT);
				resume.set(ValueLayout.JAVA_INT, 0, 0);
				// Probe with an empty buffer to learn the size, allocate, then read; the SCM may chunk
				// large result sets, so loop on ERROR_MORE_DATA following the resume handle.
				MemorySegment buffer = MemorySegment.NULL;
				int bufSize = 0;
				for (int guard = 0; guard < MAX_ENUM_ITERATIONS; guard++) {
					final int ok = (int) enumServicesStatusEx.invoke(capture, scm,
							SC_ENUM_PROCESS_INFO, SERVICE_WIN32, SERVICE_STATE_ALL,
							buffer, bufSize, needed, returned, resume, MemorySegment.NULL);
					final int count = returned.get(ValueLayout.JAVA_INT, 0);
					if (ok != 0) {
						if (count > 0) {
							out.addAll(parseEnumeration(buffer, count));
						}
						return out;
					}
					if (lastError(capture) != ERROR_MORE_DATA) {
						throw fail("EnumServicesStatusExW", "(all)", capture);
					}
					if (count > 0 && bufSize > 0) {
						out.addAll(parseEnumeration(buffer, count));   // a partial batch fit; keep going
					}
					final int need = needed.get(ValueLayout.JAVA_INT, 0);
					if (need <= 0) {
						return out;   // nothing more to read (defensive against a stuck loop)
					}
					bufSize = need;
					// Pointer-aligned: the entries embed LPWSTR pointers we read back via ADDRESS.
					buffer = a.allocate(bufSize, ValueLayout.ADDRESS.byteSize());
				}
				return out;
			} finally {
				close(capture, scm);
			}
		} catch (final ServiceException e) {
			throw e;
		} catch (final Throwable t) {
			throw new ServiceException("EnumServicesStatusExW failed", t);
		}
	}

	/**
	 * Parse {@code count} {@code ENUM_SERVICE_STATUS_PROCESSW} entries packed at the front of
	 * {@code buffer} (the service name + display-name strings live in the same buffer's tail, so each
	 * entry's {@code lpServiceName} pointer is relativized against {@code buffer.address()} and read
	 * back from the buffer — no reinterpret, so this stays bounds-checked and unit-tests off-Windows).
	 *
	 * <p>On 64-bit Windows the struct is two pointers ({@code lpServiceName}, {@code lpDisplayName})
	 * followed by a 36-byte {@code SERVICE_STATUS_PROCESS}, tail-padded to a pointer-aligned stride.
	 * Package-visible and static so a test can feed it a hand-built buffer.
	 */
	static List<ScmService> parseEnumeration(final MemorySegment buffer, final int count) {
		final List<ScmService> out = new ArrayList<>(Math.max(0, count));
		final long ptr = ValueLayout.ADDRESS.byteSize();
		final long statusBase = 2 * ptr;                                  // after the two LPWSTR pointers
		final long entrySize = roundUp(statusBase + SERVICE_STATUS_PROCESS_SIZE, ptr);
		final long base = buffer.address();
		for (int i = 0; i < count; i++) {
			final long entry = (long) i * entrySize;
			final MemorySegment namePtr = buffer.get(ValueLayout.ADDRESS, entry);   // lpServiceName
			final String name = buffer.getString(namePtr.address() - base, StandardCharsets.UTF_16LE);
			final int state = buffer.get(ValueLayout.JAVA_INT, entry + statusBase + OFF_CURRENT_STATE);
			final int exit = buffer.get(ValueLayout.JAVA_INT, entry + statusBase + OFF_WIN32_EXIT);
			final int pid = buffer.get(ValueLayout.JAVA_INT, entry + statusBase + OFF_PROCESS_ID);
			out.add(new ScmService(name, ServiceControlStatus.of(state, pid, exit)));
		}
		return out;
	}

	private static long roundUp(final long value, final long alignment) {
		return (value + alignment - 1) / alignment * alignment;
	}

	/** Set the delayed-auto-start flag via {@code ChangeServiceConfig2W}
	 * ({@code SERVICE_CONFIG_DELAYED_AUTO_START_INFO}). The struct is a single {@code BOOL}. */
	private void setDelayedAutoStart(final Arena arena, final MemorySegment capture,
			final MemorySegment service, final String name) throws Throwable {
		final MemorySegment info = arena.allocate(ValueLayout.JAVA_INT);   // { BOOL fDelayedAutostart }
		info.set(ValueLayout.JAVA_INT, 0, 1);
		final int ok = (int) changeServiceConfig2.invoke(capture, service,
				SERVICE_CONFIG_DELAYED_AUTO_START_INFO, info);
		if (ok == 0) {
			throw fail("ChangeServiceConfig2W(delayed-auto)", name, capture);
		}
	}

	// --- shared open-service-then-act plumbing ---

	private interface ServiceOp {
		Object run(Arena arena, MemorySegment capture, MemorySegment service) throws Throwable;
	}

	private void withService(final String name, final int access, final ServiceOp op) {
		try (Arena a = Arena.ofConfined()) {
			final MemorySegment capture = a.allocate(captureLayout);
			final MemorySegment scm = openManager(a, capture, SC_MANAGER_CONNECT);
			try {
				final MemorySegment service = (MemorySegment) openService.invoke(
						capture, scm, wide(a, name), access);
				if (isNull(service)) {
					if (lastError(capture) == ERROR_SERVICE_DOES_NOT_EXIST) {
						throw new com.u1.servicepal.ServiceNotFoundException(name);
					}
					throw fail("OpenServiceW", name, capture);
				}
				try {
					op.run(a, capture, service);
				} finally {
					close(capture, service);
				}
			} finally {
				close(capture, scm);
			}
		} catch (final ServiceException e) {
			throw e;
		} catch (final Throwable t) {
			throw new ServiceException("SCM call failed for " + name, t);
		}
	}

	private MemorySegment openManager(final Arena arena, final MemorySegment capture,
			final int access) throws Throwable {
		final MemorySegment scm = (MemorySegment) openSCManager.invoke(
				capture, MemorySegment.NULL, MemorySegment.NULL, access);
		if (isNull(scm)) {
			throw fail("OpenSCManagerW", "(local)", capture);
		}
		return scm;
	}

	private void close(final MemorySegment capture, final MemorySegment handle) {
		if (isNull(handle)) {
			return;
		}
		try {
			closeServiceHandle.invoke(capture, handle);
		} catch (final Throwable ignored) {
			// closing a handle should not mask the real outcome
		}
	}

	private NativeCommandException fail(final String function, final String name,
			final MemorySegment capture) {
		final int code = lastError(capture);
		return new NativeCommandException(List.of(function, name), code,
				"Win32 error " + code);
	}

	private int lastError(final MemorySegment capture) {
		return (int) lastErrorHandle.get(capture, 0L);
	}

	private static boolean isNull(final MemorySegment segment) {
		return segment == null || segment.address() == 0;
	}

	/** Allocate a NUL-terminated UTF-16LE (WCHAR) string. Windows is always little-endian. */
	private static MemorySegment wide(final Arena arena, final String s) {
		return arena.allocateFrom(ValueLayout.JAVA_CHAR, (s + "\0").toCharArray());
	}

	/** A double-NUL-terminated WCHAR list for {@code lpDependencies}; {@code NULL} if empty. */
	private static MemorySegment wideMulti(final Arena arena, final List<String> items) {
		if (items == null || items.isEmpty()) {
			return MemorySegment.NULL;
		}
		final StringBuilder sb = new StringBuilder();
		for (final String item : items) {
			sb.append(item).append('\0');
		}
		sb.append('\0');
		return arena.allocateFrom(ValueLayout.JAVA_CHAR, sb.toString().toCharArray());
	}
}
