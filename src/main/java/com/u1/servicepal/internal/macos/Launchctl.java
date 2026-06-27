package com.u1.servicepal.internal.macos;

import java.util.Map;

/** The launchctl seam (stub in tests). Discovery needs only the job listing for now. */
public interface Launchctl {

	/** Loaded jobs keyed by label. Resilient: returns empty on failure rather than throwing. */
	Map<String, JobInfo> listJobs();
}
