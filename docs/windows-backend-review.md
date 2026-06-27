# Windows backend — three-implementation review & selection

> **Role:** independent reviewer. **Task:** assess three competing Windows-backend
> implementations for *correctness out of the box, as-is* (first) and *conceptual / code
> simplicity* (second), rule out anything with a fatal bug, and recommend one to merge to `main`.
> Incremental polish is explicitly **out of scope** — we pick the one that works correctly as
> written, then improve later.

**Date:** 2026-06-27 · **Reviewer build/test JDK:** Temurin 25.0.3 (downloaded for this review).

---

## TL;DR — recommendation

> ### ✅ Merge **`claude/project-familiarization-axnrtj`** (referred to below as **B3**).
>
> All three implementations are **correct, build on JDK 25, pass their unit tests, and passed a
> real install→start→RUNNING→uninstall lifecycle on a `windows-latest` CI runner.** **None has a
> fatal bug** — so any of the three is mergeable. The choice is therefore decided on
> *out-of-the-box robustness* and *simplicity*, and **B3 has the fewest functional rough edges
> while being nearly as small and clean as the leanest entry.**
>
> - **B3 (`axnrtj`) — recommended.** Least-privilege SCM access (inspection works for non-admin),
>   idempotent start/stop/restart, working named-user credentials, a non-racy upsert, and service
>   dependencies. Only debits are stylistic (3 `var` uses) and a minor foreign-service
>   inspection wrinkle. **Best on the primary criterion.**
> - **B1 (`java-launchd-api-design-zkcty4`) — very close runner-up; the cleanest code.** Leanest
>   (11 classes / 1964 LOC), convention-perfect (zero `var`/`Optional`/Streams), best named-user
>   support, idempotent lifecycle. Held back by **one real out-of-the-box gap**: read-only
>   `status()`/`list()`/`discover()` require Administrator and **throw for a non-admin caller**,
>   and an upsert-by-delete-recreate that risks `ERROR_SERVICE_MARKED_FOR_DELETE`. Pick this one
>   if the team weights code-cleanliness over those two edges (both are small fixes — but we are
>   choosing as-is).
> - **B2 (`project-familiarization-p3hdgl`) — third.** The most feature-complete on a couple of
>   daemon-update / task-status edges, but the largest (14 classes / 2168 LOC) and carries the
>   most *functional* gaps: `asUser()` is unsupportable (no credential field at all), and
>   `restart()`/`stop()` of a stopped service throw (no idempotency).

---

## Method & evidence base

For each branch I:

1. **Fetched** the branch and isolated it in its own git worktree.
2. **Built and unit-tested** it locally on **JDK 25** (`mvn -B -ntp verify`). The branches bump
   `maven.compiler.release` 21→25, so JDK 25 is required to compile their FFM code.
3. **Pulled the real Windows CI probe logs** from GitHub Actions. The probe runs the project's
   `SelfTestCli` on a `windows-latest` runner under JDK 25 with
   `--enable-native-access=ALL-UNNAMED`. This is the only place the **real FFM service host** is
   exercised — off-Windows unit tests stub the native seams.
4. **Deep-reviewed** every file in `internal/windows/` (three independent reviewers, one per
   branch), then **personally verified** every differentiator and every candidate fatal bug by
   reading the code and diffing the branches against each other.

> **What the probe proves and what it doesn't.** The CI self-test installs a **SYSTEM_WIDE
> daemon**, starts it, confirms `state=RUNNING` with a real PID, round-trips `readNative`/`read`,
> and uninstalls. That validates the hardest part — the pure-Java FFM `ServiceHost` actually
> speaking the SCM control protocol — on **all three** branches. It does **not** exercise: the
> Task Scheduler (scheduled-job) path, `asUser(name)`, non-admin inspection, or upsert. Those
> were assessed by code reading and are where the differentiators live.

---

## Bottom line: all three are correct; none has a fatal bug

| Evidence | B1 `zkcty4` | B2 `p3hdgl` | B3 `axnrtj` |
|---|---|---|---|
| Builds on JDK 25 | ✅ | ✅ | ✅ |
| Unit tests (whole project) | ✅ 68 pass | ✅ 92 pass | ✅ 89 pass |
| …of which Windows tests | 20 | 25 | 26 |
| **Real Windows CI lifecycle self-test** | ✅ `SELFTEST PASS`, pid 1344 | ✅ `SELFTEST PASS`, pid 8660 | ✅ `SELFTEST PASS`, pid 5784 |
| FFM correctness (descriptors, struct offsets, upcalls, wide strings, handle/arena hygiene) | ✅ verified | ✅ verified | ✅ verified |
| **Fatal bugs** | **none** | **none** | **none** |

