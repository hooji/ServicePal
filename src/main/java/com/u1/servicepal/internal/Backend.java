package com.u1.servicepal.internal;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Installation;
import com.u1.servicepal.Platform;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.util.List;

/**
 * Internal per-platform contract (code organization only — <em>not</em> a public extension
 * point). This increment implements the read/discovery half; mutation arrives in step 4.
 */
public interface Backend {

	Platform platform();

	Capabilities capabilities();

	/** Which installations this platform supports (e.g. OpenRC is SYSTEM_WIDE only). */
	List<Installation> supportedInstallations();

	List<ServiceStatus> list(Installation installation);

	/** Parsed spec, or {@code null} if not installed in the given installation. */
	ServiceSpec read(String id, Installation installation);

	/** Verbatim native definition text, or {@code null} if not installed. */
	String readNative(String id, Installation installation);

	/** Status, or {@code null} if not installed in the given installation. */
	ServiceStatus status(String id, Installation installation);
}
