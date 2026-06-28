package com.u1.servicepal.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.internal.Platforms;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.nio.file.Path;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The desktop GUI entry point (reached via {@code java -jar servicepal.jar -ui}). It runs in one of
 * four modes:
 *
 * <ul>
 *   <li>default — the real {@link ServiceManager} for this platform, interactive;</li>
 *   <li>{@code --demo} — an in-memory {@link DemoServiceManager}, interactive (no OS changes);</li>
 *   <li>{@code --screenshot <dir>} — demo data; capture the main window and the Add-Job dialog to
 *       PNGs, then exit (deterministic, used by CI on every platform);</li>
 *   <li>{@code --screenshot-live <dir>} — the real backend; install/start a throwaway job, capture
 *       the window showing it actually running, uninstall, then exit (end-to-end proof).</li>
 * </ul>
 *
 * <p>Optional: {@code --platform <NAME>} forces the demo platform; {@code --tag <t>} names the PNG.
 */
public final class ServicePalGui {

	private static final String LIVE_ID = "com.u1.servicepal.guidemo";

	private ServicePalGui() {
	}

	public static void main(final String[] args) {
		final Args parsed = Args.parse(args);
		try {
			if (parsed.screenshotDir != null) {
				runDemoScreenshots(parsed);
			} else if (parsed.liveScreenshotDir != null) {
				runLiveScreenshot(parsed);
			} else {
				runInteractive(parsed);
			}
		} catch (final Throwable t) {
			System.err.println("ServicePal GUI error: " + t);
			t.printStackTrace();
			System.exit(1);
		}
	}

	// --- interactive ---

	private static void runInteractive(final Args parsed) {
		SwingUtilities.invokeLater(() -> {
			installLookAndFeel();
			final ServiceManager manager;
			try {
				manager = parsed.demo
						? DemoData.forPlatform(resolvePlatform(parsed))
						: ServiceManager.getServiceManager();
			} catch (final RuntimeException e) {
				JOptionPane.showMessageDialog(null,
						"ServicePal could not start on this system:\n\n" + e.getMessage(),
						"ServicePal", JOptionPane.ERROR_MESSAGE);
				return;
			}
			final MainWindow window = new MainWindow(manager);
			window.setVisible(true);
			window.controller().refresh();
			window.controller().focusList();
		});
	}

	// --- demo screenshots (deterministic, all platforms) ---

	private static void runDemoScreenshots(final Args parsed) throws Exception {
		final Platform platform = resolvePlatform(parsed);
		final String tag = parsed.tag != null ? parsed.tag : tagFor(platform);
		final MainWindow[] win = new MainWindow[1];
		SwingUtilities.invokeAndWait(() -> {
			installLookAndFeel();
			win[0] = new MainWindow(DemoData.forPlatform(platform));
			win[0].setLocation(40, 40);
			win[0].setVisible(true);
			win[0].controller().loadSynchronously(null);
			win[0].controller().focusList();
		});
		Screenshotter.capture(win[0], parsed.screenshotDir.resolve("main-" + tag + ".png"));

		final JDialog[] dialog = new JDialog[1];
		SwingUtilities.invokeAndWait(() -> {
			dialog[0] = JobDialog.buildForScreenshot(win[0], sampleAddForm(platform));
			dialog[0].setLocation(win[0].getX() + 130, win[0].getY() + 80);
			dialog[0].setVisible(true);
		});
		Screenshotter.capture(dialog[0], parsed.screenshotDir.resolve("add-job-" + tag + ".png"));
		System.out.println("DEMO SCREENSHOTS DONE (" + tag + ")");
		System.exit(0);
	}

	private static JobForm sampleAddForm(final Platform platform) {
		final boolean win = platform == Platform.WINDOWS;
		return new JobForm(null, "Nightly Backup",
				win ? "C:\\Tools\\backup.exe" : "/usr/local/bin/backup", "--daemon",
				win ? "C:\\Backups" : "/var/backups", true, RestartPolicy.ALWAYS);
	}

	// --- live screenshot (real backend; macOS + Windows in CI) ---

