package com.u1.servicepal.gui;

import com.u1.servicepal.Capabilities;
import com.u1.servicepal.Platform;
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.RunState;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.awt.BorderLayout;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JToolBar;
import javax.swing.SwingWorker;

/**
 * The presenter: it owns the toolbar, master list, detail panel, and status bar, and implements
 * the {@link JobActions} those views invoke. Every call into the {@link ServiceManager} runs on a
 * {@link SwingWorker} (off the EDT) because the backends shell out to {@code launchctl} /
 * {@code systemctl} / {@code sc}; results and errors are marshalled back to the EDT.
 */
final class JobsController implements JobActions {

	private final ServiceManager manager;
	private final Window owner;

	private final JobListPanel list = new JobListPanel(this::onSelect);
	private final JobDetailPanel detail = new JobDetailPanel(this);
	private final JLabel statusBar = new JLabel();
	private final JButton addBtn = new JButton("+ Add Job");
	private final JButton startBtn = new JButton("Start");
	private final JButton stopBtn = new JButton("Stop");
	private final JButton restartBtn = new JButton("Restart");
	private final JButton removeBtn = new JButton("Remove");
	private final JButton refreshBtn = new JButton("Refresh");
	private final JPanel root = new JPanel(new BorderLayout());

	private boolean busy;

	JobsController(final ServiceManager manager, final Window owner) {
		this.manager = manager;
		this.owner = owner;
		buildLayout();
		wireButtons();
		updateActionsFor(null);
	}

	JComponent component() {
		return root;
	}

	private void buildLayout() {
		final JToolBar toolbar = new JToolBar();
		toolbar.setFloatable(false);
		toolbar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		toolbar.add(addBtn);
		toolbar.addSeparator();
		toolbar.add(startBtn);
		toolbar.add(stopBtn);
		toolbar.add(restartBtn);
		toolbar.addSeparator();
		toolbar.add(removeBtn);
		toolbar.add(javax.swing.Box.createHorizontalGlue());
		toolbar.add(refreshBtn);

		final JScrollPane listScroll = new JScrollPane(list);
		listScroll.setMinimumSize(new java.awt.Dimension(240, 0));
		final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detail);
		split.setResizeWeight(0.42);
		split.setDividerLocation(360);
		split.setBorder(null);

		statusBar.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

