# API Design — universal service manager (step 2 proposal)

> **Status:** Proposal for owner approval. No implementation yet. Supersedes the
> single-platform sketch in `CLAUDE.md`. Built on the research in `docs/research/`
> (read `cross-platform-synthesis.md` first).
>
> **Decisions baked in** (owner-approved): pure-Java FFM service host on Windows (bundled,
> runnable, the default path); **JDK 25** baseline; **systemd + OpenRC** both shipped in v1
> as *separate platforms* (no public pluggable SPI); **fail-fast** on capability gaps;
> **root package `com.u1.servicepal`** (project to be renamed *ServicePalForJava* later).
>
> **Code style** (owner-mandated, applies to every sketch below and all real code): `final`
> by default; **no `var`**; **no `Optional`** (use `null`, document nullability; collections
> never null); **no Java Streams**; **tabs** for indentation.

---

## 1. The central question: how uniform can this be?

**Verdict: three tiers.** The design's whole job is to keep Tier 1 huge, Tier 2 clean and
out of the way, and Tier 3 loud and early.

| Tier | What | How it's exposed |
|---|---|---|
| **1. Uniform core** (~90% of real use) | lifecycle verbs; `id`, `command`, env, working dir, **run-as identity**, log files, autostart, restart policy (`NEVER`/`ON_FAILURE`/`ALWAYS`), calendar/interval schedule, status core | the plain `ServiceManager` + `ServiceSpec` — identical on every platform |
| **2. Platform-unique power** | the long tail of per-OS knobs (macOS `ProcessType`, systemd sandboxing/cgroups/ordering, Windows accounts/recovery, OpenRC supervisor) | optional, **typed, namespaced option blocks** (`.mac(...)`, `.systemd(...)`, `.windows(...)`, `.openrc(...)`) — applied on their platform; a block for a **different** platform throws at `install()` (§6) |
| **3. Capability gaps** | things a platform simply cannot do (calendar schedule on OpenRC; per-user scope on OpenRC; conditional keep-alive off systemd/macOS) | `Capabilities` query + **fail-fast** `UnsupportedFeatureException` at `install()` with an actionable message |

So: **yes, the common path is uniform**; the platform-specific bits are neither hidden nor
in your face — they sit in clearly-labelled side rooms you only enter on purpose.

The simple path, identical on macOS, systemd, OpenRC, and Windows:

```java
final ServiceManager mgr = ServiceManager.getServiceManager();

final ServiceSpec backup = ServiceSpec.builder()
		.id("com.example.backup")            // optional; auto-generated if omitted (§4.1)
		.displayName("Nightly Backup")       // optional; defaults to id()
		.command("/usr/local/bin/backup", "--daily")
		.asSystemDaemon()
		.restart(RestartPolicy.ON_FAILURE)
		.stdout(Path.of("/var/log/backup.log"))
		.autoStart(true)
		.build();

mgr.install(backup);                          // render definition + register with the OS
mgr.start("com.example.backup");             // run now
final ServiceStatus st = mgr.status("com.example.backup");
mgr.uninstall("com.example.backup");         // stop + deregister + delete definition
```

That block compiles to a launchd plist + `launchctl`, a systemd unit + `systemctl`, an OpenRC
script + `rc-service`, or a Windows service (via the bundled Java host) + SCM — with no code
change.

---

## 2. Two concepts that are easy to conflate: identity vs. installation

A service answers two *different* questions, and keeping them separate is what makes the model
clean:

| Question | Concept | Values | Where it's set |
|---|---|---|---|
| **As whom does the process run?** | `RunAs` (run-as identity) | current user · named user · root/system | a `ServiceSpec` builder field (§2.1) |
| **Is it set up for one user, or the whole computer?** | `Installation` | `PER_USER` · `SYSTEM_WIDE` | derived from `RunAs`; also the explicit selector for by-id ops (§2.2) |

