package com.u1.servicepal;

/** A by-id operation found the same id in both PER_USER and SYSTEM_WIDE; pass an explicit
 * {@link Installation} to disambiguate. */
public final class AmbiguousServiceException extends ServiceException {

	private static final long serialVersionUID = 1L;

	public AmbiguousServiceException(final String id) {
		super("service '" + id + "' exists as both PER_USER and SYSTEM_WIDE;"
				+ " specify an Installation explicitly");
	}
}
