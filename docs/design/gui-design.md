# Desktop GUI design

A small, cross-platform desktop GUI on top of the ServicePal library. It deliberately exposes
**only the 90%+ common case** — set up jobs to be auto-started and kept running in the background —
and hides every platform-specific capability the library otherwise offers.

## Scope

**In:** see your background jobs and whether each is running; add a job (name + command, optional
working folder); choose between **keep it running** (*start automatically* + *what to do if it
stops*) and **on a schedule** (a simple Repeat picker — every-N-minutes / daily / weekly); start /
stop / restart; remove. The list shows **every service the platform can discover** (`list()`), split
into up to three sections: **“Created with ServicePal”**, **“Adopted by ServicePal”** (services it
installed over but did not originally create), and **“Other background jobs”** (everything else found
on the machine). On macOS that last group is the third-party launchd agents/daemons in
`~/Library/LaunchAgents` and `/Library/Launch*`, not Apple’s hundreds of services under
`/System/Library` (which the backend never touches).

**Deliberately out (hidden):** `RunAs` named-user / system-daemon selection, the per-platform option
blocks (`.mac()/.systemd()/.windows()/.openrc()`), explicit `Installation` choice, env vars and
log-file paths (a later "Advanced" disclosure). The UI is **identical on all four platforms**; only
the native window chrome differs.

## Three categories, and the adoption marker

Two side-band markers classify each discovered service (`ServiceStatus.managed()` /
`ServiceStatus.adopted()`): **created** (we wrote it from scratch — managed, not adopted),
**adopted** (we installed over a service we did not create — managed *and* adopted), and **foreign**
(no marker). The master list groups into a section per category, in that order, with a non-selectable
header row each (a bold, muted label + count and a thin divider line). Header rows are skipped by
mouse and keyboard selection (`JobListPanel.changeSelection`), so the groups read as one continuous,
navigable list.

**Every job is actionable**, including foreign ones — start / stop / restart operate by id with no
special handling. **Editing or removing a foreign service is allowed but confirmed first**, because
those rewrite or delete a service we did not create:

- *Edit* a foreign job → a warning that it will be rewritten in ServicePal’s format (settings we
  don’t model may be lost) and **adopted**; on confirm, `install(spec, overwriteUnmanaged=true)`
  stamps the adoption marker, and the job moves to the “Adopted” section on the next refresh.
- *Remove* a foreign job → a stronger confirmation; on confirm, `uninstall(id, true)`.

So the awkward `yesDoThisToAServiceIDidNotCreate` override is still the only path that touches a
service we didn’t create — the GUI just takes it deliberately, behind a warning, instead of hiding it.
The adoption marker keeps discovery honest: an adopted service is shown as managed **without** ever
being mislabelled as one we created. (On Windows, discovery is machine-wide — it enumerates every
Win32 service via `EnumServicesStatusExW` — so foreign services show in the "Other background jobs"
section too, by their service key name.)

## The one cross-platform decision: the auto privilege model

The GUI never mentions `RunAs` or `Installation`. Instead `JobSpecs.fromForm` picks identity from
the platform's `capabilities()`:

- `perUserInstall` supported (macOS, systemd) → `asCurrentUser()` → **per-user**, no admin needed,
  auto-starts at login.
- otherwise (Windows, OpenRC — system-wide only) → `asSystemDaemon()` → **system-wide**, auto-starts
  at boot, needs the app to run elevated (admin / sudo).

This keeps the UI uniform and avoids privilege prompts on the platforms that don't need them. When a
privileged op fails, the controller shows a friendly "run as administrator / with sudo" hint.

## Scheduling: keep-running vs on-a-schedule

The add/edit form has a **mode toggle** — *Keep it running* vs *On a schedule*. The two modes are
mutually exclusive and swap the lower half of the form (a `CardLayout` in `JobFormPanel`):

- **Keep it running** (a daemon): the existing *Start automatically* + *If it stops* (`RestartPolicy`)
  fields.
- **On a schedule** (`SchedulePanel`): a **Repeat** picker — *Every N minutes* (a dropdown of
  `5/10/15/20/30`), *Daily at HH:MM*, or *Weekly on <day> at HH:MM* — that maps to a
  `Schedule` (`IntervalSchedule` / `CalendarSchedule`). The minute options are all divisors of 60 so
  every choice round-trips on **all four** backends (launchd `StartCalendarInterval`/`StartInterval`,
  systemd `.timer`, the OpenRC cron fallback — which can only express intervals dividing a minute/hour
  — and Windows Task Scheduler). The toggle is shown only where `capabilities().calendarSchedule()`
  or `intervalSchedule()` is true (all current platforms; it hides on a future backend that can't
  schedule).