		root.add(toolbar, BorderLayout.NORTH);
		root.add(split, BorderLayout.CENTER);
		root.add(statusBar, BorderLayout.SOUTH);
	}

	private void wireButtons() {
		addBtn.addActionListener(e -> addJob());
		startBtn.addActionListener(e -> startSelected());
		stopBtn.addActionListener(e -> stopSelected());
		restartBtn.addActionListener(e -> restartSelected());
		removeBtn.addActionListener(e -> removeSelected());
		refreshBtn.addActionListener(e -> refresh());
	}

	/** Load every discoverable job and repaint, preserving (or overriding) the selection. */
	@Override
	public void refresh() {
		refreshAndSelect(null);
	}

	private void refreshAndSelect(final String idToSelect) {
		if (busy) {
			return;
		}
		setBusy(true);
		new SwingWorker<List<Job>, Void>() {
			private Throwable error;

			@Override
			protected List<Job> doInBackground() {
				try {
					return loadJobs();
				} catch (final RuntimeException e) {
					error = e;
					return new ArrayList<>();
				}
			}

			@Override
			protected void done() {
				setBusy(false);
				if (error != null) {
					showError("load the service list", error);
				}
				List<Job> jobs;
				try {
					jobs = get();
				} catch (final Exception e) {
					jobs = new ArrayList<>();
				}
				applyJobs(jobs, idToSelect);
			}
		}.execute();
	}

	/** Load and apply synchronously on the EDT — used only by the screenshot harness. */
	void loadSynchronously(final String idToSelect) {
		applyJobs(loadJobs(), idToSelect);
	}

	/** Move keyboard focus to the master list so the selected row highlights (not dimmed). */
	void focusList() {
		list.requestFocusInWindow();
	}

	private void applyJobs(final List<Job> jobs, final String idToSelect) {
		list.setJobs(jobs);
		if (idToSelect != null) {
			list.selectId(idToSelect);
		}
		list.selectFirstIfAny();
		final Job selected = list.selectedJob();
		detail.showJob(selected);
		updateActionsFor(selected);
		updateStatusBar(jobs);
	}

	private List<Job> loadJobs() {
		final List<Job> jobs = new ArrayList<>();
		for (final ServiceStatus status : manager.list()) {
			ServiceSpec spec = null;
			try {
				// Read the parsed definition (works for unmanaged services too on macOS/systemd/
				// OpenRC); fall back to id + live state if it can't be parsed.
				spec = status.installation() != null
						? manager.read(status.id(), status.installation())
						: manager.read(status.id());
			} catch (final RuntimeException ignored) {
				// leave spec null; the row still shows id + live state
			}
			jobs.add(new Job(spec, status));
		}
		// Alphabetical within each section (the table model groups managed-vs-other).
		jobs.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
		return jobs;
	}

	private void onSelect(final Job job) {
		detail.showJob(job);
		updateActionsFor(job);
	}

	@Override
	public void addJob() {
		final JobForm form = JobDialog.showDialog(owner, "Add Job", JobDialog.blankForm(),
				supportsScheduling());
		if (form != null) {
			save(form, false);
		}
	}

	/** Whether this platform can schedule jobs (so the form offers the "On a schedule" mode). */
	private boolean supportsScheduling() {
		final Capabilities caps = manager.capabilities();
		return caps.calendarSchedule() || caps.intervalSchedule();
	}

	@Override
	public void editSelected() {
		final Job job = list.selectedJob();
		if (job == null) {
			return;
		}
		final boolean foreign = !job.managed();
		if (foreign && !confirmForeignEdit(job)) {
			return;
		}
		final JobForm form = JobDialog.showDialog(owner, "Edit Job", toForm(job), supportsScheduling());
		if (form != null) {
			save(form, foreign);   // editing a foreign service overwrites it (and adopts it)
		}
	}

	/** Warn before rewriting a service we did not create (the edit adopts it into ServicePal). */
	private boolean confirmForeignEdit(final Job job) {
		final int ok = JOptionPane.showConfirmDialog(owner,
				"“" + job.displayName() + "” was not created by ServicePal.\n\n"
						+ "Editing it rewrites its definition in ServicePal's format — settings "
						+ "ServicePal doesn't support may be lost — and ServicePal will then manage "
						+ "it (marked as adopted).\n\nContinue?",
				"Edit a service ServicePal didn't create", JOptionPane.OK_CANCEL_OPTION,
				JOptionPane.WARNING_MESSAGE);
		return ok == JOptionPane.OK_OPTION;
	}

	@Override
	public void removeSelected() {
		final Job job = list.selectedJob();
		if (job == null) {
			return;
		}
		final boolean foreign = !job.managed();
		final String message = foreign
				? "Remove “" + job.displayName() + "”?\n\nThis service was NOT created by "
						+ "ServicePal. Removing it stops it and deletes its definition from this "
						+ "computer."
				: "Remove “" + job.displayName() + "”?\nThis stops it and deletes its definition.";
		final int choice = JOptionPane.showConfirmDialog(owner, message,
				"Remove job", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		final String id = job.id();
		runAsync("remove the job", () -> manager.uninstall(id, foreign), null);
	}

	@Override
	public void startSelected() {
		final Job job = list.selectedJob();
		if (job != null) {
			final String id = job.id();
			runAsync("start the job", () -> manager.start(id), id);
		}
	}

	@Override
	public void stopSelected() {
		final Job job = list.selectedJob();
		if (job != null) {
			final String id = job.id();
			runAsync("stop the job", () -> manager.stop(id), id);
		}
	}

	@Override
	public void restartSelected() {
		final Job job = list.selectedJob();
		if (job != null) {
			final String id = job.id();
			runAsync("restart the job", () -> manager.restart(id), id);
		}
	}

	private void save(final JobForm form, final boolean overwriteUnmanaged) {
		final ServiceSpec spec = JobSpecs.fromForm(form, manager.capabilities());
		runAsync("save the job", () -> {
			// Capture the prior definition + run state so a changed command can be applied to a
			// service that is already running. Otherwise the edit would not take effect until a
			// manual restart on systemd/OpenRC/Windows (only macOS reloads on install).
			final ServiceSpec previous = manager.read(spec.id());
			final boolean wasRunning = manager.status(spec.id()).state() == RunState.RUNNING;
			applySave(manager, previous, spec, wasRunning, form.autoStart(), overwriteUnmanaged);
		}, spec.id());
	}

	/**
	 * Apply a saved job: upsert the definition, then bring it to the requested state. If it was
	 * already running and a runtime-affecting field changed, <em>restart</em> it so the new
	 * definition takes effect on every platform (a cosmetic rename alone does not bounce it).
	 * {@code overwriteUnmanaged} is true when editing a service we did not create (adopting it).
	 * Package-visible and Swing-free so it unit-tests directly.
	 */
	static void applySave(final ServiceManager mgr, final ServiceSpec previous,
			final ServiceSpec spec, final boolean wasRunning, final boolean autoStart,
			final boolean overwriteUnmanaged) {
		mgr.install(spec, overwriteUnmanaged);
		if (spec.schedule() != null) {
			armSchedule(mgr, spec.id());   // a scheduled job: arm it, never "start now"
			return;
		}
		if (!autoStart) {
			try {
				mgr.disable(spec.id());
			} catch (final RuntimeException ignored) {
				// not all platforms allow disabling a never-enabled job; not fatal
			}
			return;
		}
		mgr.enable(spec.id());
		if (!wasRunning) {
			mgr.start(spec.id());
		} else if (runtimeChanged(previous, spec)) {
			mgr.restart(spec.id());   // apply the changed definition to the running instance
		}
		// running + only a cosmetic change (e.g. rename): leave it running as-is.
	}

	/**
	 * Arm a scheduled job so it fires on its schedule and persists across reboot, <em>without</em>
	 * running it now. {@code enable} arms it on every platform (systemd {@code .timer} symlink,
	 * OpenRC crontab entry, Windows task enabled, launchd load). A systemd {@code .timer} additionally
	 * needs an explicit (re)start to activate in the current session and to pick up an edited schedule
	 * — and that starts the <em>timer</em>, not the job. On the other platforms {@code enable} already
	 * armed it and a "start"/"run" would execute the command immediately, so we don't.
	 */
	private static void armSchedule(final ServiceManager mgr, final String id) {
		mgr.enable(id);
		if (mgr.platform() == Platform.LINUX_SYSTEMD) {
			mgr.restart(id);
		}
	}

	/** Whether a field that affects the running process changed (vs. just the display name). */
	static boolean runtimeChanged(final ServiceSpec previous, final ServiceSpec next) {
		if (previous == null) {
			return true;
		}
		return !previous.command().equals(next.command())
				|| !Objects.equals(previous.workingDirectory(), next.workingDirectory())
				|| !previous.environment().equals(next.environment())
				|| previous.restart() != next.restart()
				|| !Objects.equals(previous.runAs(), next.runAs())
				|| !Objects.equals(previous.schedule(), next.schedule());
	}

	/** Run a mutating op off the EDT, then refresh (selecting {@code idToSelect} if given). */
	private void runAsync(final String description, final Runnable work, final String idToSelect) {
		if (busy) {
			return;
		}
		setBusy(true);
		new SwingWorker<Void, Void>() {
			private Throwable error;

			@Override
			protected Void doInBackground() {
				try {
					work.run();
				} catch (final Throwable t) {
					error = t;
				}
				return null;
			}

			@Override
			protected void done() {
				setBusy(false);
				if (error != null) {
					showError(description, error);
				}
				refreshAndSelect(idToSelect);
			}
		}.execute();
	}

	private void setBusy(final boolean value) {
		this.busy = value;
		root.setCursor(java.awt.Cursor.getPredefinedCursor(
				value ? java.awt.Cursor.WAIT_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
		if (value) {
			addBtn.setEnabled(false);
			startBtn.setEnabled(false);
			stopBtn.setEnabled(false);
			restartBtn.setEnabled(false);
			removeBtn.setEnabled(false);
			refreshBtn.setEnabled(false);
		} else {
			addBtn.setEnabled(true);
			refreshBtn.setEnabled(true);
		}
	}

	private void updateActionsFor(final Job job) {
		if (busy) {
			return;
		}
		// Every discovered job is actionable. Editing/removing one we did not create takes the
		// "overwrite unmanaged" path (with a warning + an adoption marker) — see edit/removeSelected.
		final boolean has = job != null;
		final boolean running = has && job.status().state() == RunState.RUNNING;
		startBtn.setEnabled(has && !running);
		stopBtn.setEnabled(running);
		restartBtn.setEnabled(running);
		removeBtn.setEnabled(has);
	}

	private void updateStatusBar(final List<Job> jobs) {
		int created = 0;
		int adopted = 0;
		int running = 0;
		for (final Job job : jobs) {
			if (job.managed()) {
				if (job.adopted()) {
					adopted++;
				} else {
					created++;
				}
			}
			if (job.status().state() == RunState.RUNNING) {
				running++;
			}
		}
		final int others = jobs.size() - created - adopted;
		final StringBuilder count = new StringBuilder();
		count.append(created).append(" created here");
		if (adopted > 0) {
			count.append(" · ").append(adopted).append(" adopted");
		}
		if (others > 0) {
			count.append(" · ").append(others).append(" other");
		}
		count.append(" · ").append(running).append(" running");
		statusBar.setText(platformLabel(manager.platform()) + "        " + count);
	}

	private void showError(final String action, final Throwable error) {
		final String detailText = error.getMessage() != null ? error.getMessage() : error.toString();
		final StringBuilder message = new StringBuilder("Could not ").append(action).append(".\n\n")
				.append(detailText);
		if (needsPrivilegeHint(detailText)) {
			message.append("\n\nThis platform installs background services machine-wide, so "
					+ "ServicePal needs to be run as an administrator (Windows) or with sudo "
					+ "(Linux).");
		}
		JOptionPane.showMessageDialog(owner, message.toString(), "ServicePal",
				JOptionPane.ERROR_MESSAGE);
	}

	private static boolean needsPrivilegeHint(final String text) {
		// Match only genuine permission failures. Notably NOT the bare word "root": launchctl
		// appends "Try re-running the command as root for richer errors" to many failures
		// (including the transient reload race on a per-user agent), which is not a privilege issue.
		final String lower = text.toLowerCase();
		return lower.contains("access is denied")            // Windows
				|| lower.contains("permission denied")        // POSIX
				|| lower.contains("operation not permitted")  // POSIX EPERM
				|| lower.contains("must be run as")
				|| lower.contains("requires administrator")
				|| lower.contains("administrator privilege");
	}

	private static JobForm toForm(final Job job) {
		final ServiceSpec spec = job.spec();
		if (spec == null) {
			return new JobForm(job.id(), job.displayName(), "", "", "", job.status().enabled(),
					RestartPolicy.NEVER);
		}
		final List<String> command = spec.command();
		final String program = command.isEmpty() ? "" : command.get(0);
		final String args = joinArgs(command);
		final String folder = spec.workingDirectory() == null ? "" : spec.workingDirectory().toString();
		return new JobForm(spec.id(), spec.displayName(), program, args, folder,
				job.status().enabled(), spec.restart(), spec.schedule());
	}

	/** Re-join the argument tail, quoting any element that contains whitespace. */
	private static String joinArgs(final List<String> command) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 1; i < command.size(); i++) {
			if (sb.length() > 0) {
				sb.append(' ');
			}
			final String arg = command.get(i);
			if (hasWhitespace(arg)) {
				sb.append('"').append(arg).append('"');
			} else {
				sb.append(arg);
			}
		}
		return sb.toString();
	}

	private static boolean hasWhitespace(final String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isWhitespace(s.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	static String platformLabel(final Platform platform) {
		return switch (platform) {
			case MACOS_LAUNCHD -> "macOS · launchd";
			case LINUX_SYSTEMD -> "Linux · systemd";
			case LINUX_OPENRC -> "Linux · OpenRC";
			case WINDOWS -> "Windows · services";
		};
	}
}