They're related but **not 1:1**: `RunAs` has three values, `Installation` has two
(`asUser(name)` and `asSystemDaemon()` are different identities but both are `SYSTEM_WIDE`
installations). That mismatch is exactly why they're separate types.

### 2.1 `RunAs` — the run-as identity (builder field, default `asCurrentUser()`)

| Builder call | Meaning | `Installation` (derived) |
|---|---|---|
| `.asCurrentUser()` *(default)* | run as the user running the JVM | `PER_USER` |
| `.asUser("www-data")` | system-registered, **drops to** that user | `SYSTEM_WIDE` |
| `.asSystemDaemon()` | run as root / `LocalSystem` | `SYSTEM_WIDE` |

Per-platform realization:

| Identity | macOS | systemd | OpenRC | Windows |
|---|---|---|---|---|
| current user | agent in `~/Library/LaunchAgents`, `gui/<uid>` | `systemctl --user`, `~/.config/systemd/user/` | **unsupported → fail-fast** | per-session / current-user context |
| named user | system daemon + `UserName=<name>` | system unit + `User=<name>` | `start-stop-daemon --user <name>` | service account `<name>` (+ secret via `.windows(...)`) |
| system daemon | `/Library/LaunchDaemons`, root | system manager, `User=root` | system runlevel, root | `LocalSystem` |

```java
public final class RunAs {
	public enum Kind { CURRENT_USER, NAMED_USER, SYSTEM_DAEMON }

	public static RunAs currentUser();
	public static RunAs namedUser(final String userName);
	public static RunAs systemDaemon();

	public Kind kind();
	public String userName();            // null unless kind() == NAMED_USER
	public Installation installation();  // CURRENT_USER -> PER_USER; otherwise SYSTEM_WIDE
}
```

The builder exposes `.asCurrentUser()`, `.asUser(String)`, `.asSystemDaemon()`, plus a
`.runAs(RunAs)` setter (mutually exclusive; last one wins). `spec.runAs()` is **never null**.
`asUser` takes a **name** (`String`) — the portable form; numeric uid, if ever needed, becomes
a `.systemd(...)`/`.mac(...)` detail. Windows `LocalService`/`NetworkService` are lesser system
accounts set via `.windows(...).account(...)`, not core.

### 2.2 `Installation` — is the service set up for one user, or the whole computer?

Plain English: every service is installed **either just for one user account, or for the whole
computer** — the same choice a software installer gives you ("Install for all users, or just
me?"). This is the concept launchd calls a *domain* (`gui/<uid>` vs `system`); "domain" is a
poor cross-platform word (overloaded on Windows/AD/networking), so we name it after what it
actually decides. It's a two-valued enum, **identical on all four platforms**:

```java
public enum Installation { PER_USER, SYSTEM_WIDE }
```

| Value | What it means | Admin needed? | Runs when |
|---|---|---|---|
| `PER_USER` | belongs to **one user account**; lives in that user's personal space | **no** | that user is logged in (or via lingering) |
| `SYSTEM_WIDE` | belongs to the **whole machine**; lives in system locations | **yes** (root/admin) | from **boot**, independent of any login |

Per-platform realization:

| Platform | `PER_USER` | `SYSTEM_WIDE` |
|---|---|---|
| macOS | `gui/<uid>` agent | `system` daemon |
| systemd | `--user` manager | system manager |
| OpenRC | **unsupported → fail-fast** | system runlevels |
| Windows | per-user service / session | machine-wide service |

`Installation` is **derived** from the spec's `RunAs` at install time (you don't set it on the
spec). It surfaces as the **explicit selector** on by-id operations (§3) — for when you want to
skip auto-resolution.

---

## 3. The facade — `ServiceManager`

One manager per process, implicitly for **this** platform. Lifecycle calls take just the `id`;
the manager **auto-resolves** the installation for an `id` by looking at the `PER_USER`
installation first, then `SYSTEM_WIDE` (throws `AmbiguousServiceException` if the same id
exists in both — pass an explicit `Installation` to disambiguate).

