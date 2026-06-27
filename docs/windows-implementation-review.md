# Windows backend — three-implementation review & selection

**Reviewer:** AI agent (Reviewer role) · **Date:** 2026-06-27
**Mandate:** Three AI agents each implemented the Windows backend on separate branches. Assess which
are **complete and correct**, rule out anything with a fatal bug, and recommend **one** to roll into
`main`. Priority order: **correctness first**, then **conceptual & code simplicity / comprehensibility**.
Incremental polish is explicitly *out of scope* for this decision — we want the one that works
correctly out of the box **as is**. (Two branches also contain an OpenRC backend; per the mandate,
OpenRC was **not** assessed here, but its presence is noted as a scope/isolation factor.)

## TL;DR

| | **zkcty4** | **p3hdgl** | **axnrtj** |
|---|---|---|---|
| Branch | `claude/java-launchd-api-design-zkcty4` | `claude/project-familiarization-p3hdgl` | `claude/project-familiarization-axnrtj` |
| Scope | **Windows only** | Windows **+ OpenRC** | Windows **+ OpenRC** |
| Builds on JDK 25 | ✅ | ✅ | ✅ |
| Unit tests | ✅ 68 (20 Windows) | ✅ 92 (25 Windows) | ✅ 89 (26 Windows) |
| **Real `windows-latest` probe** | ✅ `SELFTEST PASS` | ✅ `SELFTEST PASS` | ✅ `SELFTEST PASS` |
| Fatal bugs | **none found** | **none found** | **none found** |
| House-style adherence (`var` ban) | **0 violations** | 1 `var` | 3 `var` |
| Shared-code blast radius | **+4 lines** (wiring only) | Backend/ServiceManager + new exception | Backend + **macOS backend** edited |
| Self-test robustness | no settle/poll (race-prone) | polls ≤15s | polls ≤15s |
| `read()` round-trips a schedule | ✗ | **✅** | ✗ |

**All three are complete, correct, and validated** — each actually installs → starts →
reports `RUNNING` with a real PID → uninstalls a service on a real Windows runner. **None has a fatal
bug; none can be ruled out on correctness.**

> ### Recommendation: **zkcty4**
> It is the **most isolated** change (adds the Windows backend and *nothing else* — 4 lines of shared
> code), has **zero** house-style violations, is the natural continuation of the already-merged
> macOS/systemd line, and is **proven to work out of the box** on a real Windows runner. It best
> satisfies "roll the Windows implementation into `main`" with the least risk and the least to review.
>
> **Runner-up: p3hdgl** — the most *robust/complete* Windows implementation on its own merits (settle/poll
> self-test, faithful `read()` round-trip, dedicated JSON codec). Choose it over zkcty4 only if you
> want that extra robustness margin now **and** are comfortable also merging its bundled OpenRC backend
> and its edits to the shared `Backend`/`ServiceManager` surface.

---

## How this was assessed

Correctness of an FFM-based Windows service backend **cannot** be judged from unit tests alone: the
tests stub the native seams (`Scm`/`TaskScheduler`) and run off-Windows, so they never exercise the
two files where a bug would actually bite — `FfmScm` (advapi32 bindings) and `ServiceHost` (the SCM
protocol via FFM up-calls). The assessment therefore combined five independent methods:

1. **Built and tested all three on JDK 25** (downloaded Temurin 25.0.3) — the FFM-final LTS the pom
   now targets (`maven.compiler.release=25`). All three compile clean and pass every test.
2. **Read the riskiest code directly** — all three `ServiceHost` and `SidecarWriter` files in full,
   plus the FFM signatures, struct layouts, threading, and arena lifetimes.
3. **Empirically tested the single most dangerous FFM question on JDK 25** (see below).
4. **Three parallel deep-dive code reviews** (one agent per branch, identical rubric) cross-checking
   every Win32 ABI signature, struct offset, handle-lifetime, and routing path against quoted code.
5. **Pulled the real CI "probe" evidence from GitHub Actions** — the decisive signal, since the probe
   *runs* the backend on a real `windows-latest` runner.

### The empirical FFM check (decisive, and a near-miss false-positive)

The upcall targets (`ServiceMain`, the control handler) are bound differently across branches:

- **zkcty4** keeps them **`private`** and binds via `MethodHandles.lookup().bind(this, "serviceMain", …)`.
- **p3hdgl / axnrtj** make them **package-private** and bind via `findVirtual(…).bindTo(this)`, with a
  comment that *"private methods aren't virtual"* — implying zkcty4's approach could throw at runtime.

