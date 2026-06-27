package com.u1.servicepal.model;

import java.util.List;

/**
 * The result of discovering services: the ones we could read, plus the paths of any definition
 * files we found but could <em>not</em> read (insufficient permissions or malformed). The
 * latter are reported rather than silently dropped, so discovery is honest about its coverage.
 *
 * @param services   the services successfully read (never null)
 * @param unreadable paths of definition files that were skipped (never null)
 */
public record Discovery(List<ServiceStatus> services, List<String> unreadable) {
}