```java
public interface ServiceManager {

	// --- construction (implicitly THIS platform) ---
	static ServiceManager getServiceManager();
	static ServiceManager getServiceManager(final Platform platform);   // explicit: tests / cross-render

	Platform platform();
	Capabilities capabilities();                       // feature queries (§7)

	// --- definition lifecycle (install is UPSERT: create or update + reconcile) ---
	void install(final ServiceSpec spec);              // throws if it would overwrite an UNMANAGED service
	void install(final ServiceSpec spec, final boolean yesDoThisToAServiceIDidNotCreate);
	void uninstall(final String id);                   // throws if target is UNMANAGED (§5.1)
	void uninstall(final String id, final boolean yesDoThisToAServiceIDidNotCreate);
	boolean isInstalled(final String id);

	// --- discovery & inspection (§5) ---
	List<ServiceStatus> list();                        // all services visible in reachable installations
	List<ServiceStatus> listManaged();                 // only services THIS library created
	boolean isManaged(final String id);
	ServiceSpec read(final String id);                 // parsed spec, or null if not installed
	String readNative(final String id);                // verbatim plist/unit/script text, or null

	// --- the two orthogonal axes (kept separate; see §3.1) ---
	void enable(final String id);                      // start at boot/login (persistence)
	void disable(final String id);
	void start(final String id);                       // run now
	void stop(final String id);
	void restart(final String id);

	// --- convenience (the common "do it all" case) ---
	void installEnableStart(final ServiceSpec spec);

	// --- query ---
	ServiceStatus status(final String id);             // never null; installed()==false if absent

	// --- explicit-installation variants ---
	// Every by-id method above also has a sibling overload taking a trailing Installation
	// to skip auto-resolution. Shown here for two; the same pattern applies to all of them:
	void start(final String id, final Installation installation);
	void uninstall(final String id, final Installation installation,
			final boolean yesDoThisToAServiceIDidNotCreate);
	// …stop/restart/enable/disable/status/read/readNative/isInstalled/isManaged likewise.
}
```

Mutating ops **throw** on failure (`ServiceException` hierarchy, §8) — chosen over result
objects for a clean call site; failures here are exceptional and usually unrecoverable.

### 3.1 `enable`/`start` are separate; `install` is upsert
`enable`/`disable` (boot persistence) and `start`/`stop` (run now) are **separate verbs** —
the systemd-faithful model (resolves tension **T3**). `install` does *not* implicitly enable or
start; `autoStart` in the spec is the value `enable` writes, and `installEnableStart` is the
combined convenience launchd users expect. **`install` is upsert**: an existing *managed*
service is rewritten and reconciled; otherwise it's created. Updating a *running* service does
not restart the live process — call `restart(id)` to apply (§5.3).

### 3.2 The managed-service guard
`uninstall` (and an overwriting `install`) **throw `UnmanagedServiceException`** if the target
service exists but lacks our managed-by marker (§5.1) — you can't accidentally delete or
clobber a service the library didn't create. To proceed anyway, call the overload passing
`yesDoThisToAServiceIDidNotCreate = true`. The parameter name is deliberately blunt and
awkward — that awkwardness *is* the speed bump you pay to touch a service you didn't make.

---

## 4. The domain model — `ServiceSpec`

Immutable value object, `final` throughout, built with a builder, **no `Optional`** (absent =
`null`; collections never null).