If binding a **private** method that way threw, zkcty4's service would fail to start — a fatal bug no
off-Windows test could catch. Rather than argue from memory, I tested it on JDK 25:

```
A) bind()        on PRIVATE method  -> OK
B) findVirtual() on PRIVATE method  -> OK
C) findVirtual() on PKG-PRIVATE     -> OK
D) bind()        on PKG-PRIVATE     -> OK
```

**All four styles work on JDK 25.** The full-power lookup has private access, so zkcty4's `bind` on a
private method is fine. No branch is disadvantaged here. (The conventional "use `findSpecial` for
private methods" wisdom predates the relaxed behavior; both approaches are valid.)

### The decisive evidence: real `windows-latest` probe runs

Each branch's HEAD pushed a `probe.yml` run that builds the shaded jar with JDK 25 and executes
`SelfTestCli` on a real `windows-latest` admin runner. **Important:** the probe step ends with
`|| true`, so the *job's* green check does **not** by itself prove the self-test passed — you must read
the log line. I did, for all three HEAD runs:

| Branch | Probe run / Windows job | Log result |
|---|---|---|
| zkcty4 | run `28299194390`, job `83844408767` | `status: installed=true managed=true state=RUNNING pid=1344` → all `[PASS]` → **`SELFTEST PASS`** |
| p3hdgl | run `28299984677`, job `83846507206` | `status: … state=RUNNING pid=8660` → all `[PASS]` → **`SELFTEST PASS`** |
| axnrtj | run `28300515019`, job `83847865946` | `status: … state=RUNNING pid=5784` → all `[PASS]` → **`SELFTEST PASS`** |

axnrtj's probe also dumps the host log, which is the clearest possible proof the FFM SCM protocol
round-trips end-to-end on real Windows:

```
host starting for id=com.u1.servicepal.selftest
child started, service RUNNING
stop/shutdown control received
dispatcher returned; host exiting
service STOPPED (exit=1)
```

The self-test exercises the **daemon path** for all three: `asSystemDaemon()` (LocalSystem) running a
long console-less command (`ping -n …`), then `install → enable → start → status(RUNNING + pid) →
readNative → read(round-trips command) → uninstall`. This is the load-bearing path (the FFM
`ServiceHost`). The **Task Scheduler path** (scheduled jobs) is *not* exercised on real Windows by any
branch — it's covered only by stubbed unit tests + code review (equal across all three).

---

## Correctness verdict — all three pass

The FFM/SCM fundamentals are **correct in every branch** (verified by direct read + cross-review +
the live probe):

- **`SERVICE_STATUS`** laid out as 7 DWORDs / 28 bytes at the right offsets; `dwControlsAccepted`
  includes `SERVICE_ACCEPT_STOP` only when `RUNNING`.
- **`SERVICE_STATUS_PROCESS`** read as 36 bytes with `dwProcessId` at offset 28 (matches the real
  PIDs observed in the probe).
- **`SERVICE_TABLE_ENTRYW`** is a NULL-terminated 2-entry array (32 bytes on x64).
- **`CreateServiceW`** is bound with the correct 13-argument signature; start types 2/3/4; service
  type `SERVICE_WIN32_OWN_PROCESS` (0x10).
- **`RegisterServiceCtrlHandlerExW`** + a 4-arg `HandlerEx` up-call (correct `Ex` variant).
- **Up-call stubs allocated on a long-lived shared `Arena`** (field-scoped) — they outlive the
  dispatcher; no use-after-free.
- **1053 avoided:** `SERVICE_START_PENDING` is reported *before* the child spawn and `SERVICE_RUNNING`
  promptly after a fast `ProcessBuilder.start()`; the blocking child wait runs off the dispatcher
  thread (separate supervisor thread in zkcty4/p3hdgl; the SCM's separate control thread in axnrtj).
- **Wide strings** UTF-16LE + NUL; **handles closed on all paths**; **`GetLastError` captured** via
  `Linker.Option.captureCallState`.
- Wiring (`DefaultServiceManager` → `WindowsBackend`), `Capabilities` (per-user = false,
  system-wide = true, structured status = true), and `supportedInstallations` (SYSTEM_WIDE only) are
  correct in all three.

No certain fatal flaw was found in any branch — no wrong signature, no struct mis-layout, no
arena/handle lifetime bug, no missing `SetServiceStatus`, no JVM-abort path.

---

## Per-implementation notes

### zkcty4 — Windows only (recommended)
- **Cleanest integration by far.** Shared-code delta is **+4 lines** in `DefaultServiceManager`
  (the platform wiring). It does **not** touch `Backend`, `ServiceManager`, `UnimplementedBackend`,
  or the macOS/systemd backends. It is the same branch lineage that built and validated macOS +
  systemd, so merging it is a near-fast-forward addition of *just* Windows.
- **Best house-style adherence:** 0 `var`, 0 Streams, 0 `Optional` (the project bans all three).
- Clean, well-commented `ServiceHost` with a handy `--console` foreground mode for manual debugging.
- **Trade-offs (all "incremental", not fatal):** the self-test queries status immediately after
  `installEnableStart` with **no settle/poll loop** — a latent race that *happened* to pass on the
  runner but could produce a spurious probe `FAIL` on a slower start; `report()` allocates a small
  segment per call on the never-closed shared arena (negligible slow leak); `read()` of a scheduled
  job does not reconstruct the `Schedule` (the sidecar records only `kind`).

### p3hdgl — Windows + OpenRC (strong runner-up)
- **Most robust/complete Windows backend on its own merits:** the self-test **polls up to 15s** for
  `RUNNING` (no race); the sidecar **persists the full `Schedule`** so `read()` round-trips scheduled
  jobs faithfully (the only branch that does); a dedicated `Json` codec with its own `JsonTest`
  hardens escaping/parsing; a graceful `liveStatusWithoutSidecar` path.
- Near-clean style: a single `final var p = ValueLayout.ADDRESS` in `FfmScm`.
- **Cost of choosing it:** it bundles a full OpenRC backend (~700 LOC, out of scope for this review)
  and edits the shared `Backend`/`ServiceManager` surface and adds a `PermissionException`. Selecting
  it means merging (or surgically extracting from) more than just Windows.

### axnrtj — Windows + OpenRC (correct; third on code-quality grounds)
- Fully correct and validated, with the **best probe instrumentation** (dumps the host log + sidecar
  dir on the runner — invaluable for future debugging) and the **simplest `ServiceHost` threading**
  (blocks `ServiceMain` directly in `supervise()`; valid because the SCM delivers controls on a
  separate thread). Richer `WindowsOptions` support (`dependsOn`, password).
- **Weakest on the stated tiebreaker (comprehensibility/house style):** **3 `var` usages** violating
  the project's explicit "Never use `var`" rule; like zkcty4, `read()` drops the schedule and (per the
  cross-review) `windows()` options on round-trip. It also **edits the already-shipped macOS
  `LaunchdBackend`**, widening the re-validation surface for a "just add Windows" goal.

---

## Why zkcty4 over the others

Correctness is a three-way tie (all validated on real Windows), so the decision rests on the
mandate's second criterion — **simplicity / comprehensibility** — and on "works out of the box **as
is** with the least risk":

1. **Isolation.** zkcty4 changes 4 lines of shared code; the other two bundle an out-of-scope OpenRC
   backend and modify shared interfaces (and, for axnrtj, the validated macOS backend). For "roll the
   Windows implementation into `main`," zkcty4 is the surgical match.
2. **House style.** zkcty4 is the only branch with zero `var`/Streams/`Optional` violations — i.e. the
   most faithful to the codebase's stated conventions, which is exactly "comprehensibility."
3. **Lineage.** It continues the branch that already built and probe-validated macOS + systemd, so it
   reuses the proven patterns and carries the least integration risk.
4. **Proven.** Its `windows-latest` probe is green with the full lifecycle and a real PID.

The robustness extras that p3hdgl/axnrtj have over zkcty4 (poll loop, schedule round-trip, richer
options) are real and nice — but they fall squarely under the "little tweaks we could make to improve"
that the mandate defers to the *post-selection* phase. None is needed for correct out-of-the-box
operation, and all are cheap to port from the other branches later.

---

## Follow-ups for the post-selection (incremental) phase

Once zkcty4 is integrated and smoke-tested, borrow these from the runners-up (do **not** block merge
on them):

- **Add a settle/poll loop to the Windows self-test** before asserting `RUNNING` (from p3hdgl/axnrtj) —
  removes the latent probe-flakiness in zkcty4.
- **Persist the `Schedule` (and `windows()` options) in the sidecar** so `read()` round-trips scheduled
  jobs (from p3hdgl) — closes the one real `read()`-fidelity gap.
- **Adopt axnrtj's probe debug step** (dump host log + sidecar dir on the Windows runner) — makes
  future Windows failures diagnosable from CI logs.
- **Validate the Task Scheduler (scheduled-job) path on a real runner** — currently unproven on Windows
  for *every* branch; add a scheduled-job case to the self-test.
- Consider switching `report()` to a per-call confined arena (or a single reused buffer, as
  p3hdgl/axnrtj do) to avoid the tiny shared-arena growth.
