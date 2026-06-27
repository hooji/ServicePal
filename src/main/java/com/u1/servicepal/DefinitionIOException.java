package com.u1.servicepal;

/** Reading or writing a service definition file (plist / unit / script) failed. */
public final class DefinitionIOException extends ServiceException {

	private static final long serialVersionUID = 1L;

	public DefinitionIOException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