```java
public final class ServiceSpec {
	// ---- identity ----
	String id();                       // never null after build (generated if not supplied; §4.1)
	String displayName();              // never null after build (defaults to id())
	String description();              // nullable

	// ---- what to run (uniform) ----
	List<String> command();            // required, non-empty; program + args; program absolute
	Path workingDirectory();           // nullable
	Map<String,String> environment();  // never null; empty if none
	RunAs runAs();                     // never null; default RunAs.currentUser()  (§2.1)

	// ---- I/O (uniform) ----
	Path stdout();                     // nullable (launchd StandardOutPath / systemd file:)
	Path stderr();                     // nullable

	// ---- lifecycle policy (uniform core) ----
	boolean autoStart();               // value written by enable() (start at boot/login)
	RestartPolicy restart();           // never null; default NEVER
	Schedule schedule();               // nullable; non-null => scheduled job (timer/Task/cron)

	// ---- platform escape hatches (Tier 2; all nullable) ----
	MacOptions mac();
	SystemdOptions systemd();
	WindowsOptions windows();
	OpenRcOptions openrc();

	Builder toBuilder();               // derive a builder from this spec (read→modify→install)
	static Builder builder();
}
```

### 4.1 `id` and `displayName` (both optional)

- **`id`** — the unique machine identifier, reverse-DNS in form (e.g. `com.example.backup`),
  normalized to each platform's naming rules internally (kept verbatim where the platform
  allows — systemd unit names and Windows service names both tolerate dots). It is the handle
  every lifecycle call uses.
- **`displayName`** — a free-form human string; stored as side-band info (Windows DisplayName,
  systemd `Description`, a plist/script comment) and never used as a key.
- Both are optional on the builder. Resolution at `build()`:
  - `id` omitted → generate `com.u1.servicepal.<uuid>`.
  - `displayName` omitted → default to `id()` (so a fully-defaulted spec gets a UUID id that is
    also its display name).

### 4.2 `RestartPolicy` (the keep-alive core)

```java
public enum RestartPolicy { NEVER, ON_FAILURE, ALWAYS }
```

| Policy | launchd | systemd | Windows (host) | OpenRC |
|---|---|---|---|---|
| `NEVER` | no KeepAlive | `Restart=no` | host doesn't respawn | `start-stop-daemon` |
| `ON_FAILURE` | `KeepAlive={SuccessfulExit=false}` | `Restart=on-failure` | host respawns on non-zero exit | `supervise-daemon` |
| `ALWAYS` | `KeepAlive=true` | `Restart=always` (+`StartLimitIntervalSec=0`) | host respawns always | `supervise-daemon` |

Rich/conditional keep-alive lives in the option blocks (Tier 2).

### 4.3 `Schedule` (calendar + interval)

A non-null `schedule` routes the job to the scheduling backend (systemd `.timer`, Windows Task
Scheduler, launchd `StartCalendarInterval`). On OpenRC there is no native scheduler →
**fail-fast** (documented future cron fallback, see roadmap).

```java
public sealed interface Schedule permits CalendarSchedule, IntervalSchedule {
	static Schedule everyMinutes(final int n);
	static Schedule every(final Duration period);                 // interval
	static Schedule dailyAt(final int hour, final int minute);    // calendar
	static Schedule weeklyAt(final DayOfWeek day, final int h, final int m);
	static Schedule monthlyAt(final int dayOfMonth, final int h, final int m);
	static Schedule calendar(final CalendarSpec spec);            // full control
}
```

A spec with **both** a `schedule` and `restart(ALWAYS)` is contradictory on every backend →
fail-fast at build/validate.

### 4.4 `ServiceStatus` (small common core, honest about gaps)

```java
public final class ServiceStatus {
	String id();
	boolean installed();
	boolean enabled();          // boot persistence
	boolean managed();          // created by this library (marker present)?
	RunState state();           // RUNNING | STOPPED | STARTING | STOPPING | FAILED | UNKNOWN
	Integer pid();              // nullable
	Integer lastExitCode();     // nullable
	String raw();               // nullable native dump (launchctl print / systemctl show / sc query)
}
```

Weak platforms (launchd text, SysV) populate what they can and leave the rest `null` rather
than fabricating — resolves tension **T6**.

---

## 5. Discovery, inspection, and modification