All three faithfully mirror the established macOS/systemd backend shape: a `Backend`
implementation, all native access behind stubbable interfaces (`Scm` + `TaskScheduler`), a
definition writer/reader, and `Recording*` test fakes. In all three the **sidecar JSON** in
`%ProgramData%\ServicePal\<id>.json` is the definition file, the managed-by marker, and the
runtime command source for the host; install **routes by job shape** (`spec.schedule() != null`
→ Task Scheduler, else → SCM service + `ServiceHost`); and `Capabilities` correctly reports
`perUserInstall=false` (Windows v1 is SYSTEM_WIDE only).

---

## Shared characteristics (NOT differentiators)

Each branch was reviewed in isolation, so some reviews flagged these as "issues" — but they are
**common to all three**, so they cancel out of the comparison:

- **`restart()` = `stop()` then `start()`** (no atomic restart). The implementation plan itself
  specified this shape. (Robustness then depends on idempotency — see the matrix, where the
  branches *do* differ.)
- **Discovery is sidecar-scoped.** `list()` enumerates only `%ProgramData%\ServicePal\*.json`, so
  it returns only services ServicePal created — effectively `list() == listManaged()`. The
  macOS/systemd backends scan the real OS directories and surface foreign services with
  `managed=false`; the plan called for `EnumServicesStatusExW`. All three deferred this. Honest,
  documented v1 limitation; not a crash.
- **Upsert across a job-shape change orphans the old object.** Re-installing a spec that was a
  service as a scheduled task (or vice-versa) leaves the previous SCM service / task registered.
  Uncommon; present in all three.
- **Task `<Arguments>` are not robustly quote-escaped.** Scheduled commands with embedded quotes
  can mis-parse (the daemon path is immune — it uses `ProcessBuilder(List)`). B1 is marginally
  better here (it quotes space-containing args); B2/B3 plain-join.
- **Hand-rolled `Json`** (~282–302 LOC) to avoid a JSON dependency — present in all three and the
  single largest comprehension cost in each.

---

## The differentiators

| # | Dimension | B1 `zkcty4` | B2 `p3hdgl` | B3 `axnrtj` | Best |
|---|---|---|---|---|---|
| 1 | **Non-admin inspection** (read access masks) | ❌ uses `SC_MANAGER_ALL_ACCESS`/`SERVICE_ALL_ACCESS` everywhere → `status`/`list`/`discover` **throw** for non-admin | ✅ least-priv (`SC_MANAGER_CONNECT`+`SERVICE_QUERY_STATUS`) | ✅ least-priv | B2/B3 |
| 2 | **Lifecycle idempotency** (restart/stop of stopped svc) | ✅ tolerates 1056/1062 | ❌ no tolerance → `stop`/`restart` of a stopped svc throws | ✅ tolerates 1056/1062 | B1/B3 |
| 3 | **Named-user `asUser()`** | ✅ `account`+`password` fields, `.\name` qualification | ❌ **no credential field at all** (only `startType`/`dependsOn`) | ✅ `password` field | B1 |
| 4 | **Daemon upsert** | ⚠️ delete+recreate (risks `ERROR_SERVICE_MARKED_FOR_DELETE`) | ✅ in-place `ChangeServiceConfig` (most complete) | ✅ `setStartType` only (safe; binPath is invariant) | B2≈B3 |
| 5 | **Task enable/disable** | ❌ no-op (documented) | ✅ `schtasks /change` | ✅ `schtasks /change` | B2/B3 |
| 6 | **Scheduled-task status** | ⚠️ exists→UNKNOWN/STOPPED | ✅ `TaskInfo` (runState+disabled) | ✅ running→RUNNING/STOPPED | B2 |
| 7 | **Service dependencies** (`dependsOn`) | ❌ ignored | ❌ ignored | ✅ passed to `CreateService` | B3 |
| 8 | **Conventions** (`var` ban) | ✅ 0 | ⚠️ 1 | ⚠️ 3 | B1 |
| 9 | **Size** (windows pkg) | 11 classes / **1964 LOC** | 14 classes / 2168 LOC | 13 classes / 1979 LOC | B1 |
| 10 | **Host debug logging** | — | — | ✅ logs SCM protocol sequence | B3 |

