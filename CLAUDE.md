# CLAUDE.md — JLaunchdManagerForMacs

Project knowledge base for AI agents working on this repo. Keep this current.

## What this is

A clean, immutable-first Java library for **creating and managing OS-level
background services / daemons** — a single uniform API across **macOS, Linux, and
Windows**. No UI — API only.

> **Scope grew.** It started as a macOS-only launchd wrapper (hence the repo name)
> and is now a **universal, cross-platform** service manager. The launchd model
> remains the conceptual baseline the other platforms are mapped onto. The name is
> historical; the goal is platform-agnostic.

Per platform, the library:

- **macOS** — writes `.plist` files (file I/O); drives `launchctl` (subprocess).
- **Linux/systemd** — writes `.service`/`.timer` INI units (file I/O); drives `systemctl`.
- **Linux/OpenRC** — writes init scripts (file I/O); drives `rc-service`/`rc-update` (subprocess).
  Other non-systemd inits (runit/SysV) remain later/roadmap.
- **Windows** — SCM via `advapi32` (Java FFM) and/or `sc.exe`; Task Scheduler via
  `schtasks`/PowerShell; **needs a service-host shim to run arbitrary commands as a
  service** (see the big quirk below).
- Native OS libraries are reached via **Java FFM** (no shipped compiled binaries) where
  it pays off (Windows SCM); everything else is subprocess. **All** native access sits
  behind stubbable interfaces so the library unit-tests off-platform.

**Status:** _Steps 1–2 done (research + approved API). Step 4 complete — **all four backends
implemented**; step 5 (final facade assembly) is essentially done (the manager is
platform-agnostic; each backend is wired into `DefaultServiceManager.create()`)._ macOS,
Linux/systemd, Linux/OpenRC, and Windows are all implemented end-to-end and validated on real
systems via the CI probe. The single-platform API sketch near the bottom predates the
cross-platform expansion and is **superseded** as the public design; it survives only as the
macOS-backend shape.

## Project plan (owner's 5 steps)

1. ✅ **Research all platforms & document quirks** — done. See `docs/research/`.
2. ✅ **Design an overarching API** that works across all platforms — done & approved.
   See `docs/design/api-design.md`.
3. ✅ **Design clean interop with each platform's native facilities** — done (renderers + the
   `Launchctl`/`Systemctl`/`RcService`/`Scm`/`TaskScheduler` seams; FFM for Windows).
4. ✅ **Implement per-platform modules** (macOS → systemd → OpenRC → Windows) — **all four
   complete** (discovery + inspection + mutation, see below).
5. ✅ **Assemble the unified library behind one facade** — the facade/manager are
   platform-agnostic; each backend is wired into `DefaultServiceManager.create()`. (Remaining
   work is refinement, not new platforms: systemd `.timer`, per-user Windows, etc.)

## Implementation status (live)

- **Build:** Maven, `mvn verify`. Compiler `release` is **25** (the Windows backend uses FFM,
  final in JDK 22; 25 is the first LTS with it final). macOS/systemd/OpenRC stay
  source-compatible with JDK 21, so a lower-JDK Mac/Linux-only build remains a roadmap item. The
  runnable jar's manifest carries `Enable-Native-Access: ALL-UNNAMED` so `java -jar` needs no flag.
- **Done:** full public model (`ServiceSpec`/`RunAs`/`Schedule`/`ServiceStatus`/`Capabilities`/
  exceptions), `ServiceManager` facade, platform detection, and the **complete macOS launchd
  backend** — discovery/inspection (`list`/`listManaged`/`read`/`readNative`/`status`/
  `isInstalled`/`isManaged`) **and mutation** (`install` upsert / `uninstall` / `start`/`stop`/
  `restart` / `enable`/`disable` / `installEnableStart`). Plist read+write via `dd-plist`
  (confined to `internal/macos/PlistReader`+`PlistWriter`); live state via domain-targeted
  `launchctl print`; lifecycle via `launchctl bootstrap`/`bootout`/`kickstart`/`kill`/`enable`.
  Two CLIs: **`DiscoverCli`** (read-only, the jar's main) and **`SelfTestCli`** (real
  install→start→uninstall lifecycle, used by the probe; platform-aware identity).