### 5.1 Discovery — and telling "ours" apart from the rest
Every definition we render carries a **managed-by marker**, so the library can distinguish
services it created from pre-existing ones:

| Platform | Marker |
|---|---|
| macOS | a custom plist key `com.u1.servicepal.Managed` (= the lib version) |
| systemd | `X-ServicePal-Managed=1` in `[Unit]` (systemd ignores unknown `X-` keys) |
| OpenRC | a sentinel comment + description var in the init script |
| Windows | a tag in the sidecar JSON + a marker prefix in the service Description |

- `list()` returns **all** services visible in reachable installations.
- `listManaged()` / `isManaged(id)` filter to the ones we created.
- This backs the §3.2 guard: `uninstall`/overwrite refuse on unmanaged services unless
  `yesDoThisToAServiceIDidNotCreate = true`.

### 5.2 Inspection — load current settings
- `read(id)` parses the live native definition back into a `ServiceSpec` (core fields +
  recognized option-block keys), or returns **`null`** if not installed. Best-effort:
  unmodeled hand-authored keys are not silently presented as if absent —
- `readNative(id)` returns the **verbatim** plist/unit/script text, so nothing is hidden even
  when `read()` can't round-trip it (honest about lossiness — tension **T6**).

### 5.3 Modification — change and save
Read → modify a copy (specs are immutable) → upsert → optionally restart:

```java
final ServiceSpec current = mgr.read("com.example.backup");      // null if absent
if (current != null) {
	final ServiceSpec updated = current.toBuilder()
			.command("/usr/local/bin/backup", "--hourly")
			.schedule(Schedule.hourly())
			.build();
	mgr.install(updated);                 // upsert: rewrite definition + reconcile
	mgr.restart("com.example.backup");    // apply to the running instance
}
```

`toBuilder()` makes read→modify→install natural; `install` being upsert means there's no
separate `update` verb. The backend performs any required `daemon-reload`/re-register itself.

---

## 6. Platform option blocks (Tier 2) — defaults, and the wrong-platform rule

Each block is an immutable builder, attached only when needed. **A block targeting a platform
other than the one you install on throws `WrongPlatformOptionsException` at `install()`** (per
owner decision — silently ignoring it would hide a real mistake). So you build a spec for the
platform you're targeting; to share a base spec, add the platform block conditionally with
`toBuilder()` after checking `mgr.platform()`.

```java
final ServiceSpec spec = ServiceSpec.builder()
		.id("com.example.api")
		.command("/usr/local/bin/api")
		.restart(RestartPolicy.ALWAYS)
		.systemd(SystemdOptions.builder()
				.type(SystemdType.NOTIFY)
				.after("network-online.target").wants("network-online.target")
				.memoryMax("512M").cpuQuota("50%").tasksMax(64)
				.restartSec(Duration.ofSeconds(2))
				.noNewPrivileges(true).protectSystem(ProtectSystem.STRICT)
				.build())
		.build();
```

**What each block carries (summary; full key sets in the per-platform research docs):**

- **`MacOptions`** — `processType`, `lowPriorityIO`, `nice`, `throttleInterval`, conditional
  `keepAliveWhen(...)`, `abandonProcessGroup`, `sessionType`, `watchPaths`, `queueDirectories`.
- **`SystemdOptions`** — `type`, ordering/deps (`after`/`before`/`requires`/`wants`/`bindsTo`),
  `restartSec`, `startLimitIntervalSec`, cgroup limits (`memoryMax`/`cpuQuota`/`tasksMax`),
  sandboxing (`protectSystem`/`privateTmp`/`noNewPrivileges`), `nice`, `oomScoreAdjust`,
  `slice`, timer extras (`persistent`, `randomizedDelay`, `accuracy`).
- **`WindowsOptions`** — `account`, `startType`, `dependsOn`, `recovery`, `serviceSidType`; Task
  extras (`runOnlyIfIdle`, `wakeToRun`, `runIfMissed`, `executionTimeLimit`, `priority`).
