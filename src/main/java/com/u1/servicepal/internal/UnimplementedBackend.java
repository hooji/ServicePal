package com.u1.servicepal.internal;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.util.List;

/**
 * Placeholder for platforms whose backend isn't written yet (systemd, OpenRC, Windows). It
 * reports the platform and its <em>intended</em> capabilities, but throws on any actual
 * operation. macOS is the only platform with a real backend in this build.
 */
public final class UnimplementedBackend implements Backend {

	private final Platform platform;

	public UnimplementedBackend(final Platform platform) {
		this.platform = platform;
	}

	@Override
	public Platform platform() {
		return platform;
	}

	@Override
	public Capabilities capabilities() {
		return defaultFor(platform);
	}

	@Override
	public List<Installation> supportedInstallations() {
		if (platform == Platform.LINUX_OPENRC) {
			return List.of(Installation.SYSTEM_WIDE);
		}
		return List.of(Installation.PER_USER, Installation.SYSTEM_WIDE);
	}

	@Override
	public List<ServiceStatus> list(final Installation installation) {
		throw notImplemented();
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		throw notImplemented();
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		throw notImplemented();
	}

	private UnsupportedOperationException notImplemented() {
		return new UnsupportedOperationException("the " + platform + " backend is not yet"
				+ " implemented; discovery is currently implemented for macOS only");
	}

	/** Intended capabilities per platform (informational until the backend lands). */
	public static Capabilities defaultFor(final Platform platform) {
		switch (platform) {
			case LINUX_SYSTEMD:
				return new Capabilities(true, true, true, true, true, true, true, true, true);
			case LINUX_OPENRC:
				return new Capabilities(false, true, true, false, false, true, false, true, false);
			case WINDOWS:
				return new Capabilities(true, true, true, true, true, true, false, true, true);
			case MACOS_LAUNCHD:
			default:
				return new Capabilities(true, true, true, true, true, true, true, true, false);
		}
	}
}
