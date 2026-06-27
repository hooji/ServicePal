package com.u1.servicepal.internal.macos;

/**
 * A row from {@code launchctl list}.
 *
 * @param pid        the running pid, or {@code null} if loaded but not running
 * @param lastStatus the last exit status, or {@code null} if unknown
 */
public record JobInfo(Integer pid, Integer lastStatus) {
}
