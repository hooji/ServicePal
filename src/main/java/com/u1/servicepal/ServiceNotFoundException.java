package com.u1.servicepal;

/** A mutating operation targeted an id that is not installed. */
public final class ServiceNotFoundException extends ServiceException {

	private static final long serialVersionUID = 1L;

	public ServiceNotFoundException(final String id) {
		super("no service installed with id '" + id + "'");
	}
}
