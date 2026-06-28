package com.u1.servicepal.gui;

import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;

/**
 * A single row in the GUI: the stored definition ({@link ServiceSpec}, may be {@code null} if it
 * could not be read) paired with its live {@link ServiceStatus}.
 */
public record Job(ServiceSpec spec, ServiceStatus status) {

	public String id() {
		return status.id();
	}

	public String displayName() {
		return spec != null ? spec.displayName() : status.id();
	}

	/** Whether ServicePal manages this service (created or adopted), vs. merely discovered. */
	public boolean managed() {
		return status.managed();
	}

	/** Whether we manage it but did not originally create it (we installed over it). */
	public boolean adopted() {
		return status.adopted();
	}
}
