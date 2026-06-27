package com.u1.servicepal.model;

/**
 * The uniform keep-alive core. Richer/conditional restart behavior lives in the per-platform
 * option blocks.
 */
public enum RestartPolicy {

	/** Never auto-restart. */
	NEVER,

	/** Restart only when the process exits non-zero / crashes. */
	ON_FAILURE,

	/** Always restart, regardless of how it exited. */
	ALWAYS
}