Net: **no branch dominates**, but the *functional* (correctness/robustness) rows — 1, 2, 3, 4 —
break **against B2** (rows 2 & 3) and **against B1** (rows 1 & 4), while **B3 is on the winning
side of every functional row** except #3 where it is adequate (has a password field).

---

## Per-branch detail

### B1 — `claude/java-launchd-api-design-zkcty4`  (runner-up; cleanest code)

The original development lineage. **11 classes, 1964 LOC** — the leanest. **Convention-perfect:**
zero `var`/`Optional`/Streams, tabs throughout. Best-isolated FFM layer; `WindowsBackend.java`
is crisp and the most faithful mirror of the systemd backend.

**Strengths**
- Cleanest, smallest, convention-perfect code; easiest to read top-to-bottom.
- Best named-user support: `WindowsOptions` carries `account` + `password`, and the backend
  qualifies a bare local name as `.\name` for `lpServiceStartName`
  (`WindowsBackend.java:358-368`).
- Idempotent lifecycle: `FfmScm` tolerates `ERROR_SERVICE_ALREADY_RUNNING`/`NOT_ACTIVE`
  (`FfmScm.java:157,172`).
- Quotes space-containing task arguments (better than B2/B3 on the scheduled path).

**Issues**
- **MAJOR — read-only ops require Administrator.** `FfmScm` opens the SCM and service handles
  with `SC_MANAGER_ALL_ACCESS` (`0xF003F`, includes `CREATE_SERVICE`) and `SERVICE_ALL_ACCESS`
  for *every* call including `queryStatus` (`FfmScm.java:29-30,246,248`). A non-admin process
  gets `ERROR_ACCESS_DENIED`, so `status()`/`isManaged()`/`discover()`/`list()` **throw**. And
  because `queryStatus` throws `NativeCommandException` (not `DefinitionIOException`), it is *not*
  caught in the `discover()` loop (`WindowsBackend.java:100-104`), so `list()` blows up entirely
  whenever a managed service exists and the caller isn't elevated. This diverges from the
  established "honest, non-throwing discovery" of the macOS/systemd backends. CI didn't catch it
  because the probe runs elevated and on a service-less machine. *Fix is ~2 constants — but we
  choose as-is.*
- **MAJOR — upsert by delete+recreate.** `installDaemon` does `stop` → `delete` → `create` for an
  existing service (`WindowsBackend.java:182-195`). `DeleteService` is deferred while handles are
  open, so an immediate recreate can hit `ERROR_SERVICE_MARKED_FOR_DELETE` — the exact hazard the
  implementation plan called out. B2/B3 avoid it (they reconfigure in place).
- MINOR — task enable/disable is a no-op; scheduled status is coarse (exists→UNKNOWN);
  `ServiceHost.report()` allocates a fresh status struct from the long-lived arena on every
  status report (a slow leak); `WindowsOptions.dependsOn` is accepted but ignored.

### B2 — `claude/project-familiarization-p3hdgl`  (third)

**14 classes, 2168 LOC** — the largest, with the most value-type classes (`ServiceKind`,
`TaskInfo`, `WinStartType`). Also ships an OpenRC backend (not reviewed here).

**Strengths**
- Most complete daemon **upsert**: `scm.updateConfig` rewrites binPath+startType+account in place
  via `ChangeServiceConfig` (`WindowsBackend.java:169-173`) — no delete/recreate race.
- Richest **scheduled-task status** via a dedicated `TaskInfo` (runState + disabled).
- Real **task enable/disable**; least-privilege read masks (non-admin inspection works).
- Cleanest CI wiring: a dedicated, explicit *"mutation self-test (Windows, admin + native
  access)"* probe step.

**Issues**
- **MAJOR — `asUser(name)` is unsupportable.** `WindowsOptions` was **not extended** — it has only
  `startType`/`dependsOn`, **no password/account** — and `WindowsBackend` always passes
  `password=null` (`:170,172`). A real named account can't log on, so the service fails to start;
  the task path emits no `<LogonType>`/`/rp` either. Yet `capabilities().namedUser()` returns
  `true` (`:79`). Advertised, but broken.
- **MAJOR — no lifecycle idempotency.** `FfmScm` does **not** special-case
  `ERROR_SERVICE_ALREADY_RUNNING`/`NOT_ACTIVE`, so `stop()` of a stopped service and `restart()`
  of a stopped service **throw**. `restart` is a first-class verb; this is wrong in a common case.
