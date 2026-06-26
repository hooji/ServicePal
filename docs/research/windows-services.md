# Windows Background-Process Management — Research

**Purpose:** Cross-platform research for `JLaunchdManagerForMacs`. This document maps the
Windows world (Service Control Manager + Task Scheduler) onto the macOS `launchd` model the
library already documents, and recommends a concrete native approach for the Java
implementation.

**Status:** Research only. Current as of **June 2026**. All claims verified against the
sources listed at the end; URLs are cited inline.

**TL;DR for the impatient:** Windows splits launchd's job into two subsystems —
**Services** (the daemon equivalent, governed by the Service Control Manager) and
**Task Scheduler** (the calendar/trigger equivalent). The single biggest difference from
launchd is that a Windows *service* binary **cannot be an arbitrary executable**: it must
speak the SCM control protocol. To run "any command" as a service you need a wrapper/host
(WinSW, NSSM, or your own shim). Scheduled tasks have no such constraint — any exe works.

---

## A. Windows Services (the daemon equivalent)

### A.1 The Service Control Manager (SCM)

The **Service Control Manager** (`services.exe`) is the system component that maintains the
database of installed services, starts them at the configured time, and brokers all
control requests (start/stop/pause/continue/custom). It is the central concept — the
rough analogue of `launchd` itself.

The SCM database is persisted in the registry under:

```
HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\<ServiceName>
```

Each service (and each kernel driver — drivers live in the same tree) is a subkey named by
its **service name** (the short, internal key name, *not* the display name). The
`CreateService` API literally creates this key and writes values into it; `ChangeServiceConfig`
and `ChangeServiceConfig2` update them.
([MS Learn: Services registry tree](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/hklm-system-currentcontrolset-services-registry-tree),
[MS Learn: CreateServiceW](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-createservicew))

Standard value entries written under `Services\<name>` (per the `CreateServiceW` "Remarks"):

| Value             | Meaning                                                            |
|-------------------|-------------------------------------------------------------------|
| `Type`            | Service type bitmask (`dwServiceType`)                             |
| `Start`           | Start type (`dwStartType`): 0=boot,1=system,2=auto,3=demand,4=disabled |
| `ErrorControl`    | Severity on start failure (`dwErrorControl`): 0–3                  |
| `ImagePath`       | Fully-qualified path to the service binary (the "binPath", incl. args) |
| `DisplayName`     | Human-readable name shown in UI                                    |
| `Description`     | Long description (set via `ChangeServiceConfig2`)                  |
| `ObjectName`      | Logon account (`lpServiceStartName`), e.g. `LocalSystem`, `NT AUTHORITY\LocalService` |
| `DependOnService` | Service dependencies (REG_MULTI_SZ)                                |
| `DependOnGroup`   | Load-order group dependencies                                     |
| `Group`           | Load-ordering group membership                                    |
| `FailureActions`  | Binary blob describing recovery actions (set via `ChangeServiceConfig2`) |
| `Tag`             | Load-order tag (driver services only)                             |

Sub-keys: a `Parameters` subkey commonly holds service-specific config; a `Performance`
subkey can point at a perf-counter DLL.
([MS Learn: Services registry tree](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/hklm-system-currentcontrolset-services-registry-tree),
[CreateServiceW Remarks](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-createservicew))

> **Practical note:** Although the data lives in the registry, you should **not** write these
> keys directly. The SCM caches state, computes the `FailureActions` blob, validates
> accounts, and applies security descriptors. Always go through `sc.exe` / PowerShell / the
> Win32 API so the SCM stays consistent.

### A.2 Management interfaces (modern vs legacy)

#### `sc.exe` — the canonical command-line front-end to the SCM

