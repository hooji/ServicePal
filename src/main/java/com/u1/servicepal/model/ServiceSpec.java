package com.u1.servicepal.model;

import com.u1.servicepal.model.options.MacOptions;
import com.u1.servicepal.model.options.OpenRcOptions;
import com.u1.servicepal.model.options.SystemdOptions;
import com.u1.servicepal.model.options.WindowsOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable service definition. Built with {@link #builder()}; nullable fields use
 * {@code null} (never {@code Optional}); collections are never {@code null}.
 *
 * <p>Both {@code id} and {@code displayName} are optional: an absent id is generated as
 * {@code com.u1.servicepal.<uuid>}, and an absent displayName defaults to the id.
 */
public final class ServiceSpec {

	private static final String GENERATED_ID_PREFIX = "com.u1.servicepal.";

	private final String id;
	private final String displayName;
	private final String description;
	private final List<String> command;
	private final Path workingDirectory;
	private final Map<String, String> environment;
	private final RunAs runAs;
	private final Path stdout;
	private final Path stderr;
	private final boolean autoStart;
	private final RestartPolicy restart;
	private final Schedule schedule;
	private final MacOptions mac;
	private final SystemdOptions systemd;
	private final WindowsOptions windows;
	private final OpenRcOptions openrc;

	private ServiceSpec(final Builder b) {
		this.id = b.id != null ? b.id : GENERATED_ID_PREFIX + UUID.randomUUID();
		this.displayName = b.displayName != null ? b.displayName : this.id;
		this.description = b.description;
		this.command = List.copyOf(b.command);
		this.workingDirectory = b.workingDirectory;
		this.environment = Map.copyOf(b.environment);
		this.runAs = b.runAs != null ? b.runAs : RunAs.currentUser();
		this.stdout = b.stdout;
		this.stderr = b.stderr;
		this.autoStart = b.autoStart;
		this.restart = b.restart != null ? b.restart : RestartPolicy.NEVER;
		this.schedule = b.schedule;
		this.mac = b.mac;
		this.systemd = b.systemd;
		this.windows = b.windows;
		this.openrc = b.openrc;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	/** Nullable. */
	public String description() {
		return description;
	}

	public List<String> command() {
		return command;
	}

	/** Nullable. */
	public Path workingDirectory() {
		return workingDirectory;
	}

	public Map<String, String> environment() {
		return environment;
	}

	public RunAs runAs() {
		return runAs;
	}

	/** Nullable. */
	public Path stdout() {
		return stdout;
	}

	/** Nullable. */
	public Path stderr() {
		return stderr;
	}

	public boolean autoStart() {
		return autoStart;
	}

	public RestartPolicy restart() {
		return restart;
	}

	/** Nullable; non-null marks this a scheduled job. */
	public Schedule schedule() {
		return schedule;
	}

	/** Nullable. */
	public MacOptions mac() {
		return mac;
	}

	/** Nullable. */
	public SystemdOptions systemd() {
		return systemd;
	}

	/** Nullable. */
	public WindowsOptions windows() {
		return windows;
	}

	/** Nullable. */
	public OpenRcOptions openrc() {
		return openrc;
	}

	/** A builder pre-populated with this spec's values, for read&rarr;modify&rarr;install. */
	public Builder toBuilder() {
		final Builder b = new Builder();
		b.id = this.id;
		b.displayName = this.displayName;
		b.description = this.description;
		b.command = new ArrayList<>(this.command);
		b.workingDirectory = this.workingDirectory;
		b.environment = new LinkedHashMap<>(this.environment);
		b.runAs = this.runAs;
		b.stdout = this.stdout;
		b.stderr = this.stderr;
		b.autoStart = this.autoStart;
		b.restart = this.restart;
		b.schedule = this.schedule;
		b.mac = this.mac;
		b.systemd = this.systemd;
		b.windows = this.windows;
		b.openrc = this.openrc;
		return b;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String id;
		private String displayName;
		private String description;
		private List<String> command = new ArrayList<>();
		private Path workingDirectory;
		private Map<String, String> environment = new LinkedHashMap<>();
		private RunAs runAs;
		private Path stdout;
		private Path stderr;
		private boolean autoStart;
		private RestartPolicy restart;
		private Schedule schedule;
		private MacOptions mac;
		private SystemdOptions systemd;
		private WindowsOptions windows;
		private OpenRcOptions openrc;

		public Builder id(final String value) {
			this.id = value;
			return this;
		}

		public Builder displayName(final String value) {
			this.displayName = value;
			return this;
		}

		public Builder description(final String value) {
			this.description = value;
			return this;
		}

		public Builder command(final String... args) {
			this.command = new ArrayList<>(List.of(args));
			return this;
		}

		public Builder command(final List<String> args) {
			this.command = new ArrayList<>(args);
			return this;
		}

		public Builder workingDirectory(final Path value) {
			this.workingDirectory = value;
			return this;
		}

		public Builder env(final String key, final String value) {
			this.environment.put(key, value);
			return this;
		}

		public Builder environment(final Map<String, String> value) {
			this.environment = new LinkedHashMap<>(value);
			return this;
		}

		// --- run-as identity (fluent; mutually exclusive, last wins) ---

		public Builder asCurrentUser() {
			this.runAs = RunAs.currentUser();
			return this;
		}

		public Builder asUser(final String userName) {
			this.runAs = RunAs.namedUser(userName);
			return this;
		}

		public Builder asSystemDaemon() {
			this.runAs = RunAs.systemDaemon();
			return this;
		}

		public Builder runAs(final RunAs value) {
			this.runAs = value;
			return this;
		}

		public Builder stdout(final Path value) {
			this.stdout = value;
			return this;
		}

		public Builder stderr(final Path value) {
			this.stderr = value;
			return this;
		}

		public Builder autoStart(final boolean value) {
			this.autoStart = value;
			return this;
		}

		public Builder restart(final RestartPolicy value) {
			this.restart = value;
			return this;
		}

		public Builder schedule(final Schedule value) {
			this.schedule = value;
			return this;
		}

		public Builder mac(final MacOptions value) {
			this.mac = value;
			return this;
		}

		public Builder systemd(final SystemdOptions value) {
			this.systemd = value;
			return this;
		}

		public Builder windows(final WindowsOptions value) {
			this.windows = value;
			return this;
		}

		public Builder openrc(final OpenRcOptions value) {
			this.openrc = value;
			return this;
		}

		public ServiceSpec build() {
			if (command.isEmpty()) {
				throw new IllegalArgumentException("command must have at least one element");
			}
			if (schedule != null && restart == RestartPolicy.ALWAYS) {
				throw new IllegalArgumentException(
						"a job cannot be both scheduled and RestartPolicy.ALWAYS");
			}
			return new ServiceSpec(this);
		}
	}
}