- **`OpenRcOptions`** — `supervisor`, deps (`need`/`use`/`after`/`before`/`provide`), `runlevel`,
  `respawnMax`/`respawnPeriod`, `pidfile`.

### 6.1 Default behavior per platform (when no option block is given)

Defaults are a **starting point** — refined as we gain experience; making them configurable is
a roadmap item.

| Aspect | macOS | systemd | OpenRC | Windows |
|---|---|---|---|---|
| service "type"/mode | (launchd job) | **`Type=exec`** (detects bad binary/user) | `supervise-daemon` if `restart != NEVER`, else `start-stop-daemon` | own-process service via the Java host |
| `autoStart=true` | `RunAtLoad=true` | `enable` → `WantedBy=multi-user.target` (system) / `default.target` (user) | `rc-update add … default` | start type `auto` |
| `autoStart=false` | `RunAtLoad=false` | not enabled | not added to a runlevel | start type `manual` |
| stdout/stderr unset | launchd default | **journal** | OpenRC default logging | inherited by host |
| restart throttle | launchd default | `RestartSec=100ms`; `ALWAYS` adds `StartLimitIntervalSec=0` | default respawn period | host backoff |
| scheduled job | `StartCalendarInterval` | `.timer` + `oneshot .service` | **fail-fast** | Task Scheduler (no host) |

---

## 7. Capabilities & fail-fast

```java
public interface Capabilities {
	boolean perUserInstall();        // false on OpenRC
	boolean systemWideInstall();
	boolean namedUser();
	boolean calendarSchedule();      // false on OpenRC
	boolean intervalSchedule();
	boolean keepAlive();             // continuous restart
	boolean conditionalKeepAlive();  // mac/systemd only
	boolean logFileRedirection();
	boolean structuredStatus();      // reliable pid+exit code (true: systemd/windows)
}
```

`install()` validates `spec` against `capabilities()` **before** touching the system and throws
`UnsupportedFeatureException` naming exactly what isn't supported and why. No silent
degradation. Opt-in fallbacks (e.g. calendar→cron on OpenRC) are a future roadmap item.

---

## 8. Errors

```java
ServiceException                       // unchecked base — wraps everything
├── UnsupportedFeatureException        // capability gap (fail-fast, pre-flight)
├── WrongPlatformOptionsException      // an option block for a non-current platform (§6)
├── UnmanagedServiceException          // destructive/overwrite op on a service we didn't create (§3.2)
├── AmbiguousServiceException          // by-id op and the id exists as both PER_USER and SYSTEM_WIDE
├── ServiceNotFoundException           // mutating op on an unknown id
├── PermissionException                // needs root/admin/elevation
├── DefinitionIOException              // writing/reading the plist/unit/script failed
└── NativeCommandException             // launchctl/systemctl/sc/FFM call failed (carries cmd+exit+stderr)
```

---

## 9. Platform detection & the `Platform` enum

```java
public enum Platform { MACOS_LAUNCHD, LINUX_SYSTEMD, LINUX_OPENRC, WINDOWS }
```

`getServiceManager()` detection order:
- **Windows / macOS:** by `os.name`.
- **Linux:** systemd if `/run/systemd/system` exists (then `/proc/1/comm == systemd`); else
  OpenRC if `/sbin/openrc` / `rc-service` present; else **fail-fast** with a clear message
  (covers the init-less container case — "no supported init system found; PID 1 is `<x>`").

systemd and OpenRC are **distinct platforms** with **distinct backends** (your decision — no
shared Linux SPI). The internal `Backend` interface is code-org only, not a public extension
point.

---

## 10. Windows specifics — the bundled Java service host

Resolves tension **T1** while honoring "no compiled binaries":

