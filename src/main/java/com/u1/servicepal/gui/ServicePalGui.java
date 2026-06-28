package com.u1.servicepal.gui;

import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.internal.Platforms;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.awt.Color;
import java.nio.file.Path;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.nimbus.NimbusLookAndFeel;

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
			installDarkLookAndFeel();
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
			installDarkLookAndFeel();
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
				installDarkLookAndFeel();
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
	 * The GUI defaults to a dark theme on every platform. We theme the JDK's built-in Nimbus
	 * look-and-feel by overriding its base palette (no third-party dependency) — Nimbus derives most
	 * component colors from a handful of base keys, so a dark palette propagates consistently.
	 */
	private static void installDarkLookAndFeel() {
		try {
			UIManager.setLookAndFeel(new NimbusLookAndFeel());
			applyDarkPalette();
		} catch (final Exception primary) {
			try {
				UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
			} catch (final Exception ignored) {
				// keep the default look-and-feel
			}
		}
	}

	private static void applyDarkPalette() {
		final Color base = new Color(0x3C, 0x3F, 0x41);        // panels, toolbar, headers
		final Color control = new Color(0x45, 0x49, 0x4A);     // buttons, combos, scrollbars
		final Color field = new Color(0x2B, 0x2B, 0x2B);       // text fields, lists, tables
		final Color textColor = new Color(0xCB, 0xCB, 0xCB);   // primary text
		final Color disabled = new Color(0x80, 0x84, 0x88);
		final Color selection = new Color(0x2D, 0x5B, 0xA3);   // selection highlight
		final Color selectedText = new Color(0xF2, 0xF2, 0xF2);
		final Color focus = new Color(0x4A, 0x90, 0xD9);

		UIManager.put("control", base);
		UIManager.put("info", base);
		UIManager.put("background", base);
		UIManager.put("nimbusBase", control);
		UIManager.put("nimbusBlueGrey", control);
		UIManager.put("nimbusLightBackground", field);
		UIManager.put("text", textColor);
		UIManager.put("nimbusDisabledText", disabled);
		UIManager.put("nimbusFocus", focus);
		UIManager.put("nimbusSelectionBackground", selection);
		UIManager.put("nimbusSelection", selection);
		UIManager.put("nimbusSelectedText", selectedText);
		UIManager.put("textHighlight", selection);
		UIManager.put("textHighlightText", selectedText);
		UIManager.put("nimbusInfoBlue", new Color(0x3D, 0x61, 0x85));
		UIManager.put("menu", base);
		UIManager.put("menuText", textColor);
		UIManager.put("scrollbar", base);
		UIManager.put("Table.background", field);
		UIManager.put("Table.alternateRowColor", new Color(0x32, 0x35, 0x37));
		// Standard component selection keys, so the renderer-driven master list highlights clearly.
		UIManager.put("Table.selectionBackground", selection);
		UIManager.put("Table.selectionForeground", selectedText);
		UIManager.put("List.selectionBackground", selection);
		UIManager.put("List.selectionForeground", selectedText);
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