	private static void runLiveScreenshot(final Args parsed) throws Exception {
		final ServiceManager manager = ServiceManager.getServiceManager();
		final Platform platform = manager.platform();
		final String tag = parsed.tag != null ? parsed.tag : tagFor(platform);
		final ServiceSpec spec = JobSpecs.fromForm(liveForm(platform), manager.capabilities());
		int exit = 0;
		try {
			if (manager.isInstalled(spec.id())) {
				manager.uninstall(spec.id(), true);
			}
			manager.installEnableStart(spec);
			ServiceStatus status = manager.status(spec.id());
			for (int i = 0; i < 40 && status.state() != RunState.RUNNING; i++) {
				Thread.sleep(500);
				status = manager.status(spec.id());
			}
			System.out.println("live job state=" + status.state() + " pid=" + status.pid());
			final MainWindow[] win = new MainWindow[1];
			SwingUtilities.invokeAndWait(() -> {
				installLookAndFeel();
				win[0] = new MainWindow(manager);
				win[0].setLocation(40, 40);
				win[0].setVisible(true);
				win[0].controller().loadSynchronously(spec.id());
				win[0].controller().focusList();
			});
			Screenshotter.capture(win[0], parsed.liveScreenshotDir.resolve("live-" + tag + ".png"));
			if (status.state() == RunState.RUNNING) {
				System.out.println("LIVE SCREENSHOT OK (" + tag + ")");
			} else {
				System.out.println("LIVE SCREENSHOT WARNING: job did not reach RUNNING");
				exit = 1;
			}
		} catch (final Throwable t) {
			System.out.println("LIVE SCREENSHOT FAILED: " + t);
			t.printStackTrace(System.out);
			exit = 1;
		} finally {
			try {
				if (manager.isInstalled(spec.id())) {
					manager.uninstall(spec.id(), true);
				}
			} catch (final Throwable t) {
				System.out.println("cleanup warning: " + t);
			}
		}
		System.exit(exit);
	}

	private static JobForm liveForm(final Platform platform) {
		if (platform == Platform.WINDOWS) {
			return new JobForm(LIVE_ID, "ServicePal Demo Job", "ping", "-n 300 127.0.0.1", "",
					true, RestartPolicy.ALWAYS);
		}
		return new JobForm(LIVE_ID, "ServicePal Demo Job", "/bin/sleep", "300", "", true,
				RestartPolicy.ALWAYS);
	}

	// --- shared ---

	/**
	 * Install the look-and-feel. On macOS we use FlatLaf, following the system light/dark setting
	 * ({@link FlatLightLaf} / {@link FlatDarkLaf}). On Windows and Linux we use the platform's
	 * native look-and-feel.
	 */
	private static void installLookAndFeel() {
		if (isMacOs()) {
			// Follow the system setting (macOS):
			if (systemIsDark()) {
				FlatDarkLaf.setup();
			} else {
				FlatLightLaf.setup();
			}
			return;
		}
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (final Exception e) {
			// keep the default cross-platform look-and-feel
		}
	}

	private static boolean isMacOs() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
	}

	/**
	 * Whether macOS is currently in dark mode. Reads the global {@code AppleInterfaceStyle} default,
	 * which is {@code "Dark"} in dark mode and unset (so the read fails) in light mode. Any failure
	 * is treated as light.
	 */
	private static boolean systemIsDark() {
		try {
			final Process process = new ProcessBuilder(
					"defaults", "read", "-g", "AppleInterfaceStyle")
					.redirectErrorStream(true).start();
			final byte[] out = process.getInputStream().readAllBytes();
			process.waitFor();
			return new String(out, java.nio.charset.StandardCharsets.UTF_8)
					.toLowerCase(java.util.Locale.ROOT).contains("dark");
		} catch (final Exception e) {
			return false;   // default to light if the setting can't be read
		}
	}

	private static Platform resolvePlatform(final Args parsed) {
		if (parsed.platform != null) {
			return parsed.platform;
		}
		try {
			return Platforms.detect();
		} catch (final RuntimeException e) {
			return Platform.LINUX_SYSTEMD;   // demo fallback on an undetectable host
		}
	}

	private static String tagFor(final Platform platform) {
		return switch (platform) {
			case MACOS_LAUNCHD -> "macos";
			case WINDOWS -> "windows";
			case LINUX_SYSTEMD -> "linux";
			case LINUX_OPENRC -> "linux-openrc";
		};
	}

	/** Parsed command-line options for the GUI. */
	private static final class Args {

		private Path screenshotDir;
		private Path liveScreenshotDir;
		private boolean demo;
		private Platform platform;
		private String tag;

		static Args parse(final String[] args) {
			final Args parsed = new Args();
			for (int i = 0; i < args.length; i++) {
				switch (args[i]) {
					case "--screenshot" ->
							parsed.screenshotDir = Path.of(value(args, ++i, "--screenshot"));
					case "--screenshot-live" ->
							parsed.liveScreenshotDir = Path.of(value(args, ++i, "--screenshot-live"));
					case "--demo" -> parsed.demo = true;
					case "--platform" ->
							parsed.platform = Platform.valueOf(value(args, ++i, "--platform"));
					case "--tag" -> parsed.tag = value(args, ++i, "--tag");
					default -> System.err.println("ignoring unknown GUI argument: " + args[i]);
				}
			}
			return parsed;
		}

		private static String value(final String[] args, final int i, final String flag) {
			if (i >= args.length) {
				throw new IllegalArgumentException("missing value for " + flag);
			}
			return args[i];
		}
	}
}
