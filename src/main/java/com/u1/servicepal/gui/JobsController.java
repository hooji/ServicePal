package com.u1.servicepal.gui;

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

	/** Load the managed jobs and repaint, preserving (or overriding) the selection. */
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
		for (final ServiceStatus status : manager.listManaged()) {
			ServiceSpec spec = null;
			try {
				spec = manager.read(status.id());
			} catch (final RuntimeException ignored) {
				// leave spec null; the row still shows id + live state
			}
			jobs.add(new Job(spec, status));
		}
		return jobs;
	}

	private void onSelect(final Job job) {
		detail.showJob(job);
		updateActionsFor(job);
	}

	@Override
	public void addJob() {
		final JobForm form = JobDialog.showDialog(owner, "Add Job", JobDialog.blankForm());
		if (form != null) {
			save(form);
		}
	}

	@Override
	public void editSelected() {
		final Job job = list.selectedJob();
		if (job == null) {
			return;
		}
		final JobForm form = JobDialog.showDialog(owner, "Edit Job", toForm(job));
		if (form != null) {
			save(form);
		}
	}

	@Override
	public void removeSelected() {
		final Job job = list.selectedJob();
		if (job == null) {
			return;
		}
		final int choice = JOptionPane.showConfirmDialog(owner,
				"Remove “" + job.displayName() + "”?\nThis stops it and deletes its definition.",
				"Remove job", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return;
		}
		final String id = job.id();
		runAsync("remove the job", () -> manager.uninstall(id), null);
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

	private void save(final JobForm form) {
		final ServiceSpec spec = JobSpecs.fromForm(form, manager.capabilities());
		runAsync("save the job", () -> {
			manager.install(spec);
			if (form.autoStart()) {
				manager.enable(spec.id());
				manager.start(spec.id());
			} else {
				try {
					manager.disable(spec.id());
				} catch (final RuntimeException ignored) {
					// not all platforms allow disabling a never-enabled job; not fatal
				}
			}
		}, spec.id());
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
		final boolean has = job != null;
		final boolean running = has && job.status().state() == RunState.RUNNING;
		startBtn.setEnabled(has && !running);
		stopBtn.setEnabled(running);
		restartBtn.setEnabled(running);
		removeBtn.setEnabled(has);
	}

	private void updateStatusBar(final List<Job> jobs) {
		int running = 0;
		for (final Job job : jobs) {
			if (job.status().state() == RunState.RUNNING) {
				running++;
			}
		}
		final String count = jobs.size() + (jobs.size() == 1 ? " job" : " jobs") + " · " + running
				+ " running";
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
		final String lower = text.toLowerCase();
		return lower.contains("access is denied") || lower.contains("permission")
				|| lower.contains("denied") || lower.contains("administrator")
				|| lower.contains("root");
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
				job.status().enabled(), spec.restart());
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
