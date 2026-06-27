package com.u1.servicepal;

/** A spec used a capability the current platform can't honor (fail-fast, pre-flight). */
public final class UnsupportedFeatureException extends ServiceException {

	private static final long serialVersionUID = 1L;

	public UnsupportedFeatureException(final String feature, final Platform platform) {
		super(platform + " does not support: " + feature);
	}
}
