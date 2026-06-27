package com.u1.servicepal.model.options;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenRC-specific options (Tier 2). Only consulted on Linux/OpenRC. A representative subset
 * for now; expanded as the OpenRC backend grows.
 */
public final class OpenRcOptions {

	public enum Supervisor { SUPERVISE_DAEMON, START_STOP_DAEMON }

	private final Supervisor supervisor;
	private final List<String> need;
	private final String runlevel;

	private OpenRcOptions(final Builder b) {
		this.supervisor = b.supervisor;
		this.need = List.copyOf(b.need);
		this.runlevel = b.runlevel;
	}

	public Supervisor supervisor() {
		return supervisor;
	}

	public List<String> need() {
		return need;
	}

	public String runlevel() {
		return runlevel;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private Supervisor supervisor;
		private final List<String> need = new ArrayList<>();
		private String runlevel;

		public Builder supervisor(final Supervisor value) {
			this.supervisor = value;
			return this;
		}

		public Builder need(final String service) {
			this.need.add(service);
			return this;
		}

		public Builder runlevel(final String value) {
			this.runlevel = value;
			return this;
		}

		public OpenRcOptions build() {
			return new OpenRcOptions(this);
		}
	}
}
