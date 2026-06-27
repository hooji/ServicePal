package com.u1.servicepal.internal.macos;

import com.u1.servicepal.Installation;
import java.nio.file.Path;

/**
 * One launchd definition directory: where it is, which {@link Installation} it represents, and
 * which runtime {@link LaunchdDomain} its services are managed in.
 */
public record LaunchdDir(Path dir, Installation installation, LaunchdDomain domain) {
}
