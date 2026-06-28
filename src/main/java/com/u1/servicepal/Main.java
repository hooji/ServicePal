package com.u1.servicepal;

import com.u1.servicepal.cli.DiscoverCli;
import com.u1.servicepal.gui.ServicePalGui;

/**
 * The runnable jar's entry point (its {@code Main-Class}). It routes to the desktop GUI only on
 * an explicit {@code -ui} / {@code --ui} (or {@code gui}) first argument; <em>every</em> other
 * invocation — including no arguments at all — delegates to {@link DiscoverCli}, preserving the
 * historical {@code java -jar servicepal.jar} behavior that CI and users rely on.
 *
 * <p>The GUI is deliberately opt-in. The same jar doubles as the classpath for the Windows
 * service host, which the backend launches as
 * {@code javaw -cp <jar> com.u1.servicepal.internal.windows.ServiceHost --id <id>} — it names its
 * own main class and never consults this dispatcher — so nothing here can affect the host. Keeping
 * the no-argument default as the read-only discovery CLI means the jar's role as the Windows
 * "execution helper" is untouched by adding the UI.
 */
public final class Main {

	private Main() {
	}

	public static void main(final String[] args) {
		if (args.length > 0 && isUiFlag(args[0])) {
			final String[] rest = new String[args.length - 1];
			System.arraycopy(args, 1, rest, 0, rest.length);
			ServicePalGui.main(rest);
			return;
		}
		DiscoverCli.main(args);
	}

	private static boolean isUiFlag(final String arg) {
		return "-ui".equals(arg) || "--ui".equals(arg) || "gui".equals(arg) || "--gui".equals(arg);
	}
}