- **Done — Linux/systemd backend** (`internal/systemd`): writes `.service` units (INI, marker
  `X-ServicePal-Managed`), drives `systemctl` (`daemon-reload`/`enable`/`disable`/`start`/
  `stop`/`restart`, `show` for structured status). PER_USER → `systemctl --user` +
  `~/.config/systemd/user`; SYSTEM_WIDE → system manager + `/etc/systemd/system`. Full
  discovery + mutation. **Services only for now — `.timer` (calendar/interval) is deferred**,
  so systemd reports `calendar`/`interval` capabilities as false (scheduled jobs fail fast).
- **Done — Linux/OpenRC backend** (`internal/openrc`): writes init scripts to `/etc/init.d`
  (POSIX shell sourcing `openrc-run`, marker comment `# X-ServicePal-Managed`), drives
  `rc-service` (start/stop/restart/status) + `rc-update` (add/del for enable/disable). Full
  discovery + mutation. **SYSTEM_WIDE-only** (no per-user → `perUserInstall` false); **no native
  scheduler** (`calendar`/`interval` false → fail fast). Restart policy picks the supervisor:
  `start-stop-daemon` (+`command_background`) for NEVER, else `supervise-daemon` (respawns on any
  exit, so ON_FAILURE≈ALWAYS; ALWAYS just adds `respawn_max=0`). No `daemon-reload` analog —
  install is file write + chmod +x. Live state via `rc-service status` (best-effort,
  `structuredStatus` false); PID read from the pidfile. Seam = `RcService` (stub in tests).
