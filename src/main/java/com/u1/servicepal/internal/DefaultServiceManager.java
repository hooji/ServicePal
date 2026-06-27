package com.u1.servicepal.internal;

import com.u1.servicepal.AmbiguousServiceException;
import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.ServiceNotFoundException;
import com.u1.servicepal.UnsupportedFeatureException;
import com.u1.servicepal.WrongPlatformOptionsException;
import com.u1.servicepal.internal.macos.LaunchdBackend;
import com.u1.servicepal.internal.systemd.SystemdBackend;
import com.u1.servicepal.model.CalendarSchedule;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.IntervalSchedule;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires a {@link Backend} to the {@link ServiceManager} facade. Discovery/inspection is fully
 * implemented; mutation throws {@link UnsupportedOperationException} until step 4.
 */
public final class DefaultServiceManager implements ServiceManager {

	private final Backend backend;

	public DefaultServiceManager(final Backend backend) {
		this.backend = backend;
	}

	public static ServiceManager create(final Platform platform) {
		if (platform == Platform.MACOS_LAUNCHD) {
			return new DefaultServiceManager(LaunchdBackend.createDefault());
		}
		if (platform == Platform.LINUX_SYSTEMD) {
			return new DefaultServiceManager(SystemdBackend.createDefault());
		}
		return new DefaultServiceManager(new UnimplementedBackend(platform));
	}

	@Override
	public Platform platform() {
		return backend.platform();
	}

	@Override
	public Capabilities capabilities() {
		return backend.capabilities();
	}

	// --- discovery & inspection ---

	@Override
	public Discovery discover() {
		final List<ServiceStatus> services = new ArrayList<>();
		final List<String> unreadable = new ArrayList<>();
		for (final Installation installation : backend.supportedInstallations()) {
			final Discovery d = backend.discover(installation);
			services.addAll(d.services());
			unreadable.addAll(d.unreadable());
		}
		return new Discovery(services, unreadable);
	}

	@Override
	public Discovery discover(final Installation installation) {
		return backend.discover(installation);
	}

	@Override
	public List<ServiceStatus> list() {
		return discover().services();
	}

	@Override
	public List<ServiceStatus> listManaged() {
		final List<ServiceStatus> out = new ArrayList<>();
		for (final ServiceStatus status : list()) {
			if (status.managed()) {
				out.add(status);
			}
		}
		return out;
	}

	@Override
	public boolean isManaged(final String id) {
		final Installation installation = resolve(id);
		return installation != null && isManaged(id, installation);
	}

	@Override
	public boolean isManaged(final String id, final Installation installation) {
		final ServiceStatus status = backend.status(id, installation);
		return status != null && status.managed();
	}

	@Override
	public ServiceSpec read(final String id) {
		final Installation installation = resolve(id);
		return installation == null ? null : backend.read(id, installation);
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		return backend.read(id, installation);
	}

	@Override
	public String readNative(final String id) {
		final Installation installation = resolve(id);
		return installation == null ? null : backend.readNative(id, installation);
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		return backend.readNative(id, installation);
	}

	@Override
	public ServiceStatus status(final String id) {
		final Installation installation = resolve(id);
		return installation == null ? ServiceStatus.absent(id) : backend.status(id, installation);
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		final ServiceStatus status = backend.status(id, installation);
		return status == null ? ServiceStatus.absent(id) : status;
	}

	@Override
	public boolean isInstalled(final String id) {
		return resolve(id) != null;
	}

	@Override
	public boolean isInstalled(final String id, final Installation installation) {
		return backend.status(id, installation) != null;
	}

	/** Auto-resolution: PER_USER first, then SYSTEM_WIDE; ambiguous if present in both. */
	private Installation resolve(final String id) {
		Installation found = null;
		for (final Installation installation : backend.supportedInstallations()) {
			if (backend.status(id, installation) != null) {
				if (found != null) {
					throw new AmbiguousServiceException(id);
				}
				found = installation;
			}
		}
		return found;
	}

	// --- mutation ---

	@Override
	public void install(final ServiceSpec spec) {
		install(spec, false);
	}

	@Override
	public void install(final ServiceSpec spec, final boolean yesDoThisToAServiceIDidNotCreate) {
		validate(spec);
		backend.install(spec, yesDoThisToAServiceIDidNotCreate);
	}

	@Override
	public void uninstall(final String id) {
		backend.uninstall(id, requireInstallation(id), false);
	}

	@Override
	public void uninstall(final String id, final boolean yesDoThisToAServiceIDidNotCreate) {
		backend.uninstall(id, requireInstallation(id), yesDoThisToAServiceIDidNotCreate);
	}

	@Override
	public void enable(final String id) {
		backend.enable(id, requireInstallation(id));
	}

	@Override
	public void disable(final String id) {
		backend.disable(id, requireInstallation(id));
	}

	@Override
	public void start(final String id) {
		backend.start(id, requireInstallation(id));
	}

	@Override
	public void stop(final String id) {
		backend.stop(id, requireInstallation(id));
	}

	@Override
	public void restart(final String id) {
		backend.restart(id, requireInstallation(id));
	}

	@Override
	public void installEnableStart(final ServiceSpec spec) {
		validate(spec);
		backend.install(spec, false);
		final Installation installation = spec.runAs().installation();
		backend.enable(spec.id(), installation);
		backend.start(spec.id(), installation);
	}

	private Installation requireInstallation(final String id) {
		final Installation installation = resolve(id);
		if (installation == null) {
			throw new ServiceNotFoundException(id);
		}
		return installation;
	}

	/** Pre-flight: reject foreign option blocks and capability gaps before touching the OS. */
	private void validate(final ServiceSpec spec) {
		final Platform platform = backend.platform();
		if (spec.mac() != null && platform != Platform.MACOS_LAUNCHD) {
			throw new WrongPlatformOptionsException("mac", platform);
		}
		if (spec.systemd() != null && platform != Platform.LINUX_SYSTEMD) {
			throw new WrongPlatformOptionsException("systemd", platform);
		}
		if (spec.windows() != null && platform != Platform.WINDOWS) {
			throw new WrongPlatformOptionsException("windows", platform);
		}
		if (spec.openrc() != null && platform != Platform.LINUX_OPENRC) {
			throw new WrongPlatformOptionsException("openrc", platform);
		}

		final Capabilities caps = backend.capabilities();
		final Installation installation = spec.runAs().installation();
		if (installation == Installation.PER_USER && !caps.perUserInstall()) {
			throw new UnsupportedFeatureException("per-user installation", platform);
		}
		if (installation == Installation.SYSTEM_WIDE && !caps.systemWideInstall()) {
			throw new UnsupportedFeatureException("system-wide installation", platform);
		}
		if (spec.schedule() instanceof CalendarSchedule && !caps.calendarSchedule()) {
			throw new UnsupportedFeatureException("calendar schedule", platform);
		}
		if (spec.schedule() instanceof IntervalSchedule && !caps.intervalSchedule()) {
			throw new UnsupportedFeatureException("interval schedule", platform);
		}
	}
}
