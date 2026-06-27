package com.u1.servicepal.model;

/** The coarse, cross-platform run state of a service. */
public enum RunState {

	RUNNING,
	STOPPED,
	STARTING,
	STOPPING,
	FAILED,
	UNKNOWN
}