`sc.exe` exposes (almost) the full SCM API surface as subcommands. Each subcommand maps to
an SCM function. ([ss64: sc](https://ss64.com/nt/sc.html),
[MS Learn: Configuring a service using sc](https://github.com/MicrosoftDocs/win32/blob/docs/desktop-src/Services/configuring-a-service-using-sc.md))

| `sc` subcommand | Does                                            | Underlying API                |
|-----------------|-------------------------------------------------|-------------------------------|
| `create`        | Register a new service                          | `CreateServiceW`              |
| `config`        | Change persistent config                        | `ChangeServiceConfigW`        |
| `delete`        | Mark service for deletion                       | `DeleteService`               |
| `start`         | Start it                                         | `StartServiceW`               |
| `stop`          | Send STOP control                                | `ControlService`              |
| `query`         | Query current status                            | `QueryServiceStatusEx` / enum |
| `qc`            | Query persistent config                         | `QueryServiceConfigW`         |
| `failure`       | Set recovery/failure actions                    | `ChangeServiceConfig2W`       |
| `qfailure`      | Query recovery actions                          | `QueryServiceConfig2W`        |
| `description`   | Set the description                             | `ChangeServiceConfig2W`       |
| `sdset`/`sdshow`| Set/show security descriptor                    | `SetServiceObjectSecurity`    |

**`sc create` quirk (important):** the syntax is `sc create <name> binPath= "<path>" ...`.
There **must be a space after the `=`** and **no space before it** (`binPath= "x"`, not
`binPath ="x"` or `binPath="x"`). This trips up almost everyone and is easy to get wrong
when shelling out. ([ss64: sc](https://ss64.com/nt/sc.html),
[Hexacorn: sc quirky cmd line args](https://www.hexacorn.com/blog/2020/08/20/sc-and-its-quirky-cmd-line-args/))

Key `sc create` options: `binPath=`, `type=` (own/share/kernel/...), `start=`
(boot/system/auto/demand/disabled, plus `delayed-auto`), `obj=` (account),
`password=`, `displayname=`, `depend=`.

#### PowerShell cmdlets (`Microsoft.PowerShell.Management`)

| Cmdlet                       | Purpose                  | Availability                                   |
|------------------------------|--------------------------|------------------------------------------------|
| `Get-Service`                | Query                    | Windows PowerShell 1.0+                         |
| `Start/Stop/Restart-Service` | Lifecycle control        | Windows PowerShell 1.0+                         |
| `Suspend/Resume-Service`     | Pause/continue           | Windows PowerShell 1.0+                         |
| `New-Service`                | Create                   | All; `-ComputerName` dropped in PS 6.0         |
| `Set-Service`                | Reconfigure              | All; `-ComputerName` dropped in PS 6.0; gained richer params (`-StartupType DelayedAutoStart`, `-Credential`, `-SecurityDescriptorSddl`) in PS 6+/7 |
| `Remove-Service`             | Delete                   | **Introduced in PowerShell 6.0** — not in Windows PowerShell 5.1 |

The big version gotcha: **`Remove-Service` does not exist in Windows PowerShell 5.1** (the
in-box version on most Windows installs). On 5.1 you must delete via `sc.exe delete` or the
Win32 `DeleteService` API. Also, from PowerShell 6.0 onward the service cmdlets lost the
`-ComputerName` remoting parameter (use `Invoke-Command` instead).
([MS Learn: Remove-Service](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/remove-service),
[MS Learn: New-Service](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/new-service),
[MS Learn: Set-Service](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/set-service))

Note: PowerShell does **not** expose a first-class cmdlet for *failure/recovery actions* —
you still drop to `sc.exe failure` (or the Win32 API) for those.

#### `net start` / `net stop` (legacy)

`net start <name>` / `net stop <name>` are the oldest control commands. They only
start/stop (no create/config/query of config), and `net stop` will refuse if dependent
services are running. Keep for compat awareness only; prefer `sc`/PowerShell/API.

### A.3 The native Win32 Service API (`advapi32.dll`)

All of the above ultimately call exports in **`advapi32.dll`** (forwarded to `sechost.dll`
on modern Windows). This is the layer the library would target via Java's **Foreign
Function & Memory (FFM) API**. The control/management functions:

| Function                  | Role                                                          |
|---------------------------|---------------------------------------------------------------|
| `OpenSCManagerW`          | Get a handle to the SCM database (needs `SC_MANAGER_*` rights; `SC_MANAGER_CREATE_SERVICE` to create) |
| `CreateServiceW`          | Create a service (writes the registry key)                    |
| `OpenServiceW`            | Open an existing service by name, with desired access         |
| `StartServiceW`           | Start a service, optionally passing argv                      |
| `ControlService` / `ControlServiceEx` | Send STOP/PAUSE/CONTINUE/INTERROGATE/custom controls |
| `DeleteService`           | Mark a service for deletion (removed when last handle closes / it stops) |
| `ChangeServiceConfigW`    | Update core config (type, start, binpath, account, deps, ...) |
| `ChangeServiceConfig2W`   | Update extended config: description, **failure actions**, delayed-auto-start, preshutdown timeout, trigger info, etc. |
| `QueryServiceStatusEx`    | Get live status incl. **PID** (via `SERVICE_STATUS_PROCESS`)  |
| `QueryServiceConfigW`     | Read core persistent config (`QUERY_SERVICE_CONFIG`)          |
| `QueryServiceConfig2W`    | Read extended config (description, failure actions, ...)      |
| `EnumServicesStatusExW`   | Enumerate services + status (the "list" operation)            |
| `CloseServiceHandle`      | Release SC_HANDLE handles                                     |

`CreateServiceW` signature (confirmed from MS Learn):
`CreateServiceW(hSCManager, lpServiceName, lpDisplayName, dwDesiredAccess, dwServiceType,
dwStartType, dwErrorControl, lpBinaryPathName, lpLoadOrderGroup, lpdwTagId, lpDependencies,
lpServiceStartName, lpPassword)`.
([MS Learn: CreateServiceW](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-createservicew),
[MS Learn: OpenServiceA](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-openservicea))

**Key structs** (all in `winsvc.h`):

- **`SERVICE_STATUS`** — `dwServiceType, dwCurrentState, dwControlsAccepted,
  dwWin32ExitCode, dwServiceSpecificExitCode, dwCheckPoint, dwWaitHint`.
  ([MS Learn: SERVICE_STATUS](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_status))
- **`SERVICE_STATUS_PROCESS`** — same fields **plus** `dwProcessId` and `dwServiceFlags`.
  This is what `QueryServiceStatusEx` fills in; it's the only way to get the service's PID.
  ([MS Learn: SERVICE_STATUS_PROCESS](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_status_process))
- **`QUERY_SERVICE_CONFIG`** — `dwServiceType, dwStartType, dwErrorControl,
  lpBinaryPathName, lpLoadOrderGroup, dwTagId, lpDependencies, lpServiceStartName,
  lpDisplayName` (returned by `QueryServiceConfigW`).
- **`SERVICE_FAILURE_ACTIONS`** / **`SC_ACTION`** — recovery config (see A.5).
- **`SERVICE_DELAYED_AUTO_START_INFO`** — the delayed-auto-start flag, set via
  `ChangeServiceConfig2`.
  ([MS Learn: SERVICE_DELAYED_AUTO_START_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_delayed_auto_start_info))

These structs contain Unicode string pointers and variable-length blobs, which makes the
FFM marshalling non-trivial (see "Quirks" and the recommendation). For reference, JNA's
`com.sun.jna.platform.win32.Advapi32` / `Winsvc` already model all of these, which is a
useful cross-check even if we use FFM.
([JNA Advapi32](https://java-native-access.github.io/jna/4.2.1/com/sun/jna/platform/win32/Advapi32.html))

### A.4 CRITICAL QUIRK — the service control protocol (a normal exe can't just "be a service")

This is **the single most important Windows-specific fact** for this library.

On macOS, a launchd job's `ProgramArguments` can be *literally any executable or script*.
launchd execs it, supervises the process, and you're done. **Windows is not like this.**

When the SCM starts a `SERVICE_WIN32_*` service, it does **not** just launch the binary and
walk away. It expects the process to **register itself with the SCM and report status**,
via this protocol (all in `advapi32`):

1. **`StartServiceCtrlDispatcher`** — the service's `main()` must call this almost
   immediately. It connects the process's main thread to the SCM and blocks until all
   services in the process stop. If the binary doesn't call this within the SCM's timeout
   (~30s), the SCM kills it and reports **error 1053: "The service did not respond to the
   start or control request in a timely fashion."**
   ([MS Learn: StartServiceCtrlDispatcherW](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-startservicectrldispatcherw))
2. **`RegisterServiceCtrlHandler[Ex]`** — inside the service's entry point, register a
   handler callback to receive STOP/PAUSE/CONTINUE/SHUTDOWN/INTERROGATE controls.
3. **`SetServiceStatus`** — the service must repeatedly report state
   (`SERVICE_START_PENDING` → `SERVICE_RUNNING` → `SERVICE_STOP_PENDING` →
   `SERVICE_STOPPED`), including `dwCheckPoint`/`dwWaitHint` during slow transitions, or the
   SCM considers it hung.

**Implication:** You **cannot** point `binPath=` at `python myscript.py`,
`node server.js`, `java -jar app.jar`, or `backup.exe --daily` and expect a working service.
Those programs don't speak the protocol; the SCM will start them, get no `SetServiceStatus`
report, time out, and mark the service failed (error 1053). This is the exact opposite of
launchd's "run anything" model and is the central design problem for any cross-platform
"run this command as a daemon" library.

#### How wrappers solve it (NSSM / WinSW / srvany / your own shim)

The standard solution is a **service-host wrapper**: a small purpose-built executable that
*is* a proper service (it speaks the SCM protocol) and whose job is to launch and supervise
your real command as a child process.

- **WinSW** ("Windows Service Wrapper") — a single `.exe` that you rename to your service
  name and pair with an XML config file (`myapp.exe` + `myapp.xml`) placed side-by-side.
  The XML declares the executable, arguments, working dir, log redirection, env vars,
  restart-on-failure behavior, etc. WinSW registers itself with the SCM, then runs your
  process as a child and relays lifecycle. Permissive (MIT-style) license, used by Jenkins.
  **Status (2026):** stable line is **2.11.0**; **v3.x** is in active development as
  pre-releases (alpha.7 as of late Dec), targeting .NET Framework 4.6.1+ with native
  .NET 7-based x64/x86 builds for machines without the Framework. Actively maintained.
  ([GitHub: winsw](https://github.com/winsw/winsw),
  [WinSW releases](https://github.com/winsw/winsw/releases),
  [Infonautics: run any program as a service with WinSW](https://www.infonautics.ch/blog/run-any-program-as-a-windows-background-service-with-winsw/))
- **NSSM** ("Non-Sucking Service Manager") — same idea, configured via a small GUI or CLI;
  knobs for I/O redirection, restart throttling, log rotation, env, priority/affinity.
  Robust and widely used, but the **original is effectively abandoned (last release 2017)**;
  community forks exist. ([NSSM fork](https://github.com/AtiX/nssm),
  [DEV: Servy vs NSSM vs WinSW](https://dev.to/aelassas/servy-vs-nssm-vs-winsw-2k46))
- **srvany** — the ancient Resource-Kit shim; deprecated, doesn't forward STOP cleanly
  (it kills the child), no recovery features. Avoid.
- **Your own shim** — a tiny native (or AOT-compiled) host that calls
  `StartServiceCtrlDispatcher`/`RegisterServiceCtrlHandlerEx`/`SetServiceStatus` and
  `CreateProcess`es the target command, mapping a STOP control to terminating the child.
  This is exactly what WinSW/NSSM are; rolling your own means owning child-process
  supervision, graceful shutdown, log redirection, and crash-restart logic.

For a library that wants to expose launchd-style "run any command as a service," **bundling
or generating a wrapper is essentially mandatory.** There is no native Windows way around
the protocol requirement for `SERVICE_WIN32` services.

### A.5 Configuration model

**Start types** (the `Start` registry value / `dwStartType` / `sc start=`):

| Name                  | Value | `sc` keyword   | Meaning                                                |
|-----------------------|-------|----------------|--------------------------------------------------------|
| `SERVICE_BOOT_START`  | 0     | `boot`         | Driver loaded by boot loader (drivers only)            |
| `SERVICE_SYSTEM_START`| 1     | `system`       | Driver loaded at kernel init (drivers only)            |
| `SERVICE_AUTO_START`  | 2     | `auto`         | Started by SCM at system startup                       |
| `SERVICE_DEMAND_START`| 3     | `demand`       | Manual / on-demand (started via API)                   |
| `SERVICE_DISABLED`    | 4     | `disabled`     | Cannot be started                                      |

**Auto-delayed** isn't a distinct `Start` value: it's `SERVICE_AUTO_START` **plus** the
`DelayedAutostart` flag (`SERVICE_DELAYED_AUTO_START_INFO` via `ChangeServiceConfig2`, or
`sc config ... start= delayed-auto`). The SCM starts delayed-auto services after normal
auto-start services finish, to speed boot. There are also **trigger-start** services
(start on a hardware/ETW/IP/group-policy event) configured via `ChangeServiceConfig2` /
`sc triggerinfo`.
([CoreTech: startup types](https://www.coretechnologies.com/blog/windows-services/startup-types-explained/),
[MS Learn: SERVICE_DELAYED_AUTO_START_INFO](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_delayed_auto_start_info))

**Service types** (`Type` value / `dwServiceType`):

| Name                          | Value      | Meaning                                  |
|-------------------------------|-----------|------------------------------------------|
| `SERVICE_KERNEL_DRIVER`       | 0x00000001| Kernel driver                            |
| `SERVICE_FILE_SYSTEM_DRIVER`  | 0x00000002| Filesystem driver                        |
| `SERVICE_WIN32_OWN_PROCESS`   | 0x00000010| Service in its own process (the usual)   |
| `SERVICE_WIN32_SHARE_PROCESS` | 0x00000020| Service sharing a host process (svchost) |
| `SERVICE_INTERACTIVE_PROCESS` | 0x00000100| May interact with the desktop — **LocalSystem only; deprecated/blocked by Session 0 isolation** |
| `SERVICE_USER_OWN_PROCESS`    | 0x00000050| Per-user service template (see A.7)       |

([MS Learn: CreateServiceW dwServiceType](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-createservicew))

**Service accounts** (`ObjectName` / `lpServiceStartName`):

| Account                         | Notes                                                            |
|---------------------------------|------------------------------------------------------------------|
| `LocalSystem`                   | Most-privileged; acts as the computer on the network. Default when `lpServiceStartName` is NULL. ([MS Learn: LocalSystem](https://learn.microsoft.com/en-us/windows/win32/services/localsystem-account)) |
| `NT AUTHORITY\LocalService`     | Minimal local privileges; anonymous on the network.              |
| `NT AUTHORITY\NetworkService`   | Minimal local privileges; presents the **computer account** on the network. |
| Virtual account `NT SERVICE\<name>` | Auto-managed per-service identity (Win7/2008R2+); network identity is the computer account; **no password** (`lpPassword` must be NULL). ([CreateServiceW remarks](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-createservicew)) |
| (Group) Managed Service Account `DOMAIN\name$` | AD-managed password rotation; `lpPassword` NULL.    |
| Specific user `DOMAIN\user` / `.\user` | Requires the account's password in `lpPassword`, and the account needs the "Log on as a service" right. |

**Dependencies:** `DependOnService` / `DependOnGroup` (`lpDependencies`, double-null
terminated, group names prefixed with `SC_GROUP_IDENTIFIER` `+`). The SCM starts
dependencies first and refuses to stop a service with running dependents.

**Recovery / failure actions — the closest analog to launchd `KeepAlive`:**
Configured via `ChangeServiceConfig2(SERVICE_CONFIG_FAILURE_ACTIONS, ...)` or
`sc failure`. You specify up to **three ordered actions** (for the 1st, 2nd, 3rd+ failures)
plus a **reset window** (seconds of uptime after which the failure count resets). Each
action is one of:

- `SC_ACTION_RESTART` — restart the service after a delay
- `SC_ACTION_RUN_COMMAND` — run a command/program (e.g. a notification script)
- `SC_ACTION_REBOOT` — reboot the machine after a delay (with optional broadcast message)
- `SC_ACTION_NONE` — do nothing

Example: `sc failure MySvc reset= 86400 actions= restart/60000/restart/60000/reboot/30000`
(restart after 60s twice, then reboot after 30s). A separate flag,
`SERVICE_CONFIG_FAILURE_ACTIONS_FLAG`, controls whether recovery also triggers on
**non-crash, clean-but-nonzero exits** (off by default — by default recovery only fires
when the *process* crashes, not when the service stops itself with an error code).
([MS Learn: sc failure](https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-server-2012-r2-and-2012/cc742019(v=ws.11)),
[Octopus: set recovery actions](https://octopus.com/integrations/windows/windows-service-set-recovery-on-failure-actions))

> **launchd-vs-Windows nuance:** launchd `KeepAlive` keeps a process alive *continuously and
> unconditionally* (or on rich conditions). Windows recovery actions are **finite and
> failure-triggered** (max 3 distinct steps, then they repeat the last action), and by
> default ignore graceful exits. This is a real semantic gap — a launchd `KeepAlive=true`
> "always running" job maps only approximately onto `actions= restart/0/restart/0/restart/0`
> + the failure-on-non-crash flag, and even then the throttle and reset-window behavior
> differ. Wrappers like WinSW/NSSM implement their *own* restart loops precisely because the
> SCM's recovery model is coarse.

### A.6 Status querying

`QueryServiceStatusEx` → `SERVICE_STATUS_PROCESS` gives you:

- **`dwCurrentState`**: `SERVICE_STOPPED` / `START_PENDING` / `RUNNING` / `STOP_PENDING` /
  `PAUSE_PENDING` / `PAUSED` / `CONTINUE_PENDING`.
- **`dwControlsAccepted`**: which controls the service honors (STOP, PAUSE_CONTINUE,
  SHUTDOWN, PRESHUTDOWN, PARAMCHANGE, ...).
- **`dwWin32ExitCode`** and **`dwServiceSpecificExitCode`**: exit/error reporting. If the
  service sets `dwWin32ExitCode = ERROR_SERVICE_SPECIFIC_ERROR`, the real code is in
  `dwServiceSpecificExitCode`.
- **`dwCheckPoint`** / **`dwWaitHint`**: progress indicators during pending transitions.
- **`dwProcessId`**: the **PID** (0 if not running). The only API source of the PID.
- **`dwServiceFlags`**: e.g. runs in a system process.

([MS Learn: SERVICE_STATUS_PROCESS](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_status_process),
[MS Learn: SERVICE_STATUS](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_status))

Persistent config (start type, binpath, account, deps) comes from `QueryServiceConfig`;
description + failure actions from `QueryServiceConfig2`. `sc query` / `sc qc` / `sc qfailure`
wrap these for the CLI.

> Unlike parsing `launchctl print` (free-form text), Windows status is **structured** —
> either fixed-layout C structs (FFM) or `sc query`'s parseable key/value text, or
> PowerShell objects (`Get-Service` → `.Status`, `.StartType`; PID via CIM
> `Win32_Service.ProcessId`). This is a genuine advantage over the macOS side.

### A.7 Privilege model

- **Creating, deleting, or reconfiguring a service requires Administrator / elevation.**
  `OpenSCManager` with create rights and `CreateService`/`DeleteService`/`ChangeServiceConfig`
  all demand it. Starting/stopping/querying can be granted more narrowly via the service's
  security descriptor, but in practice management = elevated. A non-elevated process gets
  `ERROR_ACCESS_DENIED` (5). There is no per-user `~/Library/LaunchAgents` equivalent that an
  unprivileged user can write to for *system* services.
- **Per-user services** (Windows 10 1607+): the closest thing to launchd user agents. A
  service template marked `SERVICE_USER_*` (type `0x40`/`0x50`) lives in the normal
  `Services` registry tree, but the SCM spins up a **per-logon-session instance** named
  `<TemplateName>_<LUID>` (the locally-unique session id), running in the user's context,
  started at logon and stopped at logoff. The `UserServiceFlags` value (default `3`) controls
  template behavior; setting it to `0` suppresses instance creation. Installing a per-user
  *template* still requires admin, but the instances run unprivileged in the user session.
  ([MS Learn: Per-user services](https://learn.microsoft.com/en-us/windows/application-management/per-user-services-in-windows),
  [HelgeKlein: per-user services](https://helgeklein.com/blog/per-user-services-in-windows-info-and-configuration/))

---

## B. Task Scheduler (the calendar-job equivalent)

### B.1 When you'd use Task Scheduler instead of a service

Use a **service** for a continuously-running background process that should be supervised
and (re)started by the OS. Use **Task Scheduler** for *event/time-driven* work — run X **when**
something happens. Triggers include:

- **Time / calendar**: one-time, daily, weekly, monthly, monthly-day-of-week — the direct
  analog of launchd `StartCalendarInterval`.
- **At logon** (any/specific user) — analog of a launchd user agent's `RunAtLoad`.
- **At system startup / boot** — analog of `RunAtLoad` for daemons.
- **On idle**, **on an event-log event**, **on registration**, **on workstation
  lock/unlock**, **on connection to a user session**.

Tasks also support repetition intervals, random delays, "run if missed," execution time
limits, run-as accounts, and "wake the computer to run" — a richer trigger model than
launchd's `StartCalendarInterval`/`StartInterval`.
([Wikipedia: Windows Task Scheduler](https://en.wikipedia.org/wiki/Windows_Task_Scheduler),
[MS Learn: Task Scheduler schema](https://learn.microsoft.com/en-us/windows/win32/taskschd/task-scheduler-schema))

### B.2 Where tasks live & the XML schema

- **On disk:** each task is an **XML file** under `C:\Windows\System32\Tasks\` (mirroring the
  folder hierarchy you see in the Task Scheduler UI; subfolders like `\Microsoft\Windows\...`).
- **Registry:** task metadata, the security descriptor, and a hash live under
  `HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion\Schedule\TaskCache\` (`Tasks`, `Tree`,
  `Plain`/`Logon`/`Boot` index subkeys). The XML file and the registry cache must agree;
  hand-editing the XML without updating the cache breaks the task.
- **Schema:** XML in namespace `http://schemas.microsoft.com/windows/2004/02/mit/task`,
  with top-level `<RegistrationInfo>`, `<Triggers>`, `<Principals>` (the run-as identity +
  run level), `<Settings>`, and `<Actions>` (one or more `<Exec>` with `<Command>` and
  `<Arguments>`).
  ([MS Learn: Task Scheduler schema](https://learn.microsoft.com/en-us/windows/win32/taskschd/task-scheduler-schema),
  [MS Learn: Daily Trigger XML example](https://learn.microsoft.com/en-us/windows/win32/taskschd/daily-trigger-example--xml-),
  [windows-forensic-artifacts: task-scheduler-files](https://github.com/Psmths/windows-forensic-artifacts/blob/main/persistence/task-scheduler-files.md))

### B.3 Management

- **`schtasks.exe`** — CLI. `schtasks /create /xml <file> /tn <name>` registers a task from a
  full XML definition (the cleanest programmatic path); also `/create /sc DAILY /st 03:00 /tr
  <cmd> /tn <name>` for simple cases, plus `/query`, `/run`, `/end`, `/change`, `/delete`.
  ([MS Learn: Task Scheduler schema / schtasks](https://learn.microsoft.com/en-us/windows/win32/taskschd/task-scheduler-schema))
- **PowerShell `ScheduledTasks` module** — `New-ScheduledTaskTrigger`,
  `New-ScheduledTaskAction`, `New-ScheduledTaskPrincipal`, `New-ScheduledTaskSettingsSet`,
  then `Register-ScheduledTask`; plus `Get/Set/Unregister/Start/Stop-ScheduledTask`. Object-
  based, no XML hand-assembly required.
- **COM Task Scheduler 2.0 API (`ITaskService`)** — the native API:
  `ITaskService::Connect` → `GetFolder` → `NewTask` → populate
  `ITaskDefinition` (triggers/actions/settings/principal) → `ITaskFolder::RegisterTask`
  (or `RegisterTaskDefinition`). `RegisterTask` also accepts a raw XML string, so the COM
  route and the XML route converge.
  ([MS Learn: Task Scheduler schema (RegisterTask)](https://learn.microsoft.com/en-us/windows/win32/taskschd/task-scheduler-schema))

### B.4 Crucial difference: tasks don't need the SCM protocol

A scheduled task's action is plain `CreateProcess` of whatever `<Command>` you specify.
**Any exe, script, or command line works** — `python`, `node`, `java -jar`, a `.bat`, etc.
No `StartServiceCtrlDispatcher`, no `SetServiceStatus`, no wrapper. **This makes Task
Scheduler the natural Windows target for launchd's "run this command on a schedule"
(`StartCalendarInterval`/`StartInterval`) jobs** — and, arguably, even for some
"run-at-boot, fire-and-forget" jobs that don't need true service supervision.

---

## C. Synthesis for our library

### C.1 Concept mapping (launchd → Windows)

| launchd concept                       | Windows Service equivalent                              | Windows Task Scheduler equivalent             |
|---------------------------------------|---------------------------------------------------------|-----------------------------------------------|
| `Label`                               | Service name (registry key under `Services`)            | Task name (+ folder path)                     |
| `ProgramArguments`                    | `binPath` / `ImagePath` (**but must be a service-protocol exe** — see gap below) | `<Actions><Exec><Command>` + `<Arguments>` (any exe) |
| `RunAtLoad` (daemon)                  | Start type `auto` (`SERVICE_AUTO_START`)                | `<BootTrigger>`                               |
| `RunAtLoad` (user agent)              | Per-user service (auto) **or** logon task               | `<LogonTrigger>`                              |
| `KeepAlive=true`                      | Recovery actions `restart/restart/restart` + failure-on-nonzero flag (approximate) | (n/a — tasks are one-shot per trigger)        |
| `KeepAlive { conditions }`            | No clean equivalent; wrapper-managed restart logic      | Limited (`RestartOnFailure` setting)          |
| `StartCalendarInterval` (cron-ish)    | (n/a — services aren't scheduled)                       | `<CalendarTrigger>` (Daily/Weekly/Monthly...) |
| `StartInterval` (every N seconds)     | (n/a)                                                   | Trigger `Repetition` interval                 |
| `StandardOutPath`/`StandardErrorPath` | Not native to SCM; wrapper handles redirection (WinSW/NSSM logs) | Not native; redirect in the command itself, or via wrapper |
| `ProcessType` (Background/Interactive)| `type=` + priority (no direct map; wrappers set priority) | `<Settings><Priority>`                        |
| Scope `USER_AGENT` (`~/Library`)      | Per-user service / logon task (user context)            | Task with user `<Principal>` (`LeastPrivilege`)|
| Scope `GLOBAL_AGENT`/`SYSTEM_DAEMON`  | Service as `LocalSystem`/`LocalService`/`NetworkService` (elevated install) | Task as SYSTEM / specified principal (`HighestAvailable`) |
| `launchctl bootstrap/bootout`         | `CreateService` + `StartService` / `DeleteService`      | `RegisterTask` / `Unregister-ScheduledTask`   |
| `launchctl kickstart`                 | `StartService`                                          | `Start-ScheduledTask` / `schtasks /run`       |
| `launchctl kill <sig>`                | `ControlService(STOP)` (no arbitrary signals on Windows)| `Stop-ScheduledTask`                          |
| `launchctl enable/disable`            | `ChangeServiceConfig start= disabled/auto`              | `Disable/Enable-ScheduledTask`                |
| `launchctl print`                     | `QueryServiceStatusEx` / `sc query` (structured)        | `Get-ScheduledTaskInfo`                        |
| domain `gui/<uid>` vs `system`        | run-as account + elevation                              | `<Principal>` user + run level                |

### C.2 Where the abstractions DON'T line up

1. **The service-protocol wrapper problem (the big one).** launchd runs any binary as a
   daemon; a Windows `SERVICE_WIN32` binary **must** speak the SCM protocol. A direct port of
   `mgr.install(job, SYSTEM_DAEMON)` that just sets `binPath = <user command>` will produce a
   broken service (error 1053). The library must either (a) route such jobs through a bundled
   wrapper, or (b) route "run a command continuously" to a different mechanism.
2. **KeepAlive ≠ recovery actions.** Continuous, condition-rich keep-alive vs. finite,
   crash-triggered recovery (max 3 steps; ignores clean exits by default). Not a faithful map.
3. **Two subsystems, one launchd.** launchd unifies daemons + cron. Windows forces a
   *choice* (or a split) between Services and Task Scheduler per job. The library's single
   `LaunchdJob` model maps to **two different Windows backends** depending on whether the job
   is "keep running" (Service) or "run on a schedule/trigger" (Task).
4. **No stdout/stderr redirection in the SCM.** launchd's `StandardOutPath`/`StandardErrorPath`
   have no SCM equivalent; only wrappers (or the command itself) provide it.
5. **Signals.** launchd `kill <signal>` has no analog — Windows services accept a fixed set
   of *control codes*, not POSIX signals.
6. **Elevation everywhere.** No unprivileged equivalent to writing `~/Library/LaunchAgents`
   for system-scoped jobs; service/task install at machine scope needs admin.

### C.3 Recommendation — cleanest native approach for the Java library

Three candidate backends were considered:

| Approach                          | Pros                                                                 | Cons                                                                                 |
|-----------------------------------|---------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| **`sc.exe` / PowerShell / `schtasks` subprocess** | Mirrors the existing macOS design (the library already shells out to `launchctl`); zero native code; trivial to stub in tests behind the same `Launchctl`-style interface; covers create/config/start/stop/query/failure/delete and tasks; structured-enough output to parse | Brittle CLI arg quoting (`binPath= ` space quirk!); must parse text; `Remove-Service` missing pre-PS6 (use `sc delete`); slower (process spawn per op) |
| **`advapi32` via Java FFM**       | No external process; structured C structs (real PID, exact states); strongly-typed config and failure actions | Significant FFM marshalling of Unicode strings + variable-length blobs (`SERVICE_FAILURE_ACTIONS`, double-null dependency lists); Windows-only native code to maintain/test; **does nothing about the wrapper problem** — still can't run an arbitrary command as a service |
| **Bundled service-host shim (WinSW-style or own)** | The *only* way to truly "run any command as a service"; gives stdout/stderr logging + restart loops that better approximate `KeepAlive` | Bundling a third-party exe (license/footprint) or building+maintaining a native shim; install becomes "drop shim + write its config + register the shim as the service" |

**Recommended architecture — match the job kind to the backend, behind one facade:**

1. **Keep the facade and the `launchctl`-style indirection.** Define a Windows
   `ServiceController` / `TaskController` interface (parallel to `Launchctl`) so tests stub it
   on any OS, exactly as the macOS design already does.

2. **Primary backend: subprocess (`sc.exe` + `schtasks.exe`/PowerShell `ScheduledTasks`).**
   It mirrors the proven macOS pattern (we already trust shelling out to `launchctl`), needs
   no native code, and parses cleanly enough. Centralize the `binPath= ` spacing/quoting
   quirk in one place. Use `sc delete` (not `Remove-Service`) for portability across PS
   versions.

3. **Route by job semantics:**
   - **Scheduled / calendar jobs** (`StartCalendarInterval`, `StartInterval`) →
     **Task Scheduler**. Any command works directly — no wrapper, no protocol headache. This
     is the clean, faithful path and should be the default for cron-shaped jobs.
   - **Continuous daemons** (`KeepAlive` / long-running `ProgramArguments`) → **Service via a
     bundled service-host shim** (WinSW is the pragmatic choice: permissive license, active
     v3 line, XML config the library can generate). The library generates the shim's XML
     (binary, args, working dir, log paths → mapping `StandardOutPath`/`StandardErrorPath`,
     restart policy → approximating `KeepAlive`) and registers the renamed shim as the
     service. This is the only design that honestly delivers launchd's "run anything"
     semantics on the service side.

4. **Reserve FFM (`advapi32`) for a later, optional precision pass** — richer `status()`
   (exact state + PID + exit codes from `QueryServiceStatusEx`) where parsing `sc query` is
   unsatisfying. It does **not** solve the wrapper problem, so it is an enhancement, not the
   foundation.

**Net:** the cleanest design is **subprocess-first, two-backend (Task Scheduler for
schedules, Service-via-WinSW-shim for daemons), facade-unified, FFM optional later.** This
keeps parity with the existing macOS architecture, avoids premature native code, and — most
importantly — confronts the service-protocol quirk head-on instead of shipping a broken
"set binPath to your command" service.

---

## Quirks & gotchas

- **Service binaries must speak the SCM protocol** (`StartServiceCtrlDispatcher` /
  `SetServiceStatus`). Pointing `binPath=` at an arbitrary command yields **error 1053**
  ("did not respond in a timely fashion"). Wrappers (WinSW/NSSM) exist solely to fix this.
  ([StartServiceCtrlDispatcherW](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-startservicectrldispatcherw))
- **`sc create` argument spacing:** `key= value` requires a space *after* `=`, none before.
  ([Hexacorn](https://www.hexacorn.com/blog/2020/08/20/sc-and-its-quirky-cmd-line-args/))
- **`Remove-Service` needs PowerShell 6+**; it's absent from in-box Windows PowerShell 5.1.
  Use `sc delete` for portability.
  ([Remove-Service](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/remove-service))
- **Service name ≠ display name.** The registry key / API operations use the short service
  name; `DisplayName` is cosmetic. Both must be unique.
- **`DeleteService` is deferred:** the key is marked for deletion and removed only when all
  open handles close and the service is stopped. A "delete then immediately recreate" can hit
  `ERROR_SERVICE_MARKED_FOR_DELETE`.
- **Delayed-auto-start is a flag, not a start type** (`SERVICE_AUTO_START` +
  `DelayedAutostart`).
- **Recovery default ignores clean nonzero exits** — set
  `SERVICE_CONFIG_FAILURE_ACTIONS_FLAG` to also recover on graceful failures.
- **Session 0 isolation** killed `SERVICE_INTERACTIVE_PROCESS` desktop interaction; don't
  rely on services touching the GUI (services run in session 0). The launchd "agent can touch
  the GUI" model maps to **per-user services / logon tasks**, not session-0 services.
- **All install/config/delete operations require elevation;** non-elevated → `ERROR_ACCESS_DENIED`.
- **Per-user service instances are named `<Template>_<LUID>`** and are created/destroyed per
  logon session — don't hardcode the instance name.
  ([Per-user services](https://learn.microsoft.com/en-us/windows/application-management/per-user-services-in-windows))
- **Task Scheduler XML file and registry `TaskCache` must stay in sync** — register via
  `schtasks`/PowerShell/COM, never by dropping an XML file into `System32\Tasks` by hand.
- **Don't hand-edit `HKLM\...\Services` values** — go through SCM APIs so caches, security
  descriptors, and the `FailureActions` blob stay consistent.

---

## Sources

**Service Control Manager / registry**
- HKLM\SYSTEM\CurrentControlSet\Services registry tree — https://learn.microsoft.com/en-us/windows-hardware/drivers/install/hklm-system-currentcontrolset-services-registry-tree

**Win32 service API & structs**
- CreateServiceW (params, type/start/error values, registry values written) — https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-createservicew
- OpenServiceA — https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-openservicea
- StartServiceCtrlDispatcherW (service protocol) — https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-startservicectrldispatcherw
- SERVICE_STATUS — https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_status
- SERVICE_STATUS_PROCESS (PID, state) — https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_status_process
- SERVICE_DELAYED_AUTO_START_INFO — https://learn.microsoft.com/en-us/windows/win32/api/winsvc/ns-winsvc-service_delayed_auto_start_info
- JNA Advapi32 (cross-reference for struct/function shapes) — https://java-native-access.github.io/jna/4.2.1/com/sun/jna/platform/win32/Advapi32.html

**Command-line / PowerShell management**
- sc (ss64 reference) — https://ss64.com/nt/sc.html
- Configuring a service using sc — https://github.com/MicrosoftDocs/win32/blob/docs/desktop-src/Services/configuring-a-service-using-sc.md
- sc argument-spacing quirk — https://www.hexacorn.com/blog/2020/08/20/sc-and-its-quirky-cmd-line-args/
- sc failure (recovery actions) — https://learn.microsoft.com/en-us/previous-versions/windows/it-pro/windows-server-2012-r2-and-2012/cc742019(v=ws.11)
- Octopus: set recovery on failure actions — https://octopus.com/integrations/windows/windows-service-set-recovery-on-failure-actions
- New-Service — https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/new-service
- Set-Service — https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/set-service
- Remove-Service (PS 6.0+) — https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.management/remove-service

**Config model / accounts / start types**
- Startup types explained — https://www.coretechnologies.com/blog/windows-services/startup-types-explained/
- LocalSystem account — https://learn.microsoft.com/en-us/windows/win32/services/localsystem-account
- LocalService account — https://learn.microsoft.com/en-us/windows/win32/services/localservice-account
- Service accounts overview — https://learn.microsoft.com/en-us/windows-server/identity/ad-ds/manage/understand-service-accounts

**Per-user services**
- Per-user services in Windows — https://learn.microsoft.com/en-us/windows/application-management/per-user-services-in-windows
- HelgeKlein: per-user services info & configuration — https://helgeklein.com/blog/per-user-services-in-windows-info-and-configuration/

**Wrappers (the service-protocol fix)**
- WinSW (repo) — https://github.com/winsw/winsw
- WinSW releases (2.11.0 stable; v3 alphas) — https://github.com/winsw/winsw/releases
- Infonautics: run any program as a service with WinSW — https://www.infonautics.ch/blog/run-any-program-as-a-windows-background-service-with-winsw/
- NSSM (fork) — https://github.com/AtiX/nssm
- Servy vs NSSM vs WinSW — https://dev.to/aelassas/servy-vs-nssm-vs-winsw-2k46

**Task Scheduler**
- Task Scheduler schema — https://learn.microsoft.com/en-us/windows/win32/taskschd/task-scheduler-schema
- Daily Trigger XML example — https://learn.microsoft.com/en-us/windows/win32/taskschd/daily-trigger-example--xml-
- Windows Task Scheduler (overview) — https://en.wikipedia.org/wiki/Windows_Task_Scheduler
- Task Scheduler files (forensic artifact notes: file + registry locations) — https://github.com/Psmths/windows-forensic-artifacts/blob/main/persistence/task-scheduler-files.md

**Java FFM (for the native-approach evaluation)**
- JEP 454: Foreign Function & Memory API — https://openjdk.org/jeps/454
- Oracle: Foreign Function and Memory API — https://docs.oracle.com/en/java/javase/21/core/foreign-function-and-memory-api.html
