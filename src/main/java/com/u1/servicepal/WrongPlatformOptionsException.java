package com.u1.servicepal;

/** A spec carried a platform option block for a platform other than the current one. */
public final class WrongPlatformOptionsException extends ServiceException {

	private static final long serialVersionUID = 1L;

	public WrongPlatformOptionsException(final String optionsName, final Platform current) {
		super(optionsName + " options were set, but this platform is " + current
				+ "; build the spec for the platform you are installing on");
	}
}
