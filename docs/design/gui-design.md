# Desktop GUI design

A small, cross-platform desktop GUI on top of the ServicePal library. It deliberately exposes
**only the 90%+ common case** — set up jobs to be auto-started and kept running in the background —
and hides every platform-specific capability the library otherwise offers.

## Scope

**In:** see your background jobs and whether each is running; add a job (name + command, optional
working folder); choose *start automatically* and *what to do if it stops*; start / stop / restart;
remove. The list shows only jobs ServicePal created (`listManaged()`), not the machine's hundreds of
system services.

**Deliberately out (hidden):** schedules (calendar / interval), `RunAs` named-user / system-daemon
selection, the per-platform option blocks (`.mac()/.systemd()/.windows()/.openrc()`), explicit
`Installation` choice, env vars and log-file paths (a later "Advanced" disclosure). The UI is
**identical on all four platforms**; only the native window chrome differs.

## The one cross-platform decision: the auto privilege model

The GUI never mentions `RunAs` or `Installation`. Instead `JobSpecs.fromForm` picks identity from
the platform's `capabilities()`:

- `perUserInstall` supported (macOS, systemd) → `asCurrentUser()` → **per-user**, no admin needed,
  auto-starts at login.
- otherwise (Windows, OpenRC — system-wide only) → `asSystemDaemon()` → **system-wide**, auto-starts
  at boot, needs the app to run elevated (admin / sudo).

This keeps the UI uniform and avoids privilege prompts on the platforms that don't need them. When a
privileged op fails, the controller shows a friendly "run as administrator / with sudo" hint.

## Toolkit: Swing + a themed dark Nimbus look

Swing is bundled in the JDK (zero new runtime dependencies — the library still only ships
`dd-plist`), runs on the JDK 25 baseline everywhere, and compiles into the existing shaded jar.
(JavaFX was rejected: heavier, platform-native dependencies, awkward headless CI.)

The GUI defaults to a **dark theme on every platform**. Rather than add a third-party
look-and-feel (e.g. FlatLaf) — which would break the project's no-new-dependency rule — it themes
the JDK's built-in **Nimbus** look-and-feel by overriding its base palette
(`ServicePalGui.applyDarkPalette`). Nimbus derives most component colors from a handful of base
keys, so a dark palette propagates consistently. The master list sets its selection colors directly
and its cell renderers paint their own opaque background (Nimbus renders custom renderers
non-opaque otherwise), so the selected row highlights clearly. Per-platform differences are limited
to system fonts; the theme itself is uniform.

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
| `JobDetailPanel` | Selected job's properties, live status, action buttons. |
| `JobFormPanel` / `JobDialog` | The add/edit form (reused, modeless, by the screenshot harness). |
| `JobForm` / `JobSpecs` | Pure, display-free form ⇄ `ServiceSpec` mapping + the auto privilege model (unit-tested headless). |
| `Job` / `StatusVisuals` / `CircleIcon` | View-model (spec + status) and status colors/labels/dots. |
| `DemoServiceManager` / `DemoData` | In-memory `ServiceManager` + seeded representative jobs for demos, screenshots, and tests. |

All library calls run on a `SwingWorker` (the backends shell out to `launchctl` / `systemctl` /
`sc`); results and errors are marshalled back to the EDT.

## GUI → library mapping

| Action | Library call |
|--------|--------------|
| Load list | `listManaged()` + `read(id)` per row |
| Save (new/edit) | `JobSpecs.fromForm` → `install(spec)`; then `enable`+`start` if *start automatically*, else `disable` |
| Start / Stop / Restart | `start` / `stop` / `restart(id)` |
| Remove | `uninstall(id)` (with confirmation) |
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
  captures `main-<tag>.png` and `add-job-<tag>.png`. This is the layout/look review.
- **live** (macOS / Windows, non-blocking): the real backend installs + starts a throwaway job,
  screenshots it actually running (`live-<tag>.png`), then uninstalls. End-to-end proof; the real
  lifecycle is already gated by `ci.yml`, so this never blocks. macOS uses a per-user agent (no
  root); Windows uses an admin service via the FFM host. OpenRC is skipped (no display; visually
  identical to the systemd Linux shot).

Screenshots are uploaded as workflow artifacts for download and review.

## Deferred (future)

An "Advanced" disclosure (env vars, log-file paths); a "show all services" read-only view; richer
restart/keep-alive options; surfacing schedules. None are needed for the common case this GUI
targets.
