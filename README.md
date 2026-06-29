# ServicePal

A clean and simple Java library for **creating and managing background
services (daemons)** through one uniform API across **macOS, Linux, and Windows**.

Write the service definition once; ServicePal maps it onto each platform's native facilities
and detects the platform at runtime:

| Capability | macOS | systemd | OpenRC | Windows |
|------|:----:|:-------:|:------:|:-------:|
| **Discovery / inspection** (`list`, `read`, `status`) | ✅ | ✅ | ✅ | ✅ |
| **Mutation** (`install`, `start`, `enable`, …) | ✅ | ✅ | ✅ | ✅ |


| Platform | Backed by |
|----------|-----------|
| **macOS** | launchd — `.plist` files driven by `launchctl` |
| **Linux (systemd)** | `.service` units driven by `systemctl` |
| **Linux (OpenRC)** | `/etc/init.d` scripts driven by `rc-service` / `rc-update` |
| **Windows** | the Service Control Manager (via the Java FFM API) and Task Scheduler |

The root package is `com.u1.servicepal`.

## Requirements

- **JDK 25 or newer.** The Windows backend uses the Foreign Function & Memory API (final
  since JDK 22) to drive the SCM and to run a bundled pure-Java service host.
- On **Windows**, applications must grant native access. The runnable jar declares
  `Enable-Native-Access: ALL-UNNAMED` in its manifest, so `java -jar` needs no flag; when
  running ServicePal from a classpath, pass `--enable-native-access=ALL-UNNAMED`.

## Installation

Maven coordinates:

```xml
<dependency>
  <groupId>com.u1.servicepal</groupId>
  <artifactId>servicepal</artifactId>
  <version>0.0.9</version>
</dependency>
```

Each GitHub Release also attaches the runnable fat jar (`servicepal-<version>.jar`) and a
sources jar.

## Quick start

```java
import com.u1.servicepal.ServiceManager;
import com.u1.servicepal.model.RestartPolicy;
import com.u1.servicepal.model.ServiceSpec;
import com.u1.servicepal.model.ServiceStatus;
import java.nio.file.Path;

final ServiceManager services = ServiceManager.getServiceManager();   // backend for this OS

final ServiceSpec spec = ServiceSpec.builder()
        .id("com.example.backup")
        .displayName("Example Backup")
        .command("/usr/local/bin/backup", "--daemon")
        .asCurrentUser()                       // who it runs as
        .restart(RestartPolicy.ON_FAILURE)
        .autoStart(true)                       // start at boot/login
        .stdout(Path.of("/var/log/backup.out"))
        .build();

services.installEnableStart(spec);             // create + enable at boot + start now

final ServiceStatus status = services.status("com.example.backup");
System.out.println(status.state() + " pid=" + status.pid());

services.uninstall("com.example.backup");
```

`getServiceManager()` returns the backend for the current OS; lifecycle calls take just the
service `id`.

## Core concepts

**Who runs it vs. where it's installed — two independent ideas:**

- **`RunAs`** is *who* the service runs as: `.asCurrentUser()` (the default),
  `.asUser("name")`, or `.asSystemDaemon()`.
- **`Installation`** is whether it's set up for one user (`PER_USER`) or the whole machine
  (`SYSTEM_WIDE`); it is derived from `RunAs`. By-id calls auto-resolve the installation
  (PER_USER first, then SYSTEM_WIDE) and throw `AmbiguousServiceException` if an id exists in
  both — every call also has an explicit-`Installation` overload.

**Lifecycle:**

- `install(spec)` is an **upsert** — it creates or updates.
- **`enable`/`disable`** (start at boot/login) are separate from **`start`/`stop`** (run
  now); `restart` bounces a running service.
- `installEnableStart(spec)` is the common-case convenience.

**The spec** — `ServiceSpec.builder()`, immutable:

- `id` and `displayName` are optional (id defaults to `com.u1.servicepal.<uuid>`,
  displayName defaults to the id).
- Core fields: `command`, `env`, `workingDirectory`, `stdout`/`stderr`, `autoStart`, and
  `restart` (`NEVER` / `ON_FAILURE` / `ALWAYS`).
- A non-null `schedule` turns the spec into a **scheduled job** instead of a long-running
  daemon — `Schedule.dailyAt(...)`, `.weeklyAt(...)`, `.monthlyAt(...)`, or
  `.every(Duration)`.
- `toBuilder()` supports read → modify → re-install.

```java
final ServiceSpec nightly = ServiceSpec.builder()
        .id("com.example.report")
        .command("/usr/local/bin/report", "--nightly")
        .asSystemDaemon()
        .schedule(Schedule.dailyAt(3, 30))
        .build();
services.install(nightly);
```

**Platform-specific power** lives in optional, typed blocks — `.mac(...)`, `.systemd(...)`,
`.windows(...)`, `.openrc(...)` — each with sensible defaults. A block for a platform you are
not running on throws `WrongPlatformOptionsException` at `install()`; requesting a capability
the platform lacks throws `UnsupportedFeatureException`. Query support up front with
`services.capabilities()`.

**Discovery & inspection:**

- `list()` / `listManaged()` — every visible service / only those ServicePal created (via an
  embedded marker; also `isManaged(id)`).
- `read(id)` → a `ServiceSpec` (`null` if absent); `readNative(id)` → the verbatim platform
  definition text.