- The jar contains a runnable class, e.g. `com.u1.servicepal.windows.ServiceHost`.
- `install()` of a **daemon** registers a service whose `binPath` is
  `"<javaw>" -cp "<our.jar>" com.u1.servicepal.windows.ServiceHost --id com.example.api`, and
  writes the spec to a sidecar file (`%ProgramData%\servicepal\<id>.json`).
- On start, the SCM launches that host; the host uses **FFM** to call
  `StartServiceCtrlDispatcher` + `RegisterServiceCtrlHandlerEx` (handler is an **FFM upcall**)
  + `SetServiceStatus`, then `CreateProcess`-supervises the real command, mapping STOP→terminate
  child and implementing the `RestartPolicy`. This *is* a real Windows service, in pure Java.
- **Scheduled** jobs skip the host entirely → Task Scheduler runs the command directly (no
  protocol needed). Routing by job shape resolves tension **T2**.
- `status()` reads real state/PID/exit via `QueryServiceStatusEx` (FFM) — structured, no
  `sc query` text parsing.

All FFM and subprocess access sits behind interfaces (`Scm`, `TaskScheduler`, `CommandRunner`)
so the Windows backend unit-tests on Linux/macOS with stubs.

---

## 11. Internal architecture (not public API)

```
com.u1.servicepal
├── ServiceManager (iface) · Platform · Installation · Capabilities · ServiceException…   ← public
├── model/   ServiceSpec(+Builder) · RunAs · RestartPolicy · Schedule(+CalendarSpec) · ServiceStatus
│   └── options/  MacOptions · SystemdOptions · WindowsOptions · OpenRcOptions                 ← public
└── internal/                                                                                  ← package-private
	├── Backend (iface): install/uninstall/start/stop/enable/disable/status/list/read
	├── exec/   CommandRunner (stubbable subprocess seam)
	├── macos/    LaunchdBackend · PlistRenderer(dd-plist) · Launchctl
	├── systemd/  SystemdBackend · UnitRenderer · Systemctl
	├── openrc/   OpenRcBackend · RcScriptRenderer · RcService
	└── windows/  WindowsBackend(routes svc⇄task) · Scm(FFM) · TaskScheduler · ServiceHost(FFM)
```

- **Renderers** are per-platform (plist/INI/script/Task-XML) — no shared codec (tension **T5**).
  `dd-plist` is confined to `macos/PlistRenderer`.
- The `Backend` SPI is **internal**; not a public plugin point.
- Everything native (subprocess *and* FFM) is behind an interface → full off-platform testing.

---

## 12. Naming & packaging (settled)

- **Root package:** `com.u1.servicepal`.
- **Project rename:** GitHub repo to become *ServicePalForJava* (later; not yet). The current
  repo/dirs keep the `JLaunchd…` name until then.
- **The "domain" concept** is named `Installation { PER_USER, SYSTEM_WIDE }` (§2.2).

---

## 13. Resolved decisions

All step-2 open questions are now settled:

- Run-as identity (`RunAs`) vs. installation (`Installation`) cleanly separated (§2).
- **Naming:** the "domain" concept is `Installation { PER_USER, SYSTEM_WIDE }` — named after the
  plain question it answers ("set up for one user, or the whole computer?"). Replaces the
  earlier `ManagementScope`.
- **Auto-resolution:** by-id ops resolve `PER_USER` first, then `SYSTEM_WIDE`;
  `AmbiguousServiceException` if an id exists as both; explicit-`Installation` overloads to
  disambiguate (§3).
- **Managed-service guard:** destructive/overwrite ops throw `UnmanagedServiceException` unless
  called with `yesDoThisToAServiceIDidNotCreate = true` — the awkward name is the penalty
  (§3.2).
- Optional `id`/`displayName` with `com.u1.servicepal.<uuid>` generation (§4.1); throw on
  wrong-platform option blocks (§6); root package `com.u1.servicepal` (§12); no Tier-2
  promotions.

Design is ready for **step 3** (per-platform native-interop: renderers + command/FFM seams).
