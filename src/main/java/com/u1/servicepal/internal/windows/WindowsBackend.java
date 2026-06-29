package com.u1.servicepal.internal.windows;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.DefinitionIOException;
import com.u1.servicepal.Installation;
import com.u1.servicepal.NativeCommandException;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceNotFoundException;
import com.u1.servicepal.UnmanagedServiceException;
import com.u1.servicepal.UnsupportedFeatureException;
import com.u1.servicepal.internal.Backend;
import com.u1.servicepal.internal.exec.DefaultCommandRunner;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunAs;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import com.u1.servicepal.model.options.WindowsOptions;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Windows backend. Routes by job shape (tension T2): a long-running daemon becomes a real
 * Windows <em>service</em> whose {@code binPath} is our bundled pure-Java FFM {@link ServiceHost}
 * (which speaks the SCM control protocol and supervises the real command — see the error-1053
 * quirk); a scheduled job ({@code spec.schedule() != null}) becomes a <em>Task Scheduler</em>
 * task, whose action is any command directly (no protocol, no host).
 *
 * <p>Every managed service/task gets a sidecar JSON in {@code %ProgramData%\ServicePal\<id>.json}
 * — the host reads it to learn the real command, {@code read()} reconstructs the spec from it,
 * its presence is the managed-by marker, and its {@code kind} field routes by-id operations.
 *
 * <p>SYSTEM_WIDE-only in v1 (no per-user services; {@code perUserInstall} is false). Discovery
 * lists the services/tasks ServicePal manages (from their sidecars) <em>and</em> every other Win32
 * service on the machine ({@code EnumServicesStatusExW}, surfaced as foreign/unmanaged), deduped by
 * the case-insensitive service name.
 *
 * <p>All native access is behind {@link Scm} (advapi32/FFM) and {@link TaskScheduler}
 * (schtasks), so this backend unit-tests off-Windows with stubs.
 */
public final class WindowsBackend implements Backend {

	private static final String MANAGED_DESCRIPTION_PREFIX = "[ServicePal] ";

	// ControlService(STOP) is asynchronous (it returns before the service reaches STOPPED), so we
	// wait for STOPPED before re-acting: before StartServiceW in restart (otherwise the start hits a
	// still-stopping service, is rejected with ERROR_SERVICE_ALREADY_RUNNING, and leaves the service
	// stopped) and before DeleteService in uninstall (otherwise the service is only marked for
	// delete). A residual marked-for-delete window can still make a quick re-create fail, so create
	// retries on ERROR_SERVICE_MARKED_FOR_DELETE.
	private static final int STOP_WAIT_ATTEMPTS = 60;
	private static final long STOP_WAIT_INTERVAL_MS = 100L;
	private static final int CREATE_ATTEMPTS = 10;
	private static final long CREATE_RETRY_INTERVAL_MS = 300L;
	private static final int ERROR_SERVICE_MARKED_FOR_DELETE = 1072;

	private final Scm scm;
	private final TaskScheduler taskScheduler;
	private final Path sidecarDir;
	private final String javawExe;
	private final String classpath;
	private final SidecarWriter writer = new SidecarWriter();
	private final SidecarReader reader = new SidecarReader();
	private final TaskXmlWriter taskXmlWriter = new TaskXmlWriter();

	public WindowsBackend(final Scm scm, final TaskScheduler taskScheduler, final Path sidecarDir,
			final String javawExe, final String classpath) {
		this.scm = scm;
		this.taskScheduler = taskScheduler;
		this.sidecarDir = sidecarDir;
		this.javawExe = javawExe;
		this.classpath = classpath;
	}

	/** Real Windows wiring: FFM SCM, schtasks, %ProgramData% sidecars, this jar's host binPath. */
	public static WindowsBackend createDefault() {
		final String javaw = Path.of(System.getProperty("java.home", ""), "bin", "javaw.exe")
				.toString();
		return new WindowsBackend(new FfmScm(), new SchtasksScheduler(new DefaultCommandRunner()),
				WindowsPaths.sidecarDir(), javaw, resolveClasspath());
	}

	@Override
	public Platform platform() {
		return Platform.WINDOWS;
	}

	@Override
	public Capabilities capabilities() {
		// SYSTEM_WIDE-only (no per-user) and no conditional keep-alive; Task Scheduler gives
		// calendar+interval; the host gives keep-alive + log redirection; QueryServiceStatusEx
		// gives structured status.
		return new Capabilities(false, true, true, true, true, true, false, true, true);
	}

	@Override
	public List<Installation> supportedInstallations() {
		return List.of(Installation.SYSTEM_WIDE);
	}

