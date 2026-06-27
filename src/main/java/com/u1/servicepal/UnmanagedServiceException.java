package com.u1.servicepal;

/**
 * A destructive or overwriting operation targeted a service this library did not create (no
 * managed-by marker). Call the {@code yesDoThisToAServiceIDidNotCreate = true} overload to
 * proceed anyway — the awkward name is the deliberate speed bump.
 */
public final class UnmanagedServiceException extends ServiceException {

	private static final long serialVersionUID = 1L;

	public UnmanagedServiceException(final String id) {
		super("service '" + id + "' was not created by ServicePal; refuse to touch it without"
				+ " the explicit yesDoThisToAServiceIDidNotCreate=true override");
	}
}