- **Done — Windows backend** (`internal/windows`): routes by job shape (tension T2). A **daemon**
  becomes a real service whose `binPath` is our bundled pure-Java **FFM `ServiceHost`** — it
  speaks the SCM protocol via upcalls (`StartServiceCtrlDispatcherW` + `RegisterServiceCtrlHandlerExW`
  + `SetServiceStatus`) and supervises the real command as a child (implementing `RestartPolicy`),
  solving the error-1053 quirk with no shipped binary. A **scheduled** job → **Task Scheduler**
  (`schtasks` + generated XML); any command runs directly. SCM via `advapi32`/**FFM** (`FfmScm`:
  create/delete/start/stop(ControlService)/setStartType/setDescription/queryStatusEx, GetLastError
  capture). Per-service **sidecar JSON** in `%ProgramData%\ServicePal\<id>.json` is the spec record
  (host reads it), the managed marker, and the service-vs-task router; hand-rolled `Json` codec (no
  new dep — the Windows analog of dd-plist, confined). **SYSTEM_WIDE-only** in v1
  (`perUserInstall` false → fail fast); `calendar`/`interval` true (Task Scheduler);
  `structuredStatus` true (`QueryServiceStatusEx`). Discovery is sidecar-scoped (machine-wide
  enumeration of third-party services is deferred). Seams = `Scm` + `TaskScheduler` (stubs in
  tests). **FFM compiles everywhere; only `createDefault()`/calls run on Windows** — so the rest
  unit-tests off-Windows. Validated on the `windows-latest` runner via the probe `SelfTestCli`
  (real install→start→`RUNNING`+pid→uninstall; the host log confirmed the SCM round-trip).
- **89 unit tests**, all platform-independent (stubbed `CommandRunner`/`Launchctl`/`Systemctl`/
  `RcService`/`Scm`/`TaskScheduler`, temp-dir definitions). GitHub Actions CI on
  ubuntu/macos/windows (JDK 25); the probe runs a real `systemctl` lifecycle (sudo) on the ubuntu
  runner, a real launchd lifecycle on macOS, a real `rc-service` lifecycle in an Alpine/OpenRC
  container, and a real Windows-service lifecycle (the FFM host) on the windows runner.
- **Not yet (refinements, not platforms):** systemd `.timer` scheduling; per-user Windows
  services (Windows is SYSTEM_WIDE-only in v1); machine-wide enumeration of third-party Windows
  services (`list()` is sidecar-scoped there); the lower-JDK Mac/Linux-only build. `UnimplementedBackend`
  is now unused (all four platforms have real backends) but kept as a clear "not implemented" signal.
- **Refinements made during impl:**
  - `ServiceStatus` gained an `installation` field (handy for discovery grouping).
  - Discovery returns a **`Discovery(services, unreadable)`** — root-only/malformed plists are
    **reported by name**, not silently dropped (`ServiceManager.discover()`; `list()` is the
    services-only view).
  - Live state uses **domain-targeted `launchctl print <domain>/<label>`**, not `launchctl
    list` (which only sees the caller's GUI session domain — it never shows `system`-domain
    daemons, even under `sudo` from a terminal). Daemons → `system/` (root-only; reported
    `UNKNOWN` without root rather than a misleading `STOPPED`); agents → `gui/<uid>/`.
  - Native access stays behind `CommandRunner`/`Launchctl` so everything unit-tests off-platform.
- **Testing reach:** GitHub CI covers all three OSes for build+unit tests; full launchd behavior
  is best verified on a real Mac (owner runs macOS). The discovery CLI is the manual smoke test.
- **Cross-platform probe** (`.github/workflows/probe.yml`, `workflow_dispatch` + dev-branch push):
  builds and *runs* the CLI + `SelfTestCli` on ubuntu/macos/windows + an Alpine/OpenRC container,
  for exploratory validation (not pass/fail tests). Findings: platform detection is correct on all
  four (systemd / OpenRC / Windows / launchd); **real mutation lifecycles pass** — macOS launchd
  (PER_USER agent), systemd (sudo), OpenRC (Alpine container), and **Windows** (the FFM `ServiceHost`
  reaches `RUNNING` with a real PID and stops cleanly — the host log confirms the SCM protocol round
  trip, no error 1053). The probe caught two real bugs the unit tests couldn't: an empty
  `depend() {}` (a POSIX-shell syntax error) in the OpenRC script, and now fixed. Known honest
  limitation: global macOS **agents** (`/Library/LaunchAgents`) read as `UNKNOWN` under `sudo`,
  because root's `gui/0` session can't see them (they live in the console user's `gui/<uid>`).

## Desktop GUI (built on top of the finished library)

A small **Swing** GUI now sits on top of the library (`com.u1.servicepal.gui`), targeting only the
90%+ common case: set up jobs to be auto-started and kept running. It is **opt-in** — `java -jar
servicepal.jar -ui` (aliases `--ui`/`gui`); the no-argument default is still `DiscoverCli`, and a
thin `com.u1.servicepal.Main` dispatcher (now the jar's `Main-Class`) routes `-ui` → GUI and
everything else → `DiscoverCli`. **The Windows service host is unaffected** — the backend launches
it as `javaw -cp <jar> ...windows.ServiceHost --id <id>` (explicit main class, never via the
dispatcher), so the jar's role as the Windows "execution helper" is preserved.

- **What it exposes:** master-detail list of managed jobs (status dots, pid, state), add/edit (name,
  command, arguments, working folder, *start automatically*, *if it stops* → `RestartPolicy`),
  start/stop/restart, remove. Hides schedules, run-as identity, `.mac()/.systemd()/...` blocks — the
  UI is identical on all four platforms.
- **Auto privilege model** (`JobSpecs.fromForm` + `capabilities()`): per-user where supported
  (macOS/systemd → no admin, login start), else system daemon (Windows/OpenRC → boot start, needs
  elevation). The GUI never mentions `RunAs`/`Installation`.
- **Depends only on the `ServiceManager` interface**, so a `DemoServiceManager` (in-memory fake) +
  `DemoData` drive demos/screenshots/tests. Library calls run off the EDT on `SwingWorker`s.
- **Dark theme by default** on every platform: the JDK's built-in **Nimbus** L&F themed dark via a
  base-palette override (`ServicePalGui.applyDarkPalette`) — no third-party L&F (e.g. FlatLaf), to
  keep the no-new-dependency rule. The master list paints its own opaque cell backgrounds + sets
  selection colors directly (Nimbus renders custom renderers non-opaque otherwise).
- **Screenshots in CI** (`.github/workflows/gui-screenshots.yml`): captures the window by painting
  its Swing root pane into a PNG via the **`paint`** path (double-buffering off), **not** `printAll`
  (the print path makes `JTable` omit the selection) and **not** `Robot` (black on non-interactive
  Windows). **demo** shots (seeded data) on ubuntu/macOS/Windows (Linux under Xvfb); **live** shots
  (real backend install→running→uninstall) on macOS (per-user, no root) + Windows (admin, FFM host),
  non-blocking. Uploaded as artifacts. OpenRC skipped (no display; same Linux look as systemd).
- **New tests:** `JobSpecsTest` (privilege model, tokenizer, mapping) + `DemoServiceManagerTest`
  (lifecycle) — headless, platform-independent. Design: `docs/design/gui-design.md`.
- **No new runtime deps** (Swing is in the JDK); the build/JDK-25 baseline is unchanged. The GUI is
  JDK-21-source-compatible but lives in the same module, which targets release 25.

## Design docs (step 2)

- `docs/design/api-design.md` — **the API design** (approved, now being implemented): the
  three-tier uniformity model, `ServiceManager` facade, `ServiceSpec` model, Windows host design.
  Its header tracks per-platform implementation progress.
- `docs/design/windows-implementation-plan.md` — **detailed Windows build plan / next-session
  handoff**: the `internal/windows/` layout (mirroring macOS/systemd), the FFM `ServiceHost`,
  `Scm`/`TaskScheduler` seams, JDK-25 bump, install/lifecycle flows, an FFM cheat-sheet, and the
  probe-validation loop. **Start here for the Windows module.**
- `docs/ROADMAP.md` — deferred items (WinSW alt host, lower-JDK Mac/Linux build, SysV/runit,
  cron fallback, D-Bus).

## ▶ Next session: refinements (all four backends are done)

No platforms remain. The highest-value follow-ups, roughly in order:
1. **systemd `.timer` scheduling** — the one capability gap on a "full" platform (systemd
   currently reports `calendar`/`interval` false). Mirror the Windows Task-Scheduler routing.
2. **Windows polish** — per-user services (`Tmpl_<LUID>` or a logon Task; would flip
   `perUserInstall` true), machine-wide `EnumServicesStatusExW` discovery (today `list()` is
   sidecar-scoped), delayed-auto start, recovery actions via `ChangeServiceConfig2`.
3. **Lower-JDK Mac/Linux-only build** (roadmap) — the JDK 25 floor is only for the Windows FFM
   paths; the other backends are JDK-21-compatible.

When working on Windows FFM: it **compiles on any JDK 25 toolchain** (the `java.lang.foreign`
API is platform-independent; `libraryLookup("Advapi32.dll")` only fails at runtime off-Windows,
and only `createDefault()` constructs `FfmScm`), so develop+unit-test locally and validate the
real SCM behavior via the probe `SelfTestCli` on the `windows-latest` runner (the host logs to
`%ProgramData%\ServicePal\<id>.host.log`, which the probe dumps).

## Owner-approved decisions (step 2)

1. **Windows daemons → bundled pure-Java FFM service host** (a runnable class in our single
   jar). It speaks the SCM protocol via FFM upcalls and supervises the real command. This is
   the default "simple" path (90%+ cases). WinSW support is a later roadmap option.
2. **JDK 25 baseline.** A lower-JDK (down to ~JDK 8) **Mac/Linux-only** build is a roadmap item
   (the 25 floor exists only for the Windows FFM paths).
3. **systemd and OpenRC are both v1**, modeled as **separate platforms** with separate
   backends. **No public pluggable SPI** — the internal `Backend` interface is code-org only.
4. **Fail-fast** on capability gaps (e.g. calendar schedule on OpenRC). Solidify the core;
   add edge-case handling as testing reveals the need.

### Core API shape (see design doc for detail)

- Entry point `ServiceManager.getServiceManager()` (implicitly this platform; no scope-bound
  variants). Lifecycle ops take just the service `id`; the manager auto-resolves the
  installation (PER_USER first, then SYSTEM_WIDE; `AmbiguousServiceException` if in both). Every
  by-id op also has an explicit-`Installation` overload. `enable`/`disable` (boot persistence)
  are **separate** from `start`/`stop` (run now); `installEnableStart(spec)` is the combined
  convenience. `install` is **upsert** (create or update).
- **Two separate concepts** (don't conflate): **`RunAs`** = *who* runs it — a builder field,
  `.asCurrentUser()` (default) / `.asUser(name)` / `.asSystemDaemon()`, backed by an
  inspectable value type; and **`Installation { PER_USER, SYSTEM_WIDE }`** = *is it set up for
  one user or the whole computer* (the abstracted "domain"; named after the plain question it
  answers; same on all platforms, OpenRC = SYSTEM_WIDE-only). `RunAs` (3 values) derives
  `Installation` (2 values). Replaced the earlier `Scope` + `runAsUser` redundancy.
- `ServiceSpec` (immutable builder, `builder()` with **no required arg**) holds the uniform
  core (id, command, env, workdir, identity, log paths, `autoStart`, `RestartPolicy`
  NEVER/ON_FAILURE/ALWAYS, nullable `Schedule`). **`id` and `displayName` are optional**: id
  defaults to `com.u1.servicepal.<uuid>`, displayName defaults to id. Nullable fields use
  `null`, never `Optional`. `toBuilder()` enables read→modify→re-install.
- Platform-unique power lives in optional, typed, namespaced blocks: `.mac(...)`,
  `.systemd(...)`, `.windows(...)`, `.openrc(...)`, each with sensible per-platform defaults.
  A block for a **non-current** platform **throws** `WrongPlatformOptionsException` at
  `install()` (not silently ignored). Capability gaps throw `UnsupportedFeatureException`.
- Discovery/inspection: `list()` (all), `listManaged()` / `isManaged(id)` (only services we
  created, via an embedded marker), `read(id)` → `ServiceSpec` (null if absent), `readNative(id)`
  → verbatim definition text. Destructive/overwrite ops on **unmanaged** services throw unless
  called with the `yesDoThisToAServiceIDidNotCreate = true` overload (the awkward name is the
  intentional penalty).
- `Platform` enum: `MACOS_LAUNCHD | LINUX_SYSTEMD | LINUX_OPENRC | WINDOWS` (runtime-detected).

## Research docs (step 1 output — read these first)

- `docs/research/cross-platform-synthesis.md` — **start here**: master mapping table,
  the structural tensions (T1–T7), native-access strategy, and the open decisions for step 2.
- `docs/research/windows-services.md` — SCM + Task Scheduler; the service-protocol quirk.
- `docs/research/linux-systemd.md` — units, timers, enable-vs-start, lingering.
- `docs/research/linux-other-init.md` — OpenRC/SysV/runit/BusyBox; distro landscape; detection.
- `docs/research/java-ffm-native-access.md` — FFM maturity, Java baseline, FFM-vs-subprocess.

## Headline cross-platform findings (the things that shape the API)

- **"Run any command" is not universal.** macOS/Linux run any binary as a daemon;
  a **Windows service binary must speak the SCM control protocol** — a plain
  `java -jar app.jar` as a service fails (error 1053). Options: bundle WinSW (a compiled
  exe — conflicts with "no binaries"), a **pure-Java service host using FFM upcalls**
  (preserves "no binaries", needs JDK 22+), or route such jobs to Task Scheduler. **This is
  the pivotal design decision (tension T1).**
- **One launchd = several subsystems elsewhere.** Windows splits into Services +
  Task Scheduler; systemd splits a scheduled job into a `.timer` + `.service`. A single
  `Job` must be **routed to a backend by its shape**.
- **`enable` (boot persistence) ≠ `start` (run now)** on systemd; launchd conflates them.
- **Capabilities differ** (per-user agents, calendar scheduling, conditional keep-alive,
  structured status). Plan: expose a `Capabilities` query + **fail fast** on unsupported
  features rather than silently degrading.
- **Config format is per-platform** (plist XML / systemd INI / Windows registry+XML /
  shell scripts) → a **per-backend renderer**, not one shared codec.
- **Linux ≠ systemd everywhere.** ~90% of VMs are systemd, but **Alpine/OpenRC and
  init-less containers** are where servers often run. Detect the init at runtime; treat
  Linux as a pluggable backend (systemd v1, OpenRC next).

## Revised key decisions (supersede earlier single-platform notes)

- **Java baseline → JDK 25 LTS** (was 17). Rationale: **FFM is final in JDK 22** (JEP 454),
  preview in 21; JDK 25 is the first LTS with FFM final. Required for the Windows SCM/FFM
  path and the pure-Java service host. Subprocess-only fallback builds for 17/21 are
  possible but give a weaker Windows story. (Consuming apps must pass
  `--enable-native-access` under JEP 472 integrity-by-default.)
- **Native access: hybrid** — subprocess for macOS (`launchctl`), systemd (`systemctl`),
  non-systemd Linux, and Windows Task Scheduler; **FFM (`advapi32`) for Windows services**.
- **`dd-plist`** stays the macOS plist codec; systemd/script/task formats need no such dep.

---

## (Historical) original single-platform design

Everything below is the original macOS-only sketch. Kept for the macOS-backend details
(file locations, launchctl subcommands, dd-plist). **Superseded as the public API** by the
forthcoming cross-platform design (step 2).

## What this was (macOS-only)

A small, clean Java library for **creating and managing launchd entries on macOS**.
No UI — API only. The library:

- Writes/reads launchd `.plist` files with regular file I/O.
- Resolves the correct on-disk location using launchd best practices (see below).
- Invokes the `launchctl` binary as a subprocess for load/unload/start/stop/status.
- Serializes/deserializes the Apple property-list format with a dedicated plist library.

## Release & PR workflow (for AI agents)

- Develop on the assigned branch (see top). When you **wrap up a job that warrants a release**,
  push a **PR from your branch to `main`**. The owner will **immediately merge** it, which
  triggers the automated release build (`version-bump.yml` → tag → `release.yml`).
- **⚠️ Open the PR only as the LAST action of the turn — never open it and then keep pushing.**
  The owner merges a PR the moment it appears, so an open PR must be *finished, validated work*.
  Push commits to the **branch** freely during the turn (branch pushes trigger CI + the probe,
  which is how you iterate) — but **do not call `create_pull_request` until every commit is
  pushed and CI/probe is green.** Opening the PR early caused a race where a mid-iteration fix
  nearly missed the merge.
- **On your next turn, assume that PR was merged.** Keep using the **same assigned branch**;
  subsequent commits accrue into the **next** PR when you open it. (Each merge to `main` cuts a
  new release, so group changes into release-worthy units.)
- Release plumbing: `release.yml` (tag-driven build of fat jar + sources jar) and
  `version-bump.yml` (PR-merge → next version from latest `v*` tag; `release:minor`/`major`
  labels or `release:skip` / `[skip release]` to control). See `README.md`.

## Repo facts

- Single Git repo, working branch: `claude/java-launchd-api-design-zkcty4`.
- GitHub repo: `hooji/jlaunchdmanagerformacs` — **to be renamed `ServicePalForJava`** (later;
  owner will do it). Repo/dir names keep the `JLaunchd…` form until then.
- **Root Java package: `com.u1.servicepal`.** Generated service ids use this as the prefix
  (`com.u1.servicepal.<uuid>`).
- Started from an empty repository.

## Coding conventions (owner-mandated)

- **`final` by default**: all method arguments and local variables are `final`
  unless mutation is genuinely required.
- **Never use `var`.** Always write the explicit type.
- **No `Optional` anywhere** — not in `ServiceSpec`, not in return types, not
  internally. Use plain references and **`null`** for "absent"; document nullability.
  Collections are never null (return empty).
- **No Java Streams API** anywhere (no `.stream()`, `Collectors`, etc.). Use plain
  loops.
- **Indent with tabs**, not spaces (Java sources and code in docs).
- Prefer immutable value objects (records / builders) for the domain model.
- Clean, minimal, "dead simple" public surface. Hide launchctl/plist mechanics
  behind a small facade.

## Key technical decisions & research

### 1. Where launchd files go (researched)

launchd distinguishes **Agents** (run on behalf of a logged-in user, may touch the
GUI) from **Daemons** (run system-wide at boot, as root, no GUI). Standard locations:

| Location                        | Kind            | Runs as / when                         | Our scope name  |
|---------------------------------|-----------------|----------------------------------------|-----------------|
| `~/Library/LaunchAgents`        | User agent      | Current user, at their login           | `USER_AGENT`    |
| `/Library/LaunchAgents`         | Global agent    | Every user, at login (admin-installed) | `GLOBAL_AGENT`  |
| `/Library/LaunchDaemons`        | System daemon   | At boot, as root (admin-installed)     | `SYSTEM_DAEMON` |
| `/System/Library/Launch*`       | OS-owned        | **Off-limits** — never write here      | —               |

Writing to `/Library/...` requires admin/root; `~/Library/LaunchAgents` does not.
The library resolves the directory from the chosen scope and the `Label`
(`<dir>/<label>.plist`, e.g. `~/Library/LaunchAgents/com.example.backup.plist`).

### 2. launchctl invocation — use the MODERN subcommands

The legacy `launchctl load/unload/start/stop/list` subcommands are deprecated
(since ~10.10/10.11). Use the domain-aware subcommands:

| Action            | Modern command                                            |
|-------------------|-----------------------------------------------------------|
| Load / install    | `launchctl bootstrap <domain> <plist-path>`               |
| Unload / remove   | `launchctl bootout <domain>/<label>`                      |
| Start / restart   | `launchctl kickstart [-k] <domain>/<label>`               |
| Stop              | `launchctl kill <signal> <domain>/<label>`                |
| Enable / disable  | `launchctl enable|disable <domain>/<label>`               |
| Status / query    | `launchctl print <domain>/<label>`                        |

**Domain targets** (`<domain>`):
- User agent  → `gui/<uid>` (Aqua session; GUI-capable). `<uid>` via `id -u`.
- System daemon → `system`.
- Global agent → `gui/<uid>` per logged-in user.

### 3. Plist format library — use `dd-plist`, NOT Jackson

The owner asked about Jackson 2.x. **Recommendation: use `dd-plist` instead.**

- Apple plists are XML with a DTD-defined `<dict><key>…</key><value/></dict>`
  shape that does **not** map cleanly to Jackson's element/POJO model — you'd be
  writing custom serializers and fighting the format.
- `dd-plist` (`com.googlecode.plist:dd-plist`, latest `1.28`) is purpose-built:
  reads/writes XML **and** binary **and** ASCII plists, tiny, zero-dependency,
  actively maintained. `PropertyListParser` + `NSDictionary`/`NSArray`/etc.
- We keep `dd-plist` confined behind `PlistCodec` so the rest of the code never
  imports it directly (easy to swap later).

If a strong reason emerges to use Jackson anyway, revisit — but the default is
`dd-plist`.

### 4. Build & platform

- **Maven**, Java 17 LTS (records + sealed types available; good fit for
  immutable model). Could move to 21 if useful.
- Runtime obviously requires macOS for the launchctl calls; unit tests stub the
  process runner so they run anywhere. The `launchctl` process layer is behind an
  interface for exactly this reason.

## Proposed package layout

```
com.jlaunchd
├── LaunchdManager        // FACADE — the one class callers normally use
├── LaunchdScope          // USER_AGENT | GLOBAL_AGENT | SYSTEM_DAEMON (dir + domain)
├── LaunchdException      // unchecked; wraps I/O + process failures
├── model
│   ├── LaunchdJob        // immutable job definition; LaunchdJob.builder(label)
│   ├── KeepAlive         // true | { conditions }
│   ├── CalendarInterval  // minute/hour/day/weekday/month (factories: dailyAt, etc.)
│   └── ProcessType       // Background | Interactive | Adaptive | Standard
├── plist
│   └── PlistCodec        // LaunchdJob <-> .plist via dd-plist (only file that imports it)
└── launchctl
    ├── Launchctl         // interface over the launchctl binary (stub in tests)
    ├── DefaultLaunchctl  // ProcessBuilder impl
    └── JobStatus         // installed / loaded / running / pid / lastExitCode
```

### Facade sketch (pending approval)

```java
final LaunchdManager mgr = LaunchdManager.create();

final LaunchdJob job = LaunchdJob.builder("com.example.backup")
        .programArguments("/usr/local/bin/backup", "--daily")
        .runAtLoad(true)
        .startCalendarInterval(CalendarInterval.dailyAt(3, 0))
        .standardOutPath("/tmp/backup.log")
        .standardErrorPath("/tmp/backup.err")
        .build();

mgr.install(job, LaunchdScope.USER_AGENT);          // write plist + bootstrap
mgr.start("com.example.backup", LaunchdScope.USER_AGENT);   // kickstart
final JobStatus st = mgr.status("com.example.backup", LaunchdScope.USER_AGENT);
mgr.uninstall("com.example.backup", LaunchdScope.USER_AGENT); // bootout + delete
```

Facade responsibilities: `install`, `uninstall`, `start`, `stop`, `restart`,
`enable`, `disable`, `status`, `list(scope)`, `read(label, scope)`,
`isInstalled(label, scope)`. Each operation = (resolve path) + (file I/O via
`PlistCodec`) + (one or more `Launchctl` calls).

## Open questions for the owner

1. `gui/<uid>` vs `user/<uid>` for agents — default to `gui` (GUI-capable). OK?
2. Java 17 vs 21 baseline.
3. Should mutating ops throw on failure (proposed) or return result objects?
4. How rich should `JobStatus` be — parsing `launchctl print` output is messy;
   start minimal (installed/loaded/running/pid/lastExitCode)?

## Sources

- launchctl modern subcommands: https://ss64.com/mac/launchctl.html ,
  https://www.alansiu.net/2023/11/15/launchctl-new-subcommand-basics-for-macos/ ,
  https://gist.github.com/masklinn/a532dfe55bdeab3d60ab8e46ccc38a68
- dd-plist: https://github.com/3breadt/dd-plist ,
  https://central.sonatype.com/artifact/com.googlecode.plist/dd-plist