	@Override
	public Discovery discover(final Installation installation) {
		final List<ServiceStatus> services = new ArrayList<>();
		final List<String> unreadable = new ArrayList<>();
		if (installation != Installation.SYSTEM_WIDE) {
			return new Discovery(services, unreadable);
		}
		// 1. The services/tasks ServicePal manages, from their sidecars (these carry our markers,
		//    auto-start state, and — for tasks — run times). Service names are case-insensitive on
		//    Windows, so we dedup against the machine-wide pass by lower-cased name.
		final Set<String> seen = new HashSet<>();
		if (Files.isDirectory(sidecarDir)) {
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(sidecarDir, "*.json")) {
				for (final Path file : stream) {
					final String id = stem(file);
					try {
						final ServiceStatus status = status(id, Installation.SYSTEM_WIDE);
						if (status != null) {
							services.add(status);
							seen.add(id.toLowerCase(Locale.ROOT));
						}
					} catch (final DefinitionIOException e) {
						unreadable.add(file.toString());
					}
				}
			} catch (final IOException e) {
				// unreadable directory — skip
			}
		}
		// 2. Every other Win32 service on the machine (EnumServicesStatusExW) as a foreign (unmanaged)
		//    entry, so the "other background jobs" view is populated on Windows too. A managed service
		//    is also a real SCM service, so the lower-cased-name dedup keeps the sidecar entry (which
		//    is the one that knows it's managed). A failed enumeration must not hide the managed ones.
		try {
			for (final ScmService entry : scm.enumerate()) {
				if (seen.add(entry.name().toLowerCase(Locale.ROOT))) {
					final ServiceControlStatus st = entry.status();
					services.add(new ServiceStatus(entry.name(), Installation.SYSTEM_WIDE, true, false,
							false, false, st.state(), st.pid(), st.lastExitCode(), null));
				}
			}
		} catch (final RuntimeException e) {
			unreadable.add("<machine-wide service enumeration failed: " + e.getMessage() + ">");
		}
		return new Discovery(services, unreadable);
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		final Map<String, Object> sidecar = sidecarOrNull(id);
		return sidecar == null ? null : reader.toSpec(sidecar, id);
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		final Path file = sidecarFile(id);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return Files.readString(file);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to read " + file, e);
		}
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		if (installation != Installation.SYSTEM_WIDE) {
			return null;
		}
		final Map<String, Object> sidecar = sidecarOrNull(id);
		if (sidecar != null && SidecarReader.KIND_TASK.equals(reader.kind(sidecar))) {
			if (!taskScheduler.exists(id)) {
				return null;   // orphaned sidecar; the task is gone
			}
			final RunState state = taskScheduler.isRunning(id) ? RunState.RUNNING : RunState.STOPPED;
			final boolean taskManaged = reader.isManaged(sidecar);
			final TaskRunTimes runTimes = taskScheduler.runTimes(id);
			return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, reader.autoStart(sidecar),
					taskManaged, taskManaged && reader.isAdopted(sidecar), state, null, null, null)
					.withRunTimes(runTimes.next(), runTimes.last());
		}
		final ServiceControlStatus live = scm.queryStatus(id);
		if (live == null) {
			return null;   // not installed as a service
		}
		final boolean managed = sidecar != null && reader.isManaged(sidecar);
		final boolean adopted = managed && reader.isAdopted(sidecar);
		final boolean enabled = managed && reader.autoStart(sidecar);
		return new ServiceStatus(id, Installation.SYSTEM_WIDE, true, enabled, managed, adopted,
				live.state(), live.pid(), live.lastExitCode(), null);
	}

	// --- mutation ---

	@Override
	public void install(final ServiceSpec spec, final boolean overwriteUnmanaged) {
		if (spec.runAs().installation() != Installation.SYSTEM_WIDE) {
			throw new UnsupportedFeatureException("per-user installation", Platform.WINDOWS);
		}
		final String id = spec.id();
		final boolean scheduled = spec.schedule() != null;

		final Map<String, Object> existing = sidecarOrNull(id);
		final boolean managed = existing != null && reader.isManaged(existing);
		final boolean existsLive = scm.exists(id) || taskScheduler.exists(id);
		if ((existing != null || existsLive) && !managed && !overwriteUnmanaged) {
			throw new UnmanagedServiceException(id);
		}

		// Preserve our own provenance; mark an adoption when we install over something foreign.
		final boolean adopted;
		if (managed) {
			adopted = reader.isAdopted(existing);
		} else {
			adopted = existing != null || existsLive;
		}
		writeSidecar(id, writer.render(spec, scheduled, adopted));

		if (scheduled) {
			taskScheduler.create(id, taskXmlWriter.render(spec), accountOf(spec.runAs()),
					passwordOf(spec));
		} else {
			final ServiceStartType startType = startTypeOf(spec);
			final String binPath = buildBinPath(javawExe, classpath, id);
			final String account = accountForScm(spec);
			if (scm.exists(id)) {
				// Upsert: reconcile the SCM record in place so a changed run-as account, display
				// name, or start type actually takes effect (the command updates via the sidecar
				// rewritten above). In-place ChangeServiceConfig avoids the delete+recreate
				// ERROR_SERVICE_MARKED_FOR_DELETE hazard.
				scm.updateConfig(id, binPath, startType, account, passwordOf(spec),
						spec.displayName());
			} else {
				// A just-uninstalled service can linger briefly in the marked-for-delete state;
				// retry the create so a quick reinstall of the same id doesn't fail (error 1072).
				final String displayName = spec.displayName();
				final String password = passwordOf(spec);
				final List<String> deps = dependsOn(spec);
				createWithRetry(() -> scm.create(id, displayName, binPath, startType, account,
						password, deps));
			}
			// The sidecar is the authoritative managed marker; the description marker is a
			// human-visible hint, so a failure to set it must not fail an otherwise-good install.
			ignoreFailure(() -> scm.setDescription(id, MANAGED_DESCRIPTION_PREFIX + describe(spec)));
		}
	}

	@Override
	public void uninstall(final String id, final Installation installation,
			final boolean unmanagedOk) {
		final Map<String, Object> sidecar = sidecarOrNull(id);
		final boolean isService = scm.exists(id);
		final boolean isTask = taskScheduler.exists(id);
		if (sidecar == null && !isService && !isTask) {
			throw new ServiceNotFoundException(id);
		}
		final boolean managed = sidecar != null && reader.isManaged(sidecar);
		if (!unmanagedOk && !managed) {
			throw new UnmanagedServiceException(id);
		}
		if (isTask(id, sidecar)) {
			ignoreFailure(() -> taskScheduler.end(id));
			ignoreFailure(() -> taskScheduler.delete(id));
		} else {
			ignoreFailure(() -> scm.stop(id));
			waitUntilStopped(id);   // let the host exit so DeleteService really removes the service
			ignoreFailure(() -> scm.delete(id));
		}
		deleteSidecar(id);
	}

	@Override
	public void enable(final String id, final Installation installation) {
		final Map<String, Object> sidecar = require(id);
		if (isTask(id, sidecar)) {
			taskScheduler.setEnabled(id, true);
		} else {
			scm.setStartType(id, ServiceStartType.AUTO);
		}
		updateAutoStart(id, sidecar, true);
	}

	@Override
	public void disable(final String id, final Installation installation) {
		final Map<String, Object> sidecar = require(id);
		if (isTask(id, sidecar)) {
			taskScheduler.setEnabled(id, false);
		} else {
			scm.setStartType(id, ServiceStartType.DEMAND);   // won't auto-start, still startable
		}
		updateAutoStart(id, sidecar, false);
	}

	@Override
	public void start(final String id, final Installation installation) {
		final Map<String, Object> sidecar = require(id);
		if (isTask(id, sidecar)) {
			taskScheduler.run(id);
		} else {
			scm.start(id);
		}
	}

	@Override
	public void stop(final String id, final Installation installation) {
		final Map<String, Object> sidecar = require(id);
		if (isTask(id, sidecar)) {
			taskScheduler.end(id);
		} else {
			scm.stop(id);
		}
	}

	@Override
	public void restart(final String id, final Installation installation) {
		final Map<String, Object> sidecar = require(id);
		if (isTask(id, sidecar)) {
			taskScheduler.end(id);
			taskScheduler.run(id);
		} else {
			scm.stop(id);
			waitUntilStopped(id);   // ControlService(STOP) is async; start would otherwise race it
			scm.start(id);
		}
	}

	/** Poll until the service reports STOPPED (or is gone), so we don't re-act mid-teardown. */
	private void waitUntilStopped(final String id) {
		for (int i = 0; i < STOP_WAIT_ATTEMPTS; i++) {
			final ServiceControlStatus st;
			try {
				st = scm.queryStatus(id);
			} catch (final RuntimeException e) {
				return;   // can't observe it — don't block the operation
			}
			if (st == null || st.state() == RunState.STOPPED) {
				return;
			}
			sleepQuietly(STOP_WAIT_INTERVAL_MS);
		}
	}

	/** Run a create, retrying while the SCM still has the name marked for delete (error 1072). */
	private void createWithRetry(final Runnable create) {
		for (int attempt = 1; attempt <= CREATE_ATTEMPTS; attempt++) {
			try {
				create.run();
				return;
			} catch (final NativeCommandException e) {
				if (e.exitCode() != ERROR_SERVICE_MARKED_FOR_DELETE || attempt == CREATE_ATTEMPTS) {
					throw e;
				}
				sleepQuietly(CREATE_RETRY_INTERVAL_MS);
			}
		}
	}

	private static void sleepQuietly(final long millis) {
		try {
			Thread.sleep(millis);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// --- helpers ---

	/** The host invocation registered as a service's binPath. Package-visible for testing. */
	static String buildBinPath(final String javawExe, final String classpath, final String id) {
		return "\"" + javawExe + "\" --enable-native-access=ALL-UNNAMED -cp \"" + classpath + "\" "
				+ ServiceHost.class.getName() + " --id " + id;
	}

	private Map<String, Object> require(final String id) {
		final Map<String, Object> sidecar = sidecarOrNull(id);
		if (sidecar == null && !scm.exists(id) && !taskScheduler.exists(id)) {
			throw new ServiceNotFoundException(id);
		}
		return sidecar;
	}

	/** Is this id a scheduled task? From the sidecar kind if present, else from what exists live. */
	private boolean isTask(final String id, final Map<String, Object> sidecar) {
		if (sidecar != null) {
			return SidecarReader.KIND_TASK.equals(reader.kind(sidecar));
		}
		return !scm.exists(id) && taskScheduler.exists(id);
	}

	private void updateAutoStart(final String id, final Map<String, Object> sidecar,
			final boolean autoStart) {
		if (sidecar == null) {
			return;   // unmanaged; nothing of ours to update
		}
		final boolean scheduled = SidecarReader.KIND_TASK.equals(reader.kind(sidecar));
		final ServiceSpec updated = reader.toSpec(sidecar, id).toBuilder()
				.autoStart(autoStart).build();
		writeSidecar(id, writer.render(updated, scheduled));
	}

	private ServiceStartType startTypeOf(final ServiceSpec spec) {
		final WindowsOptions opts = spec.windows();
		if (opts != null && opts.startType() != null) {
			return switch (opts.startType()) {
				case AUTO -> ServiceStartType.AUTO;
				case DELAYED_AUTO -> ServiceStartType.AUTO_DELAYED;
				case MANUAL -> ServiceStartType.DEMAND;
				case DISABLED -> ServiceStartType.DISABLED;
			};
		}
		return spec.autoStart() ? ServiceStartType.AUTO : ServiceStartType.DEMAND;
	}

	/** The bare run-as account (used for the Task Scheduler {@code /ru}); {@code null} = LocalSystem. */
	private static String accountOf(final RunAs runAs) {
		return runAs.kind() == RunAs.Kind.NAMED_USER ? runAs.userName() : null;
	}

	/**
	 * The SCM logon account ({@code lpServiceStartName}); {@code null} = LocalSystem. An explicit
	 * {@code WindowsOptions.account()} wins (e.g. {@code "NT AUTHORITY\\LocalService"}); otherwise a
	 * bare named user is qualified to the local machine ({@code name} &rarr; {@code .\name}), while a
	 * name that already contains a backslash (a domain or built-in account) is left as given.
	 */
	private static String accountForScm(final ServiceSpec spec) {
		final WindowsOptions opts = spec.windows();
		if (opts != null && opts.account() != null) {
			return opts.account();
		}
		final RunAs runAs = spec.runAs();
		if (runAs.kind() == RunAs.Kind.NAMED_USER) {
			final String name = runAs.userName();
			return name.contains("\\") ? name : ".\\" + name;
		}
		return null;   // LocalSystem
	}

	private static String passwordOf(final ServiceSpec spec) {
		return spec.windows() != null ? spec.windows().password() : null;
	}

	private static List<String> dependsOn(final ServiceSpec spec) {
		return spec.windows() != null ? spec.windows().dependsOn() : List.of();
	}

	private static String describe(final ServiceSpec spec) {
		return spec.description() != null ? spec.description() : spec.displayName();
	}

	private Path sidecarFile(final String id) {
		return sidecarDir.resolve(id + ".json");
	}

	private Map<String, Object> sidecarOrNull(final String id) {
		final Path file = sidecarFile(id);
		return Files.isRegularFile(file) ? reader.parseFile(file) : null;
	}

	private void writeSidecar(final String id, final String json) {
		try {
			Files.createDirectories(sidecarDir);
			Files.writeString(sidecarFile(id), json);
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to write sidecar for " + id, e);
		}
	}

	private void deleteSidecar(final String id) {
		try {
			Files.deleteIfExists(sidecarFile(id));
		} catch (final IOException e) {
			throw new DefinitionIOException("failed to delete sidecar for " + id, e);
		}
	}

	private static String stem(final Path file) {
		final String name = file.getFileName().toString();
		return name.endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name;
	}

	private static String resolveClasspath() {
		try {
			return Path.of(WindowsBackend.class.getProtectionDomain().getCodeSource().getLocation()
					.toURI()).toString();
		} catch (final URISyntaxException | RuntimeException e) {
			return System.getProperty("java.class.path", "");
		}
	}

	private static void ignoreFailure(final Runnable action) {
		try {
			action.run();
		} catch (final NativeCommandException ignored) {
			// best-effort (teardown / cosmetic description)
		}
	}
}
