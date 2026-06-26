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
- **Linux/non-systemd** — (later) generates init scripts; drives `rc-service`/`sv`/`service`.
- **Windows** — SCM via `advapi32` (Java FFM) and/or `sc.exe`; Task Scheduler via
  `schtasks`/PowerShell; **needs a service-host shim to run arbitrary commands as a
  service** (see the big quirk below).
- Native OS libraries are reached via **Java FFM** (no shipped compiled binaries) where
  it pays off (Windows SCM); everything else is subprocess. **All** native access sits
  behind stubbable interfaces so the library unit-tests off-platform.

**Status:** _Research complete (step 1). Design phase (step 2) — not started._
The single-platform API sketch near the bottom predates the cross-platform expansion
and is **superseded** as the public design; it survives only as the macOS-backend shape.
No implementation code has been written yet. Do not start implementing until the
cross-platform API is designed and approved.

## Project plan (owner's 5 steps)

1. ✅ **Research all platforms & document quirks** — done. See `docs/research/`.
2. ⏳ **Design an overarching API** that works across all platforms — next.
3. ⬜ Design clean interop with each platform's native facilities.
4. ⬜ Implement per-platform modules one at a time (macOS → systemd → Windows → OpenRC).
5. ⬜ Assemble the unified library behind one facade.

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

## Repo facts

- Single Git repo, working branch: `claude/java-launchd-api-design-zkcty4`.
- GitHub repo: `hooji/jlaunchdmanagerformacs`.
- Started from an empty repository.

## Coding conventions (owner-mandated)

- **`final` by default**: all method arguments and local variables are `final`
  unless mutation is genuinely required.
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