`JobSpecs.fromForm` carries the picked `Schedule` onto the `ServiceSpec` and normalizes the
keep-running fields a scheduled job can't use — `autoStart=false` and `restart=NEVER` (a scheduled
run is a oneshot, and the builder forbids `schedule` + `RestartPolicy.ALWAYS`).

**Arming, not starting.** Saving a scheduled job installs it and then *arms* it via
`JobsController.applySave` → `enable` (which persists the schedule on every platform: a systemd
`.timer` symlink, an OpenRC crontab entry, a Windows task enabled, a launchd plist loaded) —
**never** `start`, because a scheduled job runs on its schedule, not now. A systemd `.timer`
additionally needs an explicit `restart` to activate in the current session and to pick up an edited
schedule; that restarts the **timer**, not the job (on the other platforms `enable` already armed it
and a "start"/"run" would execute the command immediately, so we don't).

**Showing a schedule.** A scheduled job has no long-running process, so its raw run state is
STOPPED/UNKNOWN between runs; `StatusVisuals` relabels that as **“Scheduled”** (a calm blue) in both
the list's status column and the detail header — but a job that is genuinely mid-run reads “Running”
and a failed run reads “Failed”. The detail panel swaps the *Start automatically* / *If it stops*
rows for **Schedule** (a human summary like “Daily at 02:00”, via `ScheduleText`), **Next run**, and
**Last run** (`ServiceStatus.nextRun()` / `lastRun()` — Windows and systemd give both; OpenRC/cron
computes next-run only; launchd exposes neither, so those read “—”). The run buttons are disabled for
a scheduled job (Edit/Remove still apply).

## Toolkit: Swing, with FlatLaf on macOS

Swing is bundled in the JDK, runs on the JDK 25 baseline everywhere, and compiles into the existing
shaded jar. (JavaFX was rejected: heavier, platform-native dependencies, awkward headless CI.)

The look-and-feel is chosen per platform in `ServicePalGui.installLookAndFeel`:

- **macOS → [FlatLaf](https://www.formdev.com/flatlaf/)**, following the system light/dark setting:
  `systemIsDark()` reads `defaults read -g AppleInterfaceStyle` (`"Dark"` in dark mode, unset in
  light) and installs `FlatDarkLaf` or `FlatLightLaf` accordingly. FlatLaf gives a clean, modern
  dark theme that the stock Aqua L&F can't (Aqua only follows the OS appearance, and its dark
  styling of Swing tables/lists is weak).
- **Windows / Linux → the native L&F** (`UIManager.getSystemLookAndFeelClassName()`): the Windows
  look on Windows, GTK/Metal on Linux.

FlatLaf (`com.formdev:flatlaf`) is the GUI's one third-party dependency — it is shaded into the jar
and only loaded on macOS at runtime; the library core still needs only `dd-plist`, and the Windows
service host never touches Swing. The master list's cell renderers set only their text, icon, and
(for the state column) the state color, leaving the L&F to paint the row and its selection
highlight.

## Entry point — `-ui`, opt-in by design

`java -jar servicepal.jar` is unchanged: with no arguments (or `--managed` / `--help`) it is still
the read-only `DiscoverCli`. The GUI is reached **only** via an explicit first argument:

```
java -jar servicepal.jar -ui          # (also: --ui, gui)
java -jar servicepal.jar -ui --demo   # in-memory demo data, no OS changes
```

A thin `com.u1.servicepal.Main` dispatcher routes `-ui` to the GUI and delegates everything else to
`DiscoverCli`. This matters because the **same jar is the classpath for the Windows service host**,
which the backend launches as
`javaw -cp <jar> com.u1.servicepal.internal.windows.ServiceHost --id <id>` — it names its own main
class and never consults the dispatcher. Keeping the no-argument default as the discovery CLI means
adding the UI cannot affect the jar's role as the Windows execution helper.

## Architecture (`com.u1.servicepal.gui`)

The GUI depends only on the `ServiceManager` **interface**, which makes it driveable by a fake.

| Class | Role |
|-------|------|
| `ServicePalGui` | Entry point + modes (interactive / `--demo` / `--screenshot` / `--screenshot-live`); installs the L&F. |
| `MainWindow` | The `JFrame` shell; hosts the controller's content. |
| `JobsController` | Presenter: owns toolbar + list + detail + status bar, implements `JobActions`, runs every library call off the EDT on a `SwingWorker`, refreshes, and surfaces errors. |
| `JobListPanel` / `JobTableModel` | Master list with status dots + state. |
| `JobDetailPanel` | Selected job's properties, live status, action buttons; swaps daemon rows for Schedule / Next run / Last run on a scheduled job. |
| `JobFormPanel` / `JobDialog` | The add/edit form (reused, modeless, by the screenshot harness), with the keep-running ↔ on-a-schedule mode toggle. |
| `SchedulePanel` / `ScheduleText` | The Repeat picker (every-N / daily / weekly ⇄ `Schedule`) and the human-readable schedule/run-time formatting (both unit-tested headless). |
| `JobForm` / `JobSpecs` | Pure, display-free form ⇄ `ServiceSpec` mapping + the auto privilege model and schedule normalization (unit-tested headless). |
| `Job` / `StatusVisuals` / `CircleIcon` | View-model (spec + status, incl. `scheduled()`) and status colors/labels/dots (incl. the "Scheduled" relabel). |
| `DemoServiceManager` / `DemoData` | In-memory `ServiceManager` + seeded representative jobs for demos, screenshots, and tests. |

All library calls run on a `SwingWorker` (the backends shell out to `launchctl` / `systemctl` /
`sc`); results and errors are marshalled back to the EDT.

## GUI → library mapping

| Action | Library call |
|--------|--------------|
| Load list | `list()` (all discovered) + `read(id, installation)` per row; grouped by `ServiceStatus.managed()` / `adopted()` into created / adopted / other |
| Save (keep-running) | `JobSpecs.fromForm` → `install(spec, overwriteUnmanaged)` (`overwriteUnmanaged` true only when editing a foreign job — adopts it, after a warning); then `enable` + `start` (or **`restart`** if a running job's runtime fields changed — `JobsController.applySave`/`runtimeChanged`), else `disable` |
| Save (scheduled) | `install(spec, overwriteUnmanaged)` → **`enable`** to arm the schedule (no `start`); a systemd `.timer` also gets a `restart` to activate now / pick up an edited schedule |
| Start / Stop / Restart | `start` / `stop` / `restart(id)` (any job) |
| Remove | `uninstall(id, foreign)` (with confirmation; `foreign` passes the unmanaged override) |
| Badge / privilege model | `platform()` / `capabilities()` |

## Screenshot harness (CI)

Captures are produced by painting the window's Swing root pane into a `BufferedImage`, **not** by a
`Robot` screen grab. Swing widgets paint themselves into the image, so capture does not depend on a
visible, interactive desktop (a common source of black captures on Windows CI). It uses the
**`paint`** path (double-buffering disabled), not `printAll`: `printAll` is the *print* path, and
`JTable` deliberately omits the selection highlight when printing, so the selected row would not
show. Headless Linux still needs a display for AWT, so the Linux leg runs under **Xvfb**. OS-drawn
title bars are not part of the capture (the root pane is) — the widgets are what we review.

`.github/workflows/gui-screenshots.yml`:

- **demo** (ubuntu / macOS / Windows): seeded `DemoServiceManager`, deterministic, no OS changes —
  captures `main-<tag>.png` (incl. a scheduled "Database Snapshot" row), `add-job-<tag>.png`
  (keep-running mode), and `add-job-scheduled-<tag>.png` (the Repeat picker). This is the
  layout/look review.
- **live** (macOS / Windows, non-blocking): the real backend installs + starts a throwaway job,
  screenshots it actually running (`live-<tag>.png`), then uninstalls. End-to-end proof; the real
  lifecycle is already gated by `ci.yml`, so this never blocks. macOS uses a per-user agent (no
  root); Windows uses an admin service via the FFM host. OpenRC is skipped (no display; visually
  identical to the systemd Linux shot).

Screenshots are uploaded as workflow artifacts for download and review.

## Deferred (future)

An "Advanced" disclosure (env vars, log-file paths); richer restart/keep-alive options; richer
schedule shapes (monthly, every-N-hours, arbitrary cron). None are needed for the common case this
GUI targets. (Showing — and now controlling — all discovered services, including ones we adopt, and
both keep-running and scheduled jobs, is implemented; see the sections above.)