- MINOR — 1 `var` (`FfmScm.java:96`); largest surface; task args plain-joined (no quoting).

### B3 — `claude/project-familiarization-axnrtj`  (recommended)

**13 classes, 1979 LOC** — within 1% of B1's size. Also ships an OpenRC backend (not reviewed
here). The only branch whose probe additionally **logs the host's SCM protocol sequence**
(`host starting` → `child started, service RUNNING` → `stop control received` → `STOPPED`),
which is concrete evidence the upcall handler and state machine work, and a nice operability win.

**Strengths**
- **On the winning side of every functional differentiator:** least-privilege read masks
  (`FfmScm.java:29-36`; non-admin inspection works), idempotent start/stop (tolerates 1056/1062,
  `FfmScm.java:189,202`), a **non-racy upsert** (reconciles start type in place,
  `WindowsBackend.java:186-191`), real task enable/disable, **and** service dependencies passed
  to `CreateService` (`WindowsBackend.java:189-190,324-326`) — the only branch to wire `dependsOn`.
- Named-user `password` field present; backend re-asserts the SYSTEM_WIDE gate defensively
  (`WindowsBackend.java:166`).
- Same clean conceptual model and seam isolation as the others; comprehensive, readable
  `ServiceHost` with centralized status reporting.

**Issues**
- MINOR — **3 `var` uses** (`ServiceHost.java:107-108`, `FfmScm.java:82`) violate the owner's
  "never use `var`" rule. Trivial, runtime-invisible.
- MINOR — for a *foreign* (unmanaged) service, `status(id)` returns a live status but
  `read(id)`/`readNative(id)` return `null` — a small inspection inconsistency for services you
  didn't create. (B1/B2 are similarly sidecar-scoped for reads.)
- MINOR — bare named-user account is not `.\`-qualified (B1 does this); `disable()` maps a
  service to `DEMAND` rather than true `DISABLED` (a defensible, systemd-like choice).

---

## Why B3 over B1 (the real decision)

B2 ranks third on the functional gaps above. The genuine contest is **B1 vs B3**, and they are
close: B1 is the cleaner, leaner, convention-perfect codebase; B3 is the more robust one
out-of-the-box.

The brief orders the criteria: **correctness / works-out-of-the-box first, simplicity second.**
On the primary axis, B3 wins — its only debits are stylistic, whereas B1 has two real
out-of-the-box edges:

1. **A non-admin `java -jar servicepal.jar` (the jar's own `DiscoverCli` main) throws** on a
   machine that has a ServicePal-managed service, because B1's read path demands Administrator.
   B3 inspects fine without elevation.
2. **B1's upsert can fail intermittently** with `ERROR_SERVICE_MARKED_FOR_DELETE`. B3's upsert
   can't.

On the secondary axis (simplicity) B1 leads, but only narrowly — **1979 vs 1964 LOC, 13 vs 11
classes, same conceptual model** — not enough to overturn a clear primary-axis win. B3's `var`
nits are the kind of thing the *later* polish pass fixes in minutes.

**Therefore: merge B3.** If, after integrating, the team decides convention-purity and the
absolute leanest code matter more than the two B1 edges (both quick fixes), B1 is an excellent
fallback — it is the better-written code and the source to mine first during the polish phase
(its `.\`-qualified accounts, idempotency constants, and arg-quoting are all worth porting).

---

## After choosing: what to validate during integration

The CI probe only covered the SYSTEM_WIDE **daemon** path. Before relying on the rest, exercise
these on a real Windows box (none is known-broken in B3; they are simply unproven by CI):

- **Scheduled jobs** end-to-end: `install` a spec with a `Schedule`, confirm the Task Scheduler
  task is created, runs, and `status()` reflects it; check Task XML for an argument with spaces.
- **Upsert**: install, then re-install a modified spec (changed command / start type) and confirm
  reconciliation; and the shape-change case (add/remove a `Schedule`) — the known shared
  orphaning limitation.
- **`asUser(name)`**: a real local account with a password (and the "Log on as a service" right) —
  the credential plumbing exists in B3 but is untested live.
- **Non-admin inspection**: `DiscoverCli` / `status()` / `list()` as a standard user with a
  managed service present (this is where B1 would have failed).
- Consider promoting **`list()` to real `EnumServicesStatusExW` enumeration** (the shared
  sidecar-scoped limitation) as the first post-merge feature.