- `status(id)` → a `ServiceStatus` (installed, managed, run state, pid, …); `discover()` →
  everything reachable plus any unreadable definitions.

**Safety:** destructive operations on a service ServicePal did not create are refused unless
you opt in with the explicit `install(spec, true)` / `uninstall(id, true)` overload.

## Platform support

| Capability | macOS | systemd | OpenRC | Windows |
|------------|:-----:|:-------:|:------:|:-------:|
| Per-user services | ✅ | ✅ | — | — |
| System-wide services | ✅ | ✅ | ✅ | ✅ |
| Run as a named user | ✅ | ✅ | ✅ | ✅ |
| Scheduled jobs (calendar / interval) | ✅ | ✅ | ✅¹ | ✅ |
| Restart / keep-alive | ✅ | ✅ | ✅ | ✅ |
| Log-file redirection | ✅ | ✅ | ✅ | ✅ |

Query the exact feature set of the running platform with `services.capabilities()`.

On Windows, a long-running daemon becomes a real Windows service whose executable is a
bundled pure-Java FFM **service host** — it speaks the SCM control protocol and supervises
your command (a plain `java -jar` run as a service cannot, and fails with error 1053). A
*scheduled* job is routed to **Task Scheduler** instead, where it runs your command directly.

Scheduling is mapped to each platform's native facility: launchd `StartCalendarInterval` /
`StartInterval`, a systemd `.timer` + oneshot `.service` pair (`OnCalendar` / `OnUnitActiveSec`),
and Windows Task Scheduler. ¹ **OpenRC** has no native scheduler, so it uses a **cron fallback**:
calendar schedules map directly, but an interval must divide a minute or an hour (others fail fast).

**Current limitations:**

- **OpenRC** is system-wide only; its supervised restart respawns on any exit, so `ON_FAILURE`
  and `ALWAYS` behave identically.
- **Windows** is system-wide only. Discovery is machine-wide (it enumerates every Win32 service
  via `EnumServicesStatusExW`), but foreign services show their service key name — ServicePal-created
  services and tasks carry the friendly display name.

## Command-line tools

The runnable jar ships two CLIs:

```sh
java -jar servicepal.jar              # discover and print all services on this machine
java -jar servicepal.jar --managed    # only services ServicePal created
```

`com.u1.servicepal.cli.SelfTestCli` runs a real install → start → verify → uninstall
lifecycle against the live OS; continuous integration uses it to validate mutation on each
platform.

## Desktop GUI

The same jar also ships a small cross-platform desktop GUI for the **common case** — set up jobs to
run in the background, either kept running or on a schedule. It is **opt-in** via an explicit first
argument (the no-argument default stays the discovery CLI):

```sh
java -jar servicepal.jar -ui          # launch the GUI (also: --ui, gui)
java -jar servicepal.jar -ui --demo   # explore with in-memory demo data (no OS changes)
```

It lists **every service the platform can discover**, grouped into sections — the jobs you
**created with ServicePal**, ones it has **adopted** (installed over but didn't originally create),
and **other background jobs** found on the machine. Every job is controllable (start/stop/restart,
add/edit, remove); editing or removing a service ServicePal didn't create is allowed but asks first
(editing rewrites it in ServicePal's format and adopts it). For your own jobs you set name + command,
optional working folder, and pick a mode: **keep it running** (*start automatically* + what to do
*if it stops*) or **on a schedule** (a simple every-N-minutes / daily / weekly picker; a scheduled
job shows as “Scheduled” with its next/last run). It deliberately hides the rest of the
platform-specific surface (run-as identity, the `.mac()/.systemd()/...` option blocks): the UI is
identical on every platform. Where the platform supports per-user services (macOS, systemd) it
installs without admin and starts at login; where it does not (Windows, OpenRC) it installs a
system-wide service (run the app elevated).

Built with Swing. On macOS it uses [FlatLaf](https://www.formdev.com/flatlaf/), following the OS
light/dark setting; on Windows and Linux it uses the platform's native look-and-feel. CI captures
screenshots of the GUI on macOS, Linux, and Windows for visual review — see
`.github/workflows/gui-screenshots.yml` and `docs/design/gui-design.md`.

## Build & test

```sh
mvn verify
```

Builds with **JDK 25** and runs the full unit suite. Continuous integration
(`.github/workflows/ci.yml`) runs that suite **and** a real install → start → uninstall
lifecycle against the actual service manager on macOS, Linux (systemd and OpenRC), and
Windows, on every push and pull request.

## Releases

Releases are automated (`.github/workflows/`):

- **Tag Release** (`release.yml`) — on a pushed `v*` tag (or manual dispatch), builds and
  publishes a GitHub Release with the runnable fat jar and a sources jar; the version comes
  from the tag.
- **Bump version and release** (`version-bump.yml`) — when a PR merges into `main`, computes
  the next version from the latest `v*` tag (patch by default; `release:minor` /
  `release:major` PR labels override; `release:skip` or `[skip release]` opts out), pushes the
  tag, and triggers Tag Release.

## Layout

- `src/main/java/com/u1/servicepal/` — the public surface (`ServiceManager`, `model/`,
  `model/options/`) and the per-platform backends under `internal/<platform>/`.
- `src/test/java/` — platform-independent unit tests (the native seams are stubbed, so the
  whole suite runs on any OS).
- `.github/workflows/` — CI and release automation.
