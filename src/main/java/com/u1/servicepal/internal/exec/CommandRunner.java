package com.u1.servicepal.internal.exec;

import java.util.List;

/**
 * The single seam through which the library runs external programs (launchctl, systemctl,
 * rc-service, sc). Stub it in tests so backends run off-platform.
 */
public interface CommandRunner {

	CommandResult run(List<String> command);
}
