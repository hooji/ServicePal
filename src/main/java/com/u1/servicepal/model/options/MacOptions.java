package com.u1.servicepal.model.options;

import java.time.Duration;

/**
 * macOS/launchd-specific options (Tier 2). Only consulted on macOS. A representative subset
 * for now; expanded as the macOS backend grows.
 */
public final class MacOptions {

	public enum ProcessType { BACKGROUND, STANDARD, ADAPTIVE, INTERACTIVE }

	public enum KeepAliveCondition { CRASHED, SUCCESSFUL_EXIT, NETWORK_STATE, PATH_STATE }

	private final ProcessType processType;
	private final Boolean lowPriorityIO;
	private final Integer nice;
	private final Duration throttleInterval;
	private final KeepAliveCondition keepAliveWhen;

	private MacOptions(final Builder b) {
		this.processType = b.processType;
		this.lowPriorityIO = b.lowPriorityIO;
		this.nice = b.nice;
		this.throttleInterval = b.throttleInterval;
		this.keepAliveWhen = b.keepAliveWhen;
	}

	public ProcessType processType() {
		return processType;
	}

	public Boolean lowPriorityIO() {
		return lowPriorityIO;
	}

	public Integer nice() {
		return nice;
	}

	public Duration throttleInterval() {
		return throttleInterval;
	}

	public KeepAliveCondition keepAliveWhen() {
		return keepAliveWhen;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private ProcessType processType;
		private Boolean lowPriorityIO;
		private Integer nice;
		private Duration throttleInterval;
		private KeepAliveCondition keepAliveWhen;

		public Builder processType(final ProcessType value) {
			this.processType = value;
			return this;
		}

		public Builder lowPriorityIO(final boolean value) {
			this.lowPriorityIO = value;
			return this;
		}

		public Builder nice(final int value) {
			this.nice = value;
			return this;
		}

		public Builder throttleInterval(final Duration value) {
			this.throttleInterval = value;
			return this;
		}

		public Builder keepAliveWhen(final KeepAliveCondition value) {
			this.keepAliveWhen = value;
			return this;
		}

		public MacOptions build() {
			return new MacOptions(this);
		}
	}
}
