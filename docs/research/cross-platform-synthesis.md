# Cross-Platform Synthesis — toward a universal service-management API

> Step 1 deliverable: take the four platform research docs and abstract out the common
> structure, surface the places where the platforms genuinely diverge, and frame the
> decisions the API design (step 2) must resolve. **No API is designed here** — this is the
> "look at the totality" document.

Read alongside:
- [`../../CLAUDE.md`](../../CLAUDE.md) — macOS / launchd (the original target)
- [`windows-services.md`](windows-services.md) — SCM + Task Scheduler
- [`linux-systemd.md`](linux-systemd.md) — systemd units + timers
- [`linux-other-init.md`](linux-other-init.md) — OpenRC / SysV / runit / BusyBox + detection
- [`java-ffm-native-access.md`](java-ffm-native-access.md) — FFM vs subprocess, Java baseline

---

## 1. The one-paragraph picture

Every target OS has the same *job to be done* — "register a command with the OS so it runs as
a supervised background process and/or on a schedule, then start/stop/query/remove it" — but
each splits that job across **different subsystems**, uses a **different config format**, and
draws the **user-vs-system boundary** in a different place. macOS `launchd` is the most
unified (one daemon does both supervision and scheduling, runs any binary, and a user can
install an agent with no admin rights). Windows is the most fragmented (two unrelated
subsystems, and services can't run arbitrary binaries). Linux is bimodal: **systemd** is
launchd-like and ~90% of bare-metal/VM installs, but the container world (Alpine/OpenRC, or
no init at all) breaks the "Linux = systemd" assumption exactly where server software runs.

---

## 2. The master mapping table

The common vocabulary on the left is provisional (it's roughly the launchd model already in
`CLAUDE.md`). "—" means no native equivalent; "≈" means approximate/lossy.

| Concept | macOS launchd | Linux systemd | Windows | Non-systemd Linux (OpenRC/SysV/runit) |
|---|---|---|---|---|
| **Definition unit** | `.plist` (XML) | `.service` unit (INI) | registry via SCM API **+** wrapper config; Task XML | init script (shell) / run script |
| **Identity** | `Label` (reverse-DNS) | unit name (short) | service name / task name | script name |
| **Where it lives** | `~/Library/LaunchAgents`, `/Library/Launch*` | `/etc/systemd/system`, `~/.config/systemd/user` | `HKLM\...\Services` (+`System32\Tasks`) | `/etc/init.d`, `/etc/sv` |
| **Run any binary?** | ✅ yes | ✅ yes | ❌ **service needs SCM protocol** (wrapper); ✅ scheduled task | ✅ yes |
| **Long-running daemon** | launchd job | `.service` | SCM service (**+wrapper**) | init/run script |
| **Calendar/cron job** | `StartCalendarInterval` | `.timer` (`OnCalendar=`) | Task Scheduler trigger | ❌ (fall back to cron) |
| **Interval job** | `StartInterval` | `.timer` (`OnUnitActiveSec=`) | Task trigger repetition | ❌ (cron) |
| **Auto-start at boot/login** | `RunAtLoad` | `enable` (`WantedBy=`) | start type `auto` / logon task | `rc-update add` / runlevel symlink |
| **Keep alive / restart** | `KeepAlive` (continuous, conditional) | `Restart=` (rich) | recovery actions (≈ finite, crash-only) | OpenRC `supervise-daemon`/runit ✅; SysV ❌ |
| **Stdout/stderr files** | `StandardOutPath`/`ErrorPath` | `StandardOutput=file:` (default = journal) | — (wrapper or command) | varies (runit ✅ via logger; SysV ❌) |
| **Run-as user** | `UserName` / agent = login user | `User=`/`Group=` | service account / task principal | script-dependent |
| **Per-user "agent" scope** | ✅ first-class | ✅ `--user` (+ linger) | ≈ per-user service `Tmpl_<LUID>` / logon task | ❌ mostly none |
| **Install (register)** | `launchctl bootstrap` | `daemon-reload` + `enable` | `CreateService` / `RegisterTask` | copy script + `rc-update`/`update-rc.d` |
| **Start now** | `launchctl kickstart` | `systemctl start` | `StartService` / `Start-ScheduledTask` | `rc-service start` / `sv up` |
| **Stop** | `launchctl kill` | `systemctl stop` | `ControlService(STOP)` | `rc-service stop` / `sv down` |
| **Remove** | `bootout` + delete plist | `disable` + delete + reload | `DeleteService` / `Unregister` | `rc-update del` + delete |
| **Status (structured?)** | ❌ `launchctl print` is messy text | ✅ `systemctl show -p` | ✅ `QueryServiceStatusEx` / `sc query` | ≈ `rc-service status` / `sv status` |
| **Privilege for system scope** | root (admin) | root | **Administrator (always)** | root |
| **Privilege for user scope** | none | none (+ linger) | none (per-user svc template needs admin to install) | n/a |

---

## 3. The structural tensions the API must resolve

These are the decisions step 2 has to make. Each is a place where a naïve "lowest common
denominator" port would produce something broken or surprising.

### T1 — "Run any command" is not universal (the Windows service wrapper problem) ⚠️ biggest
On macOS, Linux (systemd and others), a job's command is *any* executable. A Windows
**service** binary must speak the SCM control protocol (`StartServiceCtrlDispatcher` /
`SetServiceStatus`); pointing it at `java -jar app.jar` yields a dead service (error 1053).
Three ways out, each with a cost:
- **Bundle WinSW/NSSM** (a compiled `.exe`) — **conflicts with the owner's "no compiled
  binaries in the library" goal.**
- **Pure-Java service host via FFM upcalls** — ship a Java `ServiceHost` main that itself
  calls `StartServiceCtrlDispatcher` and registers a control handler as an FFM **upcall**,
  then supervises the real command as a child. The registered binary becomes
  `javaw -cp <ourjar> com.jlaunchd...ServiceHost <command>`. **This honours "no native
  binary" using only FFM** (upcalls are confirmed supported). Cost: requires a JVM on the
  target and is the most novel/risky piece of the project.
- **Route daemons through Task Scheduler instead** — works for fire-and-forget but loses true
  service supervision (no auto-restart-on-crash the SCM way).

**This single tension drives the Java-baseline decision** (FFM upcalls ⇒ JDK 22+, ideally 25
LTS) and is worth an explicit owner decision early.

### T2 — One job model, several backend subsystems
launchd unifies daemon + cron. Everyone else splits them:
- Windows: **Service** (daemon) vs **Task Scheduler** (schedule) — two different APIs.
- systemd: `.service` vs `.service`+`.timer` pair — same tool, two files.

So a single `Job` must be **routed to a backend by its shape** (has a calendar/interval
trigger ⇒ scheduler; long-running ⇒ supervised service). The facade hides this; the router is
real complexity.

### T3 — `enable` (boot persistence) vs `start` (run now) are separate on systemd
launchd conflates them (a loaded `RunAtLoad` plist is both). Windows somewhat separates
(start-type vs StartService). systemd fully separates them as orthogonal axes. The universal
API must pick a stance: most likely `install(job, …, {autostart, startNow})` mapping to
`daemon-reload → enable --now` etc., while still allowing the two axes to be controlled
independently (`enable`/`disable` vs `start`/`stop`).

### T4 — Capabilities are not uniform → a capability model, not a fat lowest-common-denominator
Features that exist on some platforms and not others:

| Capability | macOS | systemd | Windows | OpenRC | SysV | runit |
|---|---|---|---|---|---|---|
| Per-user agents | ✅ | ✅ | ≈ | ❌ | ❌ | ❌ |
| Calendar scheduling | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Continuous keep-alive | ✅ | ✅ | ≈ | ✅ | ❌ | ✅ |
| Conditional keep-alive | ✅ | ≈ | ❌ | ≈ | ❌ | ≈ |
| Structured status (pid/exit) | ❌ | ✅ | ✅ | ≈ | ❌ | ≈ |
| Run arbitrary binary as daemon | ✅ | ✅ | ❌→wrapper | ✅ | ✅ | ✅ |
| Resource limits (cgroup-ish) | ≈ | ✅ | ≈ | ≈ | ❌ | ❌ |

Design implication: expose a **`Capabilities` query** per resolved backend and make the API
**fail fast with a clear message** when a job uses a capability the target can't honour
(e.g. a calendar job on SysV), rather than silently degrading. Optionally offer explicit
opt-in fallbacks (calendar-on-SysV → write a crontab entry).

### T5 — Config format is per-platform; there is no single "codec"
- macOS: XML plist → **`dd-plist`**.
- systemd: INI text → trivial hand-written writer (no dependency).
- Windows: registry through the SCM API (never write keys directly) + Task **XML** + wrapper
  config.
- OpenRC/SysV/runit: **generated shell scripts** (templated).

So "serialize a job" is a **per-backend renderer**, not one shared serializer. The domain
model (the `Job` value object) is shared; the rendering is not.

### T6 — Status richness is asymmetric
Windows and systemd give structured, machine-readable status (real PID, exit code, state).
launchd gives messy `launchctl print` text. SysV gives almost nothing. The universal
`JobStatus` should be a **small common core** (installed / enabled / running / pid /
lastExitCode) with everything beyond it optional/nullable, so weak platforms can return
partial data honestly.

### T7 — Linux is itself not one platform
systemd-first is right, but the backend must (a) **detect** the active init (check
`/run/systemd/system`, then `/proc/1/comm`, then marker dirs), (b) distinguish "non-systemd
init X" from "no init at all" (the container case where PID 1 is the app), and (c) give an
actionable error instead of a raw `systemctl: not found`. Treat Linux as a **pluggable
backend** (systemd now; OpenRC next for Alpine/containers; SysV/runit later) behind one
`LinuxServiceBackend` interface.

---

## 4. Native-access strategy (consolidated)

From the FFM research, the recommended **hybrid**:

| Platform | Definition I/O | Control / query | Native API via FFM? |
|---|---|---|---|
| macOS | file I/O (plist, `dd-plist`) | `launchctl` subprocess | No public launchd API — subprocess only |
| systemd | file I/O (INI) | `systemctl` subprocess (`show` for status) | No (sd-bus possible later, not worth it v1) |
| non-systemd Linux | file I/O (templated scripts) | `rc-service`/`sv`/`service` subprocess | No |
| Windows services | SCM API | **`advapi32` via FFM** (clean structs, real status) **or** `sc.exe` subprocess | **Yes** — the one place FFM clearly wins; also enables the pure-Java service host (T1) |
| Windows tasks | Task XML | `schtasks`/PowerShell subprocess | No (Task Scheduler is COM; raw FFM has no COM) |

**Keep every native interaction (subprocess *and* FFM) behind a stubbable interface** — the
`Launchctl`-style seam already in the macOS design — so the whole library unit-tests off-platform.

**Java baseline:** FFM is final in **JDK 22** (JEP 454); preview in JDK 21. The first LTS with
FFM final is **JDK 25** (GA Sep 2025). → **Baseline JDK 25 LTS if we commit to FFM** (Windows
services / service host). If the owner needs to support JDK 17/21 shops, those builds must be
**subprocess-only** (and accept the weaker Windows story). Note also JEP 472 integrity-by-default:
the *consuming application* must pass `--enable-native-access` for FFM.

---

## 5. Proposed shape of the answer (for step 2, not decided here)

The research points toward an architecture like:

- A shared, immutable **domain model** (`Job` + value objects) — platform-agnostic.
- A **`ServiceManager` facade** with the launchd-style verbs (install / uninstall / start /
  stop / restart / enable / disable / status / list).
- A **`Platform`/`Backend` SPI**: `MacLaunchdBackend`, `SystemdBackend`, `WindowsBackend`
  (sub-routing service vs task), `OpenRcBackend`, … selected by runtime detection.
- Per-backend **renderers** (plist / INI / script / task-XML) + per-backend **native seam**
  (subprocess or FFM), all stubbable.
- A **`Capabilities`** object per backend + fail-fast validation when a job needs something
  the target can't do.

The modules then get implemented one platform at a time (step 4): **macOS → systemd → Windows
→ OpenRC**, with the unified library (step 5) assembling them behind the facade.

---

## 6. Decisions to put to the owner before step 2

1. **Windows "run any command as a service":** pursue the **pure-Java FFM service host**
   (preserves "no compiled binaries", needs JDK 25) vs **bundle WinSW** (a compiled exe) vs
   **services run real Windows-service-aware processes only, everything else → Task
   Scheduler**? This is the pivotal call.
2. **Java baseline: JDK 25 LTS** (enables FFM final) — confirm, given it raises the runtime
   floor. Fallback subprocess-only builds for 17/21 wanted, or not?
3. **Linux scope for v1:** systemd-only (with detection + clear error elsewhere), or commit to
   OpenRC (Alpine/containers) in v1 too? Containers are where servers run.
4. **Capability mismatches:** fail-fast (recommended) vs best-effort silent degradation vs
   opt-in fallbacks (e.g. calendar→cron on non-systemd)?
5. **Scope vocabulary:** keep the three launchd scopes (`USER_AGENT`/`GLOBAL_AGENT`/
   `SYSTEM_DAEMON`) as the universal scopes and map them per-platform, or design a new
   cross-platform scope enum (`USER` / `SYSTEM`)?
6. **`enable` vs `start`:** expose them as separate axes everywhere (systemd-faithful) or keep
   the launchd-style combined install/load and treat boot-persistence as a flag?
