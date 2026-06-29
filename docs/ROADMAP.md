# Roadmap

Forward-looking items deliberately deferred so v1 stays focused. Not commitments — a
parking lot for decisions already discussed.

## v1 (current target)

- Universal API (`ServiceManager` + `ServiceSpec`) per `docs/design/api-design.md`.
- Backends: **macOS launchd**, **Linux systemd**, **Linux OpenRC**, **Windows** (SCM via the
  bundled pure-Java FFM service host + Task Scheduler for scheduled jobs).
- **JDK 25** baseline (FFM final).
- Fail-fast on capability gaps.

## Deferred — Windows

- **WinSW as an alternative service host.** v1 ships the pure-Java FFM `ServiceHost` (no
  compiled binary) as the default, simple path covering 90%+ of cases. Later, optionally
  support delegating to **WinSW** for users who prefer a battle-tested wrapper or need its
  extras (log rotation, advanced restart throttling). Would be opt-in via `WindowsOptions`
  (e.g. `.host(WindowsServiceHost.WINSW)`); accept that it bundles/locates a compiled `.exe`,
  which is why it is *not* the default.
- Richer recovery-action mapping; SCM **user-service templates** (`SERVICE_USER_OWN_PROCESS`,
  the `Tmpl_<LUID>` instances) as a *true* per-user **service** (vs. the Task Scheduler logon-task
  approach now used for per-user — see below); trigger-start services.
- **Friendly display names for foreign services in `list()`.** Machine-wide discovery
  (`EnumServicesStatusExW`) is done, but foreign services surface by their service *key* name;
  surfacing the friendly display name needs either a `displayName` on `ServiceStatus` (a model
  change) or a per-service `QueryServiceConfig` lookup.

## Deferred — GUI

- **An "Advanced" disclosure** in the add/edit form for the fields the GUI deliberately hides
  today: **environment variables** and **log-file (stdout/stderr) paths**, and possibly explicit
  run-as identity. Kept out of the default UI to preserve the "dead simple, 90% case" surface;
  a collapsible "Advanced" section would expose them without cluttering the common path.
- **Richer schedule shapes** in the Repeat picker — monthly, every-N-hours, arbitrary cron —
  beyond today's every-N-minutes / daily / weekly.

## Deferred — broader JDK reach

- **Lower-JDK, Mac/Linux-only build.** The JDK 25 floor exists *only* because of the Windows
  FFM paths (SCM + service host). macOS (`launchctl`) and Linux (`systemctl`/`rc-service`) are
  pure subprocess + file I/O and need almost nothing modern. A separate artifact could target
  a much older baseline — potentially **JDK 8** — excluding the Windows backend. Keeps the
  library usable in conservative Mac/Linux server fleets. Cost: a second build profile and
  source-compatibility discipline (no records/sealed types/switch-patterns in shared code, or
  a multi-release jar). Revisit once the core stabilizes.

## Deferred — Linux

- **Capability fallbacks** instead of fail-fast where it's clearly useful — e.g. calendar
  schedules on OpenRC mapped to a **cron** entry (opt-in, never silent).
- **SysV init** and **runit** backends (Devuan, Void, older systems). Lower priority than
  systemd+OpenRC; OpenRC already covers the high-value Alpine/container case.
- Init-less container detection guidance (PID 1 is the app) — clear error + docs today;
  possibly a "foreground supervisor" mode later.

## Deferred — defaults & configuration

- **Configurable per-platform defaults.** v1 hard-codes sensible defaults for each platform's
  option block (e.g. systemd `Type=exec`, OpenRC `supervise-daemon` when restart != NEVER — see
  `docs/design/api-design.md` §6.1). These will be refined through experience. Later, expose a
  way to override the defaults globally (e.g. a `Defaults`/policy object passed to
  `ServiceManager`, or a config file) so a consumer can set their own house style without
  repeating option blocks on every spec.

## Deferred — features

- D-Bus/`sd-bus` (via FFM) for event-driven systemd status streaming, if a consumer needs
  push status instead of polling `systemctl show`.
- `advapi32` FFM as the *primary* Windows status/config path (beyond the host) if `sc` parsing
  proves insufficient.
- Lossless spec round-tripping (`read()`), where the native format allows it.
