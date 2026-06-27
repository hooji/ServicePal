# Linux systemd — research for cross-platform service management

Research doc for JLaunchdManagerForMacs. The macOS side is launchd (see `CLAUDE.md`);
this captures the Linux equivalent, **systemd**, so the library can grow a second
backend. Verified against current (2026) man pages and docs; sources are inline and
collected at the bottom.

systemd is the init system / service manager on essentially all mainstream Linux
distributions (Debian/Ubuntu, RHEL/Fedora, SUSE, Arch). It replaces the old
SysV-init + cron model with declarative **unit files**, a controlling daemon (PID 1
for the system manager, plus a per-user manager), the `systemctl` CLI, and a D-Bus
API. Like launchd, it covers **both** long-running services and scheduled
(calendar/interval) jobs — the latter via dedicated `.timer` units.

---

## A. systemd service units

### A.1 Unit file format

Unit files are **INI-style**: `[Section]` headers with `Key=Value` lines. A
service is described by a `.service` file with three relevant sections:

- `[Unit]` — generic metadata and dependencies (applies to all unit types).
- `[Service]` — service-specific execution config (`.service` only).
- `[Install]` — what happens on `systemctl enable`/`disable` (optional).

Lines can be continued with a trailing `\`. An **empty assignment** (`Key=`) resets
a list-valued key. The same key can sometimes appear multiple times to append
(e.g. `ExecStartPre=`, `Environment=`).

#### `[Unit]` keys

Source: [systemd.unit(5) — man7](https://www.man7.org/linux/man-pages/man5/systemd.unit.5.html)

| Key | Meaning |
|---|---|
| `Description=` | Short human-readable name shown in `systemctl status`/logs. |
| `Documentation=` | Space-separated URIs (`man:`, `https:`, `file:`, `info:`). |
| `Requires=` | **Strong** dependency: listed units are pulled in; if they fail to start, this unit fails too. Does **not** imply ordering. |
| `Wants=` | **Weak** dependency: listed units are pulled in, but their failure does not fail this unit. The dependency form used by `WantedBy=` in `[Install]`. |
| `Requisite=` | Like `Requires=` but the listed unit must **already** be active; otherwise this unit fails immediately (does not start it). |
| `BindsTo=` | Like `Requires=`, but if the bound unit stops *unexpectedly*, this unit is also stopped. |
| `PartOf=` | One-way propagation of stop/restart from the listed unit to this one. |
| `Conflicts=` | Negative dependency: starting this stops the other (and vice versa). |
| `After=` / `Before=` | **Ordering only** (orthogonal to `Requires`/`Wants`). `After=network.target` means "start after network.target is up". |
| `Condition…=` / `Assert…=` | Gate activation, e.g. `ConditionPathExists=`. Condition failure silently skips; Assert failure marks failed. |

Note the deliberate split: `Requires`/`Wants` express **existence/pull-in**, while
`After`/`Before` express **ordering**. You usually need both (e.g. `Wants=foo.service`
+ `After=foo.service`).

#### `[Service]` keys

Sources: [systemd.service(5) — man7](https://www.man7.org/linux/man-pages/man5/systemd.service.5.html),
[systemd.exec(5) — man7](https://www.man7.org/linux/man-pages/man5/systemd.exec.5.html)

**`Type=`** — how systemd decides the service is "started" (the readiness protocol):

| `Type=` | Semantics |
|---|---|
| `simple` | Default when `ExecStart=` is set. Considered started **immediately after fork**, before `execve()`. Fast but cannot detect a bad binary or report real startup failure. The process is the main process. |
| `exec` | Like `simple`, but considered started only after the binary has been **`execve()`'d**. Reports failure if the binary can't be invoked (bad path, bad `User=`). Generally a better default than `simple`. |
| `forking` | Classic daemon: `ExecStart` forks and the **parent exits** when init is done; the child is the main process. Pair with `PIDFile=`. **Discouraged** by upstream — prefer `notify`/`exec`/`dbus`. |
| `oneshot` | Process runs to completion; unit considered up **after it exits**. May list multiple `ExecStart=` lines. Combine with `RemainAfterExit=yes` to keep the unit "active" after the command finishes. Default `Type` when no `ExecStart` is given. The natural fit for timer-triggered jobs and one-off setup. |
| `dbus` | Like `simple`, but considered up once the configured `BusName=` is acquired on the bus. Requires `BusName=`. |
| `notify` | Like `exec`, but the service must send `READY=1` via `sd_notify(3)` when initialized; systemd waits for it before proceeding. Best readiness signal for complex daemons. |
| `notify-reload` | Like `notify`, plus a defined reload protocol (`SIGHUP` to main process, `RELOADING=1`/`READY=1` handshake). |
| `idle` | Like `simple`, but execution is delayed until pending jobs are dispatched (up to a ~5s timeout). Cosmetic — avoids interleaving console output. |

**Exec commands:**

| Key | Meaning |
|---|---|
| `ExecStart=` | The command(s) to run. Exactly one for non-`oneshot`; multiple allowed for `oneshot`. **Absolute path required** for the binary (or a path prefix). Arguments are space-separated; this is the direct analogue of launchd `ProgramArguments`. |
| `ExecStartPre=` / `ExecStartPost=` | Commands run serially before/after `ExecStart`. `Post` runs only after start is considered successful (per `Type=`). A failing `ExecStartPre` aborts startup. |
| `ExecStop=` | Command to stop the service cleanly (should be synchronous). If omitted, systemd sends `SIGTERM` then `SIGKILL`. |
| `ExecStopPost=` | Runs after the service stopped, **even on crash**. Receives `$SERVICE_RESULT`, `$EXIT_CODE`, `$EXIT_STATUS`. |
| `ExecReload=` | Command to reload config; `$MAINPID` is available (e.g. `/bin/kill -HUP $MAINPID`). |

**Restart policy:**

| Key | Meaning |
|---|---|
| `Restart=` | When to auto-restart. Values: `no` (default), `on-success`, `on-failure`, `on-abnormal`, `on-abort`, `on-watchdog`, `always`. See table below. |
| `RestartSec=` | Delay before restarting. Default **100ms**. Accepts time spans (`5s`, `5min 20s`). The closest analogue to launchd `ThrottleInterval`. |
| `RemainAfterExit=` | Treat unit as active even after all processes exit (typical for `oneshot`). Default `no`. |
| `StartLimitIntervalSec=` / `StartLimitBurst=` | Rate-limit restarts (these live in `[Unit]`). Default burst 5 within 10s, after which systemd gives up and marks the unit failed — set `StartLimitIntervalSec=0` to disable for "restart forever". |

`Restart=` value semantics ([systemd.service(5)](https://www.man7.org/linux/man-pages/man5/systemd.service.5.html)):

| Value | Restarts on… |
|---|---|
| `no` | Never (default). |
| `on-success` | Clean exit only (exit 0, or a "clean" signal SIGHUP/SIGINT/SIGTERM/SIGPIPE, or codes in `SuccessExitStatus=`). |
| `on-failure` | Non-zero exit, kill by (non-clean) signal, core dump, operation timeout, or watchdog timeout. The usual choice for daemons. |
| `on-abnormal` | Signal, timeout, or watchdog (but **not** a plain non-zero exit). |
| `on-abort` | Only an uncaught signal not in the clean set. |
| `on-watchdog` | Only watchdog timeout expiry. |
| `always` | Regardless of how it exited. Note: `oneshot` is never restarted on clean exit even with `always`. |

> "Restart forever" caveat (2026-relevant): `Restart=always` alone is throttled by
> `StartLimitBurst`. To genuinely never give up, also set `StartLimitIntervalSec=0`.
> ([Stapelberg, 2024](https://michael.stapelberg.ch/posts/2024-01-17-systemd-indefinite-service-restarts/))

**Execution environment** (from `systemd.exec(5)`):

| Key | Meaning |
|---|---|
| `User=` / `Group=` | UID/GID to run as (name or numeric). Default `root` for system units; the session user for `--user` units. With `exec`/`notify`, a bad user is detected at start. |
| `WorkingDirectory=` | CWD. `~` = the user's home; leading `-` makes a missing dir non-fatal. |
| `Environment=` | Inline `KEY=VALUE` pairs (repeatable). |
| `EnvironmentFile=` | Load `KEY=VALUE` lines from a file; leading `-` tolerates a missing file. |
| `StandardOutput=` / `StandardError=` | Destination for stdout/stderr. Values: `journal` (default; goes to journald, query via `journalctl`), `file:PATH` (truncate-on-open then keep writing), `append:PATH`, `truncate:PATH`, `null`, `inherit`, `tty`, `kmsg`, `socket`. The `file:`/`append:` forms are the analogue of launchd `StandardOutPath`/`StandardErrorPath`. |
| `RuntimeDirectory=` | Auto-create `/run/<name>` (or `$XDG_RUNTIME_DIR/<name>` for user units) owned by `User=`/`Group=`, **removed on stop**. Siblings: `StateDirectory=` (`/var/lib`), `CacheDirectory=` (`/var/cache`), `LogsDirectory=` (`/var/log`) — these persist. Each exports `$RUNTIME_DIRECTORY` etc. |
| `LimitNOFILE=`, `LimitNPROC=`, `LimitCORE=`, … | Per-process `rlimit`s; `soft:hard` or single value; `infinity` allowed. |
| `MemoryMax=`, `CPUQuota=`, `TasksMax=` | cgroup resource control (defined in `systemd.resource-control(5)`, usable in `[Service]`). Prefer `TasksMax=` over `LimitNPROC=` and `MemoryMax=` over `LimitRSS=`. |

#### `[Install]` keys

These describe what `systemctl enable` wires up; they have **no effect** until
`enable` is run. ([systemd.unit(5)](https://www.man7.org/linux/man-pages/man5/systemd.unit.5.html))

| Key | Meaning |
|---|---|
| `WantedBy=` | On `enable`, create a symlink in `<target>.wants/` (e.g. `multi-user.target.wants/`). This is what makes a service start at boot. The most common key. |
| `RequiredBy=` | Like `WantedBy=` but creates a `.requires/` (strong) link. |
| `Alias=` | Additional names for the unit (symlink-based). |
| `Also=` | When enabling this unit, also enable the listed units. |
| `DefaultInstance=` | For template units (`foo@.service`), the instance used when none is given. |

A unit **without an `[Install]` section** cannot be enabled — `is-enabled` reports
it as `static` (it's pulled in only via other units' dependencies).

---

### A.2 Where unit files live & precedence

systemd loads the **first** matching unit file found along an ordered search path,
then layers **drop-ins** on top. Highest precedence wins.

**System manager** load path, highest → lowest precedence
([systemd.unit(5)](https://www.man7.org/linux/man-pages/man5/systemd.unit.5.html), abbreviated to the ones that matter):

1. `/etc/systemd/system/` — **administrator units; always win**. Where you'd write custom units and where `enable` symlinks land.
2. `/run/systemd/system/` — runtime/transient, volatile (lost on reboot).
3. `/usr/local/lib/systemd/system/` then `/usr/lib/systemd/system/` — **package-installed** units (distro defaults). `/lib/systemd/system` is usually a symlink to the latter.

(The full list also includes generator and `.control`/`.attached` dirs at higher
priority, used by systemd internals — not something a library writes to.)

**User manager** load path, highest → lowest:

1. `~/.config/systemd/user/` (`$XDG_CONFIG_HOME`) — **user's own units; win**. Where a library should write user units.
2. `/etc/systemd/user/` — admin-provided user units (apply to all users).
3. `$XDG_RUNTIME_DIR/systemd/user/`, `/run/systemd/user/` — runtime.
4. `~/.local/share/systemd/user/` (`$XDG_DATA_HOME`) — units shipped by home-dir-installed packages.
5. `/usr/local/lib/systemd/user/`, `/usr/lib/systemd/user/` — distro/package user units.

**Drop-ins** (`<unit>.d/*.conf`): instead of editing a vendor unit, drop a partial
override into `<unit>.service.d/override.conf`. Precedence:
`/etc/` > `/run/` > `/usr/lib/`; within a dir, files apply in **lexicographic**
order; drop-ins always override the base unit file. `systemctl edit <unit>` creates
one for you; `systemctl cat <unit>` shows the base file + all applied drop-ins.
([RHEL 9 docs](https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/9/html/using_systemd_unit_files_to_customize_and_optimize_your_system/assembly_working-with-systemd-unit-files_working-with-systemd))

> **Contrast with launchd:** launchd has no drop-in/precedence layering and no
> distro-vs-admin split — a plist is just a file at one of a few fixed locations.
> systemd's precedence rules mean the library should normally only ever touch
> `/etc/systemd/system/` (system) and `~/.config/systemd/user/` (user).

---

### A.3 System manager vs user manager (and **lingering**)

systemd runs **two kinds of manager**:

- The **system manager** (PID 1). Controlled with `systemctl …` (as root). Units run
  system-wide, typically as `root` (or a configured `User=`). Analogue of launchd
  `SYSTEM_DAEMON` / `GLOBAL_AGENT`.
- A **per-user manager** (`systemd --user`), one per user. Controlled with
  `systemctl --user …`. Units run as that user. Analogue of launchd `USER_AGENT`
  (`gui/<uid>`).

`systemctl --user` talks to the calling user's manager over their session bus, so it
must be run **as that user with a session bus available** (`$XDG_RUNTIME_DIR` /
`DBUS_SESSION_BUS_ADDRESS` set). A bare `sudo su - user; systemctl --user …` often
fails because the session/bus environment isn't set up.
([linuxvox](https://linuxvox.com/blog/can-i-control-a-user-systemd-using-systemctl-user-after-sudo-su-myuser/))

**The lingering quirk (critical).** By default a user manager **starts at the user's
login and is killed at their last logout** — so a user service won't run at boot and
won't survive logout. To make user services behave like real background daemons:

```
sudo loginctl enable-linger <user>
```

This makes the user's manager start **at boot** and keep running across logout/reboot
without an active login session. Check with `loginctl show-user <user> | grep Linger`
(or `loginctl user-status`). Without linger, a user-scope install that "works while
I'm logged in" silently stops doing anything after logout — a classic gotcha,
especially for headless/rootless-container setups.
([Arch Wiki — systemd/User](https://wiki.archlinux.org/title/Systemd/User),
[khmersite](https://blog.khmersite.net/p/how-to-enable-check-linger-for-user/),
[oneuptime](https://oneuptime.com/blog/post/2026-03-18-use-loginctl-enable-linger-rootless-podman/view))

---

### A.4 Management commands

Source: [systemctl(1) — man7](https://www.man7.org/linux/man-pages/man1/systemctl.1.html)

| Command | What it does |
|---|---|
| `systemctl daemon-reload` | Re-reads all unit files from disk and rebuilds the dependency tree. **Required after writing or editing a unit file** — systemd caches units in memory and will not see your new/edited file until you reload. (Not needed after `enable`/`start` of an already-loaded unit, but needed after the file changes.) |
| `systemctl enable <u>` | Create the `[Install]` symlinks → unit starts **at next boot**. Does **not** start it now. |
| `systemctl disable <u>` | Remove those symlinks. Does not stop a running instance. |
| `systemctl enable --now <u>` | `enable` **and** `start` in one shot — the closest thing to launchd's combined "install + load". |
| `systemctl start` / `stop` / `restart` | Change **current** runtime state only (no boot persistence). |
| `systemctl reload <u>` | Run the unit's `ExecReload=` (config reload without restart). `reload-or-restart` falls back to restart. |
| `systemctl status <u>` | Human-readable state + recent journal lines. |
| `systemctl is-active <u>` | Prints/returns active state; exit 0 if active. |
| `systemctl is-enabled <u>` | Prints one of `enabled`/`enabled-runtime`/`disabled`/`static`/`masked`/`linked`/`alias`/`indirect`/`generated`/`transient`. |
| `systemctl is-failed <u>` | Exit 0 if the unit is in the `failed` state. |
| `systemctl show -p <Prop>… <u>` | **Machine-readable** `Key=Value` properties (see A.5). The right call for building a status object. |
| `systemctl cat <u>` | Print the backing unit file(s) + applied drop-ins. |
| `systemctl list-units [--type=service] [--all]` | Units currently in memory. |
| `systemctl list-unit-files` | All installed unit files + their enable state (includes uninstantiated templates). |
| `systemctl list-timers [--all]` | Timer units sorted by next elapse (NEXT/LEFT/LAST/PASSED/ACTIVATES). |
| `systemctl mask` / `unmask` | Symlink the unit to `/dev/null` so it cannot be started at all (stronger than `disable`). |

> **`enable` vs `start` — the key conceptual difference from launchd.** In launchd,
> `mgr.install` + `bootstrap` both registers the job and makes it active, and a
> `RunAtLoad`/`StartCalendarInterval` plist starts running as soon as it's loaded.
> In systemd these are **two orthogonal axes**: `enable` = "run at boot"
> (persistence), `start` = "run now" (current state). A service can be enabled but
> stopped, or running but not enabled. Our facade's `install` will usually want to map
> to `daemon-reload` → `enable --now` to approximate launchd's combined behavior.

---

### A.5 Status querying (building a status object)

`systemctl show -p <Prop> <unit>` (or `--property=`) returns parseable `Key=Value`
lines. Useful properties for a status model
([systemctl-show](https://linuxcommandlibrary.com/man/systemctl-show),
[simplified.guide](https://www.simplified.guide/systemd/unit-show-properties)):

| Property | Meaning |
|---|---|
| `LoadState` | `loaded` / `not-found` / `masked` / `error` — did systemd find/parse the unit. |
| `ActiveState` | High-level: `active`, `inactive`, `activating`, `deactivating`, `failed`, `reloading`. |
| `SubState` | Type-specific fine state: e.g. for services `running`, `exited` (oneshot + RemainAfterExit), `dead`, `start-pre`, `auto-restart`, `failed`. |
| `UnitFileState` | Enable state (same vocabulary as `is-enabled`). |
| `MainPID` / `ExecMainPID` | PID of the main process (0 if not running). |
| `ExecMainStatus` | Exit **status code** of the main process (the `$?`-style value). |
| `ExecMainCode` | How it exited as a `siginfo` code: `1`=exited, `2`=killed, etc. (`ExecMainStatus` is interpreted relative to this). |
| `ExecMainStartTimestamp` / `…ExitTimestamp` | When the main process started / exited. |
| `Result` | Why the unit is in its current (often failed) state: `success`, `exit-code`, `signal`, `timeout`, `watchdog`, `resources`, `oom-kill`, … |
| `FragmentPath` | Path to the backing unit file. |

A reasonable `JobStatus` analogue for the systemd backend:
**installed** = `LoadState=loaded` (or file exists), **enabled** = `UnitFileState`,
**running** = `ActiveState`/`SubState`, **pid** = `MainPID`,
**lastExitCode** = `ExecMainStatus` (+ `ExecMainCode`/`Result` for context).

To fetch them efficiently in one call:
`systemctl show <unit> -p LoadState,ActiveState,SubState,UnitFileState,MainPID,ExecMainStatus,ExecMainCode,Result`.

---

### A.6 D-Bus API (alternative to shelling out)

systemd exposes everything over **D-Bus** at bus name `org.freedesktop.systemd1`,
manager object `/org/freedesktop/systemd1`, interface
`org.freedesktop.systemd1.Manager`. The same API backs `systemctl` itself, and is
reachable from C via **`sd-bus`** in `libsystemd`, or from the CLI via `busctl`.
([org.freedesktop.systemd1(5)](https://man7.org/linux/man-pages/man5/org.freedesktop.systemd1.5.html),
[The new sd-bus API](https://0pointer.net/blog/the-new-sd-bus-api-of-systemd.html))

Key Manager methods:

| Method | Equivalent of |
|---|---|
| `StartUnit(name, mode)` | `systemctl start`. `mode` ∈ `replace`/`fail`/`isolate`/`ignore-dependencies`/`ignore-requirements`. Returns a job object path. |
| `StopUnit` / `RestartUnit` / `TryRestartUnit` / `ReloadUnit` / `ReloadOrRestartUnit` | the obvious `systemctl` verbs. |
| `EnableUnitFiles(files, runtime, force)` / `DisableUnitFiles(files, runtime)` | `enable`/`disable` (`runtime=true` → `/run` instead of `/etc`). |
| `GetUnit(name)` / `LoadUnit(name)` | resolve/lookup a unit object path. |
| `GetUnitFileState(name)` | `is-enabled`. |
| `ListUnits()` | `list-units`. |
| `Reload()` | `daemon-reload`. |

State changes are observable via the `JobRemoved` signal (result ∈ `done`,
`failed`, `canceled`, `timeout`, `dependency`, `skipped`), avoiding polling. Unit
objects expose `ActiveState`/`SubState` as D-Bus properties. State-changing calls
require the `org.freedesktop.systemd1.manage-units` polkit privilege (i.e. root or a
polkit rule), exactly as with `systemctl`.

**Tradeoff vs subprocess:** D-Bus gives structured returns, typed properties, and
async signals (no output parsing), but requires either FFM bindings to `sd-bus` or a
pure-Java D-Bus client and a live bus connection — meaningfully more complexity. For
this library, shelling out to `systemctl` (with `show` for structured status) is the
simpler, more portable choice; see Section C.

---

## B. systemd timers (the calendar-job equivalent)

systemd's answer to cron/launchd-`StartCalendarInterval`. A **`.timer`** unit
schedules activation of a **`.service`** unit. By default `foo.timer` activates
`foo.service` (same stem); override with `Unit=` in `[Timer]`. The service is
usually `Type=oneshot`.
([systemd.timer(5)](https://www.man7.org/linux/man-pages/man5/systemd.timer.5.html),
[Arch Wiki — systemd/Timers](https://wiki.archlinux.org/title/Systemd/Timers))

Two flavors:

- **Realtime / calendar timers** — `OnCalendar=`, fire at wall-clock times (like cron).
- **Monotonic timers** — fire a span *after* an event: `OnBootSec=` (after boot),
  `OnStartupSec=` (after the manager started), `OnActiveSec=` (after the timer was
  activated), `OnUnitActiveSec=` / `OnUnitInactiveSec=` (after the target unit last
  went active/inactive — used for "every N after each run").

`[Timer]` keys:

| Key | Meaning |
|---|---|
| `OnCalendar=` | Calendar expression(s); the cron-equivalent. Syntax below. |
| `OnBootSec=` / `OnStartupSec=` | Relative to boot / manager start. |
| `OnActiveSec=` / `OnUnitActiveSec=` / `OnUnitInactiveSec=` | Relative to timer activation / target last active / inactive. |
| `Persistent=` | (Only with `OnCalendar=`.) Store last-run time on disk; **on next boot, run immediately if a scheduled run was missed while the machine was off**. This is the headline advantage over cron. |
| `AccuracySec=` | Window within which the timer may fire (default **1min**) to allow power-friendly coalescing. Set `1us`/`1s` for near-exact timing. |
| `RandomizedDelaySec=` | Add a random delay up to this value (spread load across many hosts/timers). |
| `WakeSystem=` | Wake the machine from suspend to fire (uses an alarm clock). |
| `RemainAfterElapse=` | Keep the timer loaded/queryable after it elapses (default yes). |

**Calendar event syntax** (`OnCalendar=`, defined in
[systemd.time(7)](https://www.man7.org/linux/man-pages/man7/systemd.time.7.html)):

```
DayOfWeek Year-Month-Day Hour:Minute:Second [Timezone]
```

- `*` = any value; `,` lists values; `..` is an inclusive range; `/N` is a repeat step.
- `~` selects from the **end** of the month (`*-02~03` = 3rd-to-last day of Feb).
- Day-of-week names (`Mon`, `Tue`, … or ranges `Mon..Fri`) are optional.
- Timezone may be appended (`UTC`, local, or IANA like `Asia/Tokyo`).
- Shorthands: `minutely`, `hourly`, `daily`, `weekly`, `monthly`, `yearly`,
  `quarterly`, `semiannually`.

Examples:

| Expression | Meaning |
|---|---|
| `*-*-* 03:00:00` | Every day at 03:00. |
| `daily` | Same as above (`*-*-* 00:00:00`). |
| `Mon..Fri 09:00` | 09:00 on weekdays. |
| `*-*-* 00/6:00:00` | Every 6 hours on the hour. |
| `*-*-01 04:00:00` | 04:00 on the 1st of every month. |
| `Thu,Fri 2012-*-1,5 11:12:13` | 11:12:13 on the 1st or 5th, in 2012, only if Thu/Fri. |

Validate any expression with `systemd-analyze calendar "<expr>"` (shows the next
elapse). To enable a timer: write both `.timer` and `.service`, `daemon-reload`,
then `systemctl enable --now foo.timer` (you enable the **timer**, not the service).

**vs launchd:** `OnCalendar=` ≈ `StartCalendarInterval`; the monotonic
`OnUnitActiveSec=`/`OnBootSec=` ≈ `StartInterval` (every N seconds). systemd's
`Persistent=` ≈ launchd's behavior of running a missed `StartCalendarInterval` job
after wake — but launchd has nothing exactly like the timer/service split.

**vs cron:** Timers are preferred today because activation runs as a normal service
unit — so it gets **journald logging** (`journalctl -u foo.service`), **dependency
ordering** (`After=network-online.target`, wait for a DB, etc.), **resource limits**
(`MemoryMax=`/`CPUQuota=`), and **missed-run catch-up** (`Persistent=`). cron has none
of these and only does wall-clock scheduling. cron remains simpler for trivial
one-line jobs and is still ubiquitous.
([opensource.com](https://opensource.com/article/20/7/systemd-timers),
[xTom](https://xtom.com/blog/systemd-vs-cron-linux-task-scheduling/))

---

## C. Synthesis for our library

### Mapping to launchd concepts

| launchd (already in CLAUDE.md) | systemd equivalent | Notes |
|---|---|---|
| `Label` (reverse-DNS, e.g. `com.example.backup`) | **unit name** `example-backup.service` | systemd convention is a short name + `.service`, **not** reverse-DNS. Need a label→unit-name mapping. |
| `.plist` file (XML) | `.service` unit file (INI) | Plain file I/O on both sides; different serializer. |
| `ProgramArguments` | `ExecStart=` | systemd wants an **absolute** binary path; args space-separated on one line. |
| `RunAtLoad=true` | `WantedBy=…` (`[Install]`) + `systemctl enable` + `start` | launchd's load = start; systemd splits enable (boot) vs start (now). Use `enable --now`. |
| `KeepAlive=true` | `Restart=always` (+ `StartLimitIntervalSec=0` for truly forever) | `KeepAlive={conditions}` maps loosely to `Restart=on-failure`/`on-success` and `Condition…=`. |
| `StartCalendarInterval` | `.timer` with `OnCalendar=` | Requires a paired `oneshot` service. |
| `StartInterval` (every N sec) | `.timer` with `OnUnitActiveSec=`/`OnBootSec=` | Monotonic. |
| `ThrottleInterval` | `RestartSec=` | Min delay between restarts. |
| `StandardOutPath` / `StandardErrorPath` | `StandardOutput=file:…` / `StandardError=append:…` | Default systemd target is `journal`, not a file. |
| `WorkingDirectory`, `EnvironmentVariables`, `UserName`, `GroupName` | `WorkingDirectory=`, `Environment=`/`EnvironmentFile=`, `User=`, `Group=` | Direct analogues. |
| `ProcessType` (Background/Interactive/Adaptive/Standard) | (no exact analogue) | Closest is scheduling/`Nice=`/`CPUWeight=`/`IOWeight=`; not 1:1. |
| Scope `USER_AGENT` (`gui/<uid>`) | `systemctl --user` + `~/.config/systemd/user/` | Plus `enable-linger` to run without login. |
| Scope `SYSTEM_DAEMON` (`system`) | system manager + `/etc/systemd/system/` | Needs root. |
| Scope `GLOBAL_AGENT` (`/Library/LaunchAgents`) | `/etc/systemd/user/` | Admin-provided units for all users' managers. |
| `launchctl bootstrap` / `bootout` | `enable --now` (+ `daemon-reload`) / `disable --now` (+ delete file) | |
| `launchctl kickstart [-k]` | `start` / `restart` | |
| `launchctl kill <sig>` | `systemctl kill -s <sig>` / `stop` | |
| `launchctl print` | `systemctl show -p …` (structured) / `status` (human) | |

### Quirks & gotchas (call these out in the backend)

1. **`daemon-reload` is mandatory after writing/editing a unit file.** systemd caches
   units; a freshly written `.service` is invisible until reload. The install path
   must always `daemon-reload` before `enable`/`start`.
2. **enable ≠ start.** Two orthogonal axes (boot-persistence vs running-now). Map
   launchd "install+load" to `daemon-reload` → `enable --now`; map "uninstall" to
   `disable --now` → delete file → `daemon-reload`.
3. **User lingering.** Without `loginctl enable-linger <user>`, user-scope services
   die at logout and don't start at boot. The library should expose this and/or warn.
4. **`--user` needs a real user session/bus.** Running `systemctl --user` as the wrong
   user or without `$XDG_RUNTIME_DIR`/`DBUS_SESSION_BUS_ADDRESS` fails. Set the
   environment correctly when shelling out for user scope.
5. **Unit naming is not reverse-DNS.** Map labels to short, filesystem-safe unit names
   (e.g. lowercase, `.`→`-`), and store the original label (e.g. as `Description=` or
   an `X-` key) if round-tripping matters.
6. **Default output is the journal, not a file.** If callers expect log files,
   translate to `StandardOutput=append:…`.
7. **Timers are two units.** A calendar job = `.timer` + (usually `oneshot`)
   `.service`; you `enable`/`start` the **timer**.
8. **Restart throttling.** `Restart=always` still gives up after `StartLimitBurst`;
   add `StartLimitIntervalSec=0` for indefinite restarts.
9. **Precedence/locations.** Only ever write to `/etc/systemd/system/` (system) and
   `~/.config/systemd/user/` (user) — never `/usr/lib/systemd/*` (package territory).
10. **Privilege.** System-scope operations require root (sudo/polkit); surface this
    clearly rather than failing opaquely.

### Recommended native approach

**Recommendation: shell out to `systemctl` and write unit files with plain file
I/O** — mirroring the existing launchd design (file I/O for plists +
`launchctl` subprocess).

- **Write** the `.service`/`.timer` text directly to `/etc/systemd/system/` or
  `~/.config/systemd/user/` (a tiny INI writer; no third-party dep, unlike `dd-plist`
  on the macOS side).
- **Control** via `systemctl [--user] daemon-reload | enable --now | disable --now |
  start | stop | restart`.
- **Query** via `systemctl [--user] show <unit> -p LoadState,ActiveState,SubState,
  UnitFileState,MainPID,ExecMainStatus,ExecMainCode,Result` — structured `Key=Value`,
  trivial to parse into a `JobStatus`.

Keep this behind the same `Launchctl`-style interface (call it e.g. `ServiceControl`
with a `Systemctl` impl) so tests can stub the process runner, exactly as planned for
macOS.

**Why not D-Bus/sd-bus via FFM:** it gives typed results and async `JobRemoved`
signals (no parsing), but needs FFM bindings to `libsystemd` (or a Java D-Bus
client) plus a live bus connection and polkit handling — significant complexity and a
heavier dependency/runtime surface for marginal benefit. It also still can't avoid
writing unit files. Revisit only if a consumer needs event-driven status streaming.
The `systemctl` subprocess path keeps the two OS backends architecturally symmetric
and dependency-light.

---

## Sources

- systemd.service(5): https://www.man7.org/linux/man-pages/man5/systemd.service.5.html
- systemd.unit(5): https://www.man7.org/linux/man-pages/man5/systemd.unit.5.html
- systemd.exec(5): https://www.man7.org/linux/man-pages/man5/systemd.exec.5.html
- systemd.timer(5): https://www.man7.org/linux/man-pages/man5/systemd.timer.5.html
- systemd.time(7): https://www.man7.org/linux/man-pages/man7/systemd.time.7.html
- systemctl(1): https://www.man7.org/linux/man-pages/man1/systemctl.1.html
- org.freedesktop.systemd1 D-Bus interface: https://man7.org/linux/man-pages/man5/org.freedesktop.systemd1.5.html
- The new sd-bus API of systemd (Poettering): https://0pointer.net/blog/the-new-sd-bus-api-of-systemd.html
- systemctl show properties: https://linuxcommandlibrary.com/man/systemctl-show , https://www.simplified.guide/systemd/unit-show-properties
- Arch Wiki — systemd/User (lingering): https://wiki.archlinux.org/title/Systemd/User
- Arch Wiki — systemd/Timers: https://wiki.archlinux.org/title/Systemd/Timers
- RHEL 9 — Working with systemd unit files (drop-ins/precedence): https://docs.redhat.com/en/documentation/red_hat_enterprise_linux/9/html/using_systemd_unit_files_to_customize_and_optimize_your_system/assembly_working-with-systemd-unit-files_working-with-systemd
- enable-linger / user persistence: https://blog.khmersite.net/p/how-to-enable-check-linger-for-user/ , https://oneuptime.com/blog/post/2026-03-18-use-loginctl-enable-linger-rootless-podman/view
- `systemctl --user` session/bus caveat: https://linuxvox.com/blog/can-i-control-a-user-systemd-using-systemctl-user-after-sudo-su-myuser/
- Indefinite restarts (StartLimit): https://michael.stapelberg.ch/posts/2024-01-17-systemd-indefinite-service-restarts/
- Timers vs cron: https://opensource.com/article/20/7/systemd-timers , https://xtom.com/blog/systemd-vs-cron-linux-task-scheduling/
- Type=oneshot deep-dive: https://www.redhat.com/en/blog/systemd-oneshot-service
