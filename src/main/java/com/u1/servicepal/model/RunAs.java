package com.u1.servicepal.model;

import com.u1.servicepal.Installation;
import java.util.Objects;

/**
 * The run-as identity: <em>who</em> the service's process runs as. Distinct from
 * {@link Installation} (<em>where</em> the service is registered), which this derives.
 *
 * <p>Three values cover the cross-platform core. Each implies its installation:
 * <ul>
 *   <li>{@link #currentUser()} &rarr; {@link Installation#PER_USER}</li>
 *   <li>{@link #namedUser(String)} &rarr; {@link Installation#SYSTEM_WIDE} (drops to that user)</li>
 *   <li>{@link #systemDaemon()} &rarr; {@link Installation#SYSTEM_WIDE} (root / LocalSystem)</li>
 * </ul>
 */
public final class RunAs {

	public enum Kind { CURRENT_USER, NAMED_USER, SYSTEM_DAEMON }

	private final Kind kind;
	private final String userName;

	private RunAs(final Kind kind, final String userName) {
		this.kind = kind;
		this.userName = userName;
	}

	/** Run as the user running the JVM (a per-user agent). The default. */
	public static RunAs currentUser() {
		return new RunAs(Kind.CURRENT_USER, null);
	}

	/** System-registered, but the process drops to the named user. */
	public static RunAs namedUser(final String userName) {
		if (userName == null || userName.isBlank()) {
			throw new IllegalArgumentException("userName must be non-blank");
		}
		return new RunAs(Kind.NAMED_USER, userName);
	}

	/** Run as root / LocalSystem (a system daemon). */
	public static RunAs systemDaemon() {
		return new RunAs(Kind.SYSTEM_DAEMON, null);
	}

	public Kind kind() {
		return kind;
	}

	/** The named user, or {@code null} unless {@link #kind()} is {@link Kind#NAMED_USER}. */
	public String userName() {
		return userName;
	}

	/** The installation this identity implies. */
	public Installation installation() {
		return kind == Kind.CURRENT_USER ? Installation.PER_USER : Installation.SYSTEM_WIDE;
	}

	@Override
	public boolean equals(final Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof RunAs)) {
			return false;
		}
		final RunAs other = (RunAs) o;
		return kind == other.kind && Objects.equals(userName, other.userName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(kind, userName);
	}

	@Override
	public String toString() {
		if (kind == Kind.NAMED_USER) {
			return "RunAs[NAMED_USER:" + userName + "]";
		}
		return "RunAs[" + kind + "]";
	}
}
