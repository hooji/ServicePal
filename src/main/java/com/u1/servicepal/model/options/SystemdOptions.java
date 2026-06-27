package com.u1.servicepal.model.options;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * systemd-specific options (Tier 2). Only consulted on Linux/systemd. A representative subset
 * for now; expanded as the systemd backend grows.
 */
public final class SystemdOptions {

	public enum Type { SIMPLE, EXEC, FORKING, ONESHOT, DBUS, NOTIFY, IDLE }

	private final Type type;
	private final List<String> after;
	private final List<String> wants;
	private final Duration restartSec;
	private final String memoryMax;

	private SystemdOptions(final Builder b) {
		this.type = b.type;
		this.after = List.copyOf(b.after);
		this.wants = List.copyOf(b.wants);
		this.restartSec = b.restartSec;
		this.memoryMax = b.memoryMax;
	}

	public Type type() {
		return type;
	}

	public List<String> after() {
		return after;
	}

	public List<String> wants() {
		return wants;
	}

	public Duration restartSec() {
		return restartSec;
	}

	public String memoryMax() {
		return memoryMax;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private Type type;
		private final List<String> after = new ArrayList<>();
		private final List<String> wants = new ArrayList<>();
		private Duration restartSec;
		private String memoryMax;

		public Builder type(final Type value) {
			this.type = value;
			return this;
		}

		public Builder after(final String unit) {
			this.after.add(unit);
			return this;
		}

		public Builder wants(final String unit) {
			this.wants.add(unit);
			return this;
		}

		public Builder restartSec(final Duration value) {
			this.restartSec = value;
			return this;
		}

		public Builder memoryMax(final String value) {
			this.memoryMax = value;
			return this;
		}

		public SystemdOptions build() {
			return new SystemdOptions(this);
		}
	}
}
