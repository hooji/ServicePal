package com.u1.servicepal.gui;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.Discovery;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory {@link ServiceManager} for demos, screenshots, and tests. It implements the full
 * lifecycle against plain maps — nothing touches the real OS — so the GUI can be driven
 * deterministically on any machine. It reports the supplied {@link Platform} and
 * {@link Capabilities} verbatim, so a screenshot's status bar matches the runner it was taken on
 * even though the data is fabricated.
 */
public final class DemoServiceManager implements ServiceManager {

	private final Platform platform;
	private final Capabilities capabilities;
	private final Map<String, ServiceSpec> specs = new LinkedHashMap<>();
	private final Map<String, ServiceStatus> statuses = new LinkedHashMap<>();
	private int nextPid = 4800;

	public DemoServiceManager(final Platform platform, final Capabilities capabilities) {
		this.platform = platform;
		this.capabilities = capabilities;
	}

	/** Seed a pre-existing job (for demo data), bypassing {@link #install}. */
	public void seed(final ServiceSpec spec, final RunState state, final Integer pid,
			final boolean enabled, final Integer lastExitCode) {
		specs.put(spec.id(), spec);
		statuses.put(spec.id(), new ServiceStatus(spec.id(), spec.runAs().installation(), true,
				enabled, true, state, pid, lastExitCode, null));
	}

	@Override
	public Platform platform() {
		return platform;
	}

	@Override
	public Capabilities capabilities() {
		return capabilities;
	}

	@Override
	public Discovery discover() {
		return new Discovery(list(), new ArrayList<>());
	}

	@Override
	public Discovery discover(final Installation installation) {
		final List<ServiceStatus> out = new ArrayList<>();
		for (final ServiceStatus s : statuses.values()) {
			if (s.installation() == installation) {
				out.add(s);
			}
		}
		return new Discovery(out, new ArrayList<>());
	}

	@Override
	public List<ServiceStatus> list() {
		return new ArrayList<>(statuses.values());
	}

	@Override
	public List<ServiceStatus> listManaged() {
		return list();   // everything in the demo is managed by us
	}

	@Override
	public boolean isManaged(final String id) {
		return statuses.containsKey(id);
	}

	@Override
	public boolean isManaged(final String id, final Installation installation) {
		return isManaged(id);
	}

	@Override
	public ServiceSpec read(final String id) {
		return specs.get(id);
	}

	@Override
	public ServiceSpec read(final String id, final Installation installation) {
		return specs.get(id);
	}

	@Override
	public String readNative(final String id) {
		final ServiceSpec spec = specs.get(id);
		return spec == null ? null : "# demo definition for " + id + "\ncommand = " + spec.command();
	}

	@Override
	public String readNative(final String id, final Installation installation) {
		return readNative(id);
	}

	@Override
	public ServiceStatus status(final String id) {
		final ServiceStatus s = statuses.get(id);
		return s == null ? ServiceStatus.absent(id) : s;
	}

	@Override
	public ServiceStatus status(final String id, final Installation installation) {
		return status(id);
	}

	@Override
	public boolean isInstalled(final String id) {
		return statuses.containsKey(id);
	}

	@Override
	public boolean isInstalled(final String id, final Installation installation) {
		return isInstalled(id);
	}

	@Override
	public void install(final ServiceSpec spec) {
		final ServiceStatus prev = statuses.get(spec.id());
		final RunState state = prev != null ? prev.state() : RunState.STOPPED;
		final Integer pid = prev != null ? prev.pid() : null;
		specs.put(spec.id(), spec);
		statuses.put(spec.id(), new ServiceStatus(spec.id(), spec.runAs().installation(), true,
				spec.autoStart(), true, state, pid, null, null));
	}

	@Override
	public void install(final ServiceSpec spec, final boolean yesDoThisToAServiceIDidNotCreate) {
		install(spec);
	}

	@Override
	public void uninstall(final String id) {
		specs.remove(id);
		statuses.remove(id);
	}

	@Override
	public void uninstall(final String id, final boolean yesDoThisToAServiceIDidNotCreate) {
		uninstall(id);
	}

	@Override
	public void enable(final String id) {
		mutate(id, s -> withEnabled(s, true));
	}

	@Override
	public void disable(final String id) {
		mutate(id, s -> withEnabled(s, false));
	}

	@Override
	public void start(final String id) {
		mutate(id, s -> withState(s, RunState.RUNNING, nextPid++));
	}

	@Override
	public void stop(final String id) {
		mutate(id, s -> withState(s, RunState.STOPPED, null));
	}

	@Override
	public void restart(final String id) {
		start(id);
	}

	@Override
	public void installEnableStart(final ServiceSpec spec) {
		install(spec);
		enable(spec.id());
		start(spec.id());
	}

	// --- helpers ---

	private interface StatusFn {
		ServiceStatus apply(ServiceStatus s);
	}

	private void mutate(final String id, final StatusFn fn) {
		final ServiceStatus s = statuses.get(id);
		if (s != null) {
			statuses.put(id, fn.apply(s));
		}
	}

	private static ServiceStatus withState(final ServiceStatus s, final RunState state,
			final Integer pid) {
		return new ServiceStatus(s.id(), s.installation(), s.installed(), s.enabled(), s.managed(),
				state, pid, state == RunState.RUNNING ? null : s.lastExitCode(), s.raw());
	}

	private static ServiceStatus withEnabled(final ServiceStatus s, final boolean enabled) {
		return new ServiceStatus(s.id(), s.installation(), s.installed(), enabled, s.managed(),
				s.state(), s.pid(), s.lastExitCode(), s.raw());
	}
}
