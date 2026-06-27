# Linux service management beyond systemd — the non-systemd init landscape (2026)

> Research question: **"Is Linux service management standardized, or do we need to handle
> multiple init systems?"** — context: a cross-platform Java library that creates/manages
> OS-level services (macOS = launchd; the systemd case is covered separately).
>
> **Short answer:** No, it is not standardized. systemd is *dominant* on mainstream desktop
> and server distros, but it is **not universal**, and the single most important non-systemd
> environment for a server-side library is **Alpine Linux (OpenRC)**, which is the default
> base image for a large share of Docker/container workloads. A library that ignores
> non-systemd Linux will fail in exactly the place server software most often runs: containers.

---

## A. The distro landscape (2026)

### Distro → default init system

| Distro                     | Default init (2026)            | Notes |
|----------------------------|--------------------------------|-------|
| Debian                     | **systemd**                    | systemd since Debian 8 (2015). sysvinit still installable; Devuan exists as the non-systemd fork. |
| Ubuntu                     | **systemd**                    | systemd since 15.04 (replaced Upstart, which Canonical originally authored). |
| Fedora                     | **systemd**                    | systemd's origin distro; always systemd. |
| RHEL / Rocky / AlmaLinux   | **systemd**                    | systemd since RHEL 7. (RHEL 6 was Upstart — long EOL.) |
| openSUSE / SLE             | **systemd**                    | systemd since ~12.x. |
| Arch Linux                 | **systemd**                    | systemd since 2012. (Artix is the OpenRC/runit/s6/dinit fork.) |
| **Alpine Linux**           | **OpenRC**                     | **Not systemd.** Huge in Docker/containers. `rc-service` / `rc-update`. |
| Void Linux                 | **runit**                      | Not systemd. `sv` command, `/etc/sv`, `/var/service`. |
| Gentoo                     | **OpenRC** (systemd optional)  | OpenRC is the historical default; systemd is a fully supported profile. s6 also packaged. |
| Devuan                     | **SysV init** (sysvinit)       | The "Debian without systemd" fork; OpenRC and runit also selectable. |
| Slackware                  | **BSD-style init** (sysvinit-ish) | Oldest active distro; never adopted systemd. Uses BSD-style `/etc/rc.d` scripts. |
| Artix Linux                | OpenRC / runit / s6 / dinit    | Arch fork explicitly built around init choice. |
| antiX / MX (sysvinit edition) | SysV init (+ dinit/s6/runit options) | antiX 23.2 (2025) ships an "init diversity" remaster. |

Sources: [eylenburg distro comparison](https://eylenburg.github.io/linux_comparison.htm),
[It's FOSS — systemd-free distros](https://itsfoss.com/systemd-free-distros/),
[ArchWiki — Arch compared to other distributions](https://wiki.archlinux.org/title/Arch_compared_to_other_distributions),
[Alpine OpenRC wiki](https://wiki.alpinelinux.org/wiki/OpenRC),
[Void Handbook — services](https://docs.voidlinux.org/config/services/index.html),
[Gentoo — Comparison of init systems](https://wiki.gentoo.org/wiki/Comparison_of_init_systems),
[antiX init diversity 2025](https://itsfoss.community/t/antix-23-2-init-diversity-2025-remaster-edition-includes-dinit/13696).

### How dominant is systemd, really?

- **Mainstream desktops/servers (bare metal + VMs):** systemd is effectively the default —
  Debian, Ubuntu, Fedora, RHEL family, openSUSE, Arch all ship it. For a typical
  long-lived server VM, "Linux ≈ systemd" is a defensible 90%+ assumption.
- **The big exceptions that actually matter:**
  1. **Containers / Alpine (OpenRC).** Alpine is one of the most-pulled base images on
     Docker Hub precisely because it is tiny (~5 MB). Its default init is **OpenRC**, not
     systemd. This is the single highest-impact non-systemd case for server tooling.
     ([Alpine OpenRC wiki](https://wiki.alpinelinux.org/wiki/OpenRC))
  2. **Containers generally.** Even on systemd-based *host* distros, most containers run a
     single foreground process as PID 1 — **there is no init system inside the container at
     all** unless one is deliberately added (s6-overlay, tini, dumb-init, etc.).
     ([s6-overlay](https://github.com/just-containers/s6-overlay))
  3. **Embedded / appliances (BusyBox init).** Minimal/embedded Linux and many small
     container images use BusyBox `init` driven by `/etc/inittab`.
  4. **Deliberately systemd-free distros:** Devuan (SysV), Void (runit), Gentoo/Artix
     (OpenRC/runit/s6/dinit), Slackware (BSD init). Smaller user base, but real.

**Bottom line:** standardized *for ordinary VMs/desktops* ≈ yes (systemd). Standardized
*for the full deployment surface a server library will hit* ≈ **no**, mainly because of
containers and Alpine.

---

## B. The non-systemd systems

### 1. SysV init (sysvinit)

- **Where definitions live:** `/etc/init.d/<name>` — executable shell scripts. Runlevel
  wiring lives in `/etc/rc<N>.d/` as `S##name` / `K##name` symlinks into `/etc/init.d`.
- **Format:** plain shell script implementing a `start|stop|restart|status|reload` case
  block, plus an **LSB header** comment block parsed by tooling:
  ```sh
  ### BEGIN INIT INFO
  # Provides:          myservice
  # Required-Start:    $network $remote_fs
  # Required-Stop:     $network $remote_fs
  # Default-Start:     2 3 4 5
  # Default-Stop:      0 1 6
  # Short-Description: My service
  ### END INIT INFO
  ```
  ([Debian LSBInitScripts](https://wiki.debian.org/LSBInitScripts))
- **Management commands:**
  - Run: `service <name> start|stop|restart|status` (or `/etc/init.d/<name> start`).
  - Enable/disable at boot: **Debian** `update-rc.d <name> defaults` / `update-rc.d -f <name> remove`;
    **RHEL-family** `chkconfig --add <name>` / `chkconfig <name> on|off`.
    ([HackerStack SysV/systemd](https://www.hackerstack.org/linux-system-v-init-and-systemd-essentials/),
    [chkconfig manpage](https://manpages.debian.org/testing/sysv-rc-conf/chkconfig.8.en.html))
- **Concept model:** numeric **runlevels** (0 halt, 1 single-user, 3 multi-user text,
  5 graphical, 6 reboot). No process supervision — once started, init does not restart a
  crashed service.
- **Still relevant where:** Devuan (default), Slackware (variant), legacy enterprise systems,
  and as a *compatibility layer* under other inits. **Important 2026 change:** systemd has
  **removed** its SysV-script compatibility. Deprecated in v257, originally slated for v258,
  postponed, and the `systemd-sysv-generator` (plus `rc-local-generator`/`systemd-sysv-install`)
  were removed in **systemd v260**. So on a modern systemd box, dropping an old `/etc/init.d`
  script no longer "just works."
  ([systemd INCOMPATIBILITIES](https://systemd.io/INCOMPATIBILITIES/),
  [LWN — systemd 257](https://lwn.net/Articles/1001657/),
  [Debian devel thread, 2025](https://lists.debian.org/debian-devel/2025/05/msg00062.html),
  [FOSSLinux — systemd v260 removed SysV](https://www.fosslinux.com/157348/systemd-v260-removed-sysv-init-convert-legacy-scripts.htm))

### 2. OpenRC (Alpine, Gentoo)

- **Where definitions live:** service scripts in `/etc/init.d/<name>` (note: **same path as
  SysV but a different format**), per-service settings in `/etc/conf.d/<name>`, global config
  in `/etc/rc.conf`. Runlevels are directories under `/etc/runlevels/` (`sysinit`, `boot`,
  `default`, `shutdown`); an enabled service is a symlink from a runlevel dir to the
  `/etc/init.d` script.
  ([Alpine OpenRC docs](https://docs.alpinelinux.org/user-handbook/0.1a/Working/openrc.html))
- **Format:** an OpenRC init script (POSIX shell sourcing `/sbin/openrc-run`) declaring
  `command=`, `command_args=`, `pidfile=`, `depend()`, and optional `start()/stop()`
  functions. `/etc/conf.d/<name>` holds environment-style `KEY=value` overrides.
- **Management commands:**
  - `rc-service <name> start|stop|restart|status`
  - `rc-update add <name> <runlevel>` / `rc-update del <name> <runlevel>` (enable/disable)
  - `rc-status` to view runlevel state; `openrc <runlevel>` to switch runlevels.
  ([nixCraft — Alpine services](https://www.cyberciti.biz/faq/how-to-enable-and-start-services-on-alpine-linux/))
- **Supervision:** OpenRC itself does not supervise long-running processes by default, but it
  can delegate to a supervisor (`supervise-daemon`, or s6/runit) for restart-on-crash.
- **Why it matters most:** **Alpine ships OpenRC by default**, and Alpine is a dominant
  container base image. If the library targets containers, OpenRC is the most likely
  non-systemd backend it will encounter. (Caveat: many Alpine *containers* still run a single
  app as PID 1 with no OpenRC running at all — see Detection section.)

### 3. runit (Void, some containers)

- **Where definitions live:** one directory per service under `/etc/sv/<name>/` containing an
  executable **`run`** script (and optionally `finish`, `check`, a `log/run` for logging, and
  a `conf` file). Enabling = symlink `/etc/sv/<name>` into the active runsvdir. The active
  runsvdir is `/etc/runit/runsvdir/<current>` (e.g. `default`), exposed as the `/var/service`
  symlink (Void) — note other distros/Arch use `/run/runit/service` or `/service`.
  ([Void Handbook — services](https://docs.voidlinux.org/config/services/index.html),
  [ArchWiki — runit](https://wiki.archlinux.org/title/Runit))
- **Format:** `run` is a shell script that **execs the daemon in the foreground** (must not
  daemonize/fork — runit supervises the PID it spawns). Example:
  ```sh
  #!/bin/sh
  exec myserver --no-daemon 2>&1
  ```
- **Management commands:** `sv up|down|restart|status <name>`, `sv once <name>`. Enable =
  `ln -s /etc/sv/<name> /var/service/`; disable = remove that symlink. A `down` file in the
  service dir prevents auto-start.
- **Key trait:** runit is a **process supervisor first** — every service is supervised and
  auto-restarted on exit by default. Simple, fast, no dependency ordering (ordering is done
  with shell `sv start`/wait inside `run` scripts).

### 4. BusyBox init (minimal containers / embedded)

- **Where definitions live:** a single `/etc/inittab` (optional — BusyBox init has built-in
  defaults if absent). Plus whatever shell scripts the inittab entries invoke
  (often a single `/etc/init.d/rcS`).
- **Format:** `<id>:<runlevels>:<action>:<process>`. **The runlevels field is ignored** by
  BusyBox init. Actions include `sysinit`, `respawn` (restart on exit), `askfirst`, `once`,
  `wait`, `ctrlaltdel`, `shutdown`, `restart`.
  ([BusyBox example inittab](https://github.com/brgl/busybox/blob/master/examples/inittab),
  [Foxipex — BusyBox init](https://www.foxipex.com/2024/11/15/busybox-init-system-a-lightweight-approach-to-system-initialization/))
- **Limitations:** no runlevels, no dependency ordering, no per-service status command, no
  enable/disable abstraction — you edit `inittab` and reboot/reload. `respawn` has no
  flood-protection (a fast-crashing service spins). Effectively a "start these processes"
  list. Used in embedded systems and minimal container images.

### 5. Briefly: s6/s6-rc, Upstart, dinit

- **s6 + s6-rc** (skarnet): a small, security-focused supervision suite (`s6`) plus a
  dependency-based service manager (`s6-rc`) that compiles a service database from source
  definitions. Service dirs resemble runit (`run`/`finish`), but s6-rc adds a dependency
  graph and atomic state transitions. Heavily used in containers via **s6-overlay**
  (`just-containers/s6-overlay`) to run multiple supervised processes in one image.
  Available in Gentoo/Artix. ([Gentoo s6 wiki](https://wiki.gentoo.org/wiki/S6_and_s6-rc-based_init_system),
  [s6-overlay](https://github.com/just-containers/s6-overlay))
- **Upstart** — **dead/legacy.** Canonical's event-based init; was Ubuntu's default
  (~6.10–14.10) and RHEL 6's init. Superseded by systemd everywhere; no new development.
  Treat as historical only.
- **dinit** — a modern C++ supervising init with dependency support and per-service config
  files; the default in some Artix editions and offered in antiX 23.2 (2025). Niche but
  growing. ([antiX init diversity](https://itsfoss.community/t/antix-23-2-init-diversity-2025-remaster-edition-includes-dinit/13696),
  [Gentoo init comparison](https://wiki.gentoo.org/wiki/Comparison_of_init_systems))

---

## C. Detecting which init system is in use

No single signal is sufficient (especially in containers). Use a **layered** strategy and
take the first confident match. ([Baeldung — identify service manager](https://www.baeldung.com/linux/service-manager-identify),
[get_init_system project](https://github.com/ecxod/get_init_system),
[dotLinux — detecting the service manager](https://www.dotlinux.net/blog/detecting-which-system-manager-is-running-on-linux-system/))

**Robust signal order:**

1. **systemd:**
   - Best signal: directory `/run/systemd/system` exists → systemd is the *active, booted*
     manager (this is systemd's own recommended runtime check; more reliable than just having
     the binary).
   - Plus: `systemctl` on `PATH`, and `/proc/1/comm` == `systemd`.
2. **PID 1 identity (`/proc/1/comm`, or `readlink /proc/1/exe`):**
   - `systemd` → systemd
   - `init` → ambiguous: could be SysV `init`, OpenRC's `/sbin/init`, or BusyBox. Disambiguate:
     - OpenRC: `/sbin/openrc` and `/sbin/openrc-run` exist, `rc-status`/`rc-service` on PATH,
       `/etc/init.d` scripts source `openrc-run`, dirs under `/etc/runlevels/`.
     - SysV: `/etc/inittab` in classic format + `/etc/init.d` LSB scripts, `update-rc.d` or
       `chkconfig` present, **no** `openrc`/`systemd` markers.
     - BusyBox: `/proc/1/exe` resolves to a `busybox` binary; `/bin/init` is a busybox symlink.
   - `runit` / `runsvdir` / `runit-init` → runit. Confirm with `/etc/sv`, the `sv` binary,
     and the active runsvdir (`/var/service`, `/run/runit/service`, or `/service`).
   - `s6-svscan` → s6. `dinit` → dinit.
3. **Marker files / dirs (corroborating):**
   - systemd: `/run/systemd/system`
   - OpenRC: `/sbin/openrc`, `/etc/runlevels/`, `/run/openrc/`
   - runit: `/etc/sv/`, `/var/service` (or `/run/runit/`)
   - s6: `/run/s6/` or s6-rc live dir; `s6-svscan` running
   - BusyBox: `init`/`/bin/init` is a busybox applet
4. **Binary availability on PATH** (`systemctl`, `rc-service`/`rc-update`/`openrc`, `sv`,
   `s6-rc`, `dinit`/`dinitctl`, `service`, `chkconfig`, `update-rc.d`) — weakest signal,
   because a binary can be installed without that init being the active PID 1.

**Container caveat (critical for this library):** inside a container, PID 1 is frequently the
application itself (e.g. `/bin/bash`, `java`, `node`) or a tiny init shim (tini/dumb-init), so
`/proc/1/comm` does **not** reveal a service manager — because there isn't one. Detection must
distinguish "non-systemd init X is running" from "no service manager present at all," and the
library should surface the latter clearly rather than guessing.
([HackerNoon — PID 1 in containers](https://medium.com/hackernoon/my-process-became-pid-1-and-now-signals-behave-strangely-b05c52cc551c))

---

## D. The "run as a logged-in user's agent" question

launchd has first-class **user Agents** (`~/Library/LaunchAgents`, GUI session) and systemd
has first-class **`--user` units** (`systemctl --user`, `~/.config/systemd/user`). The
non-systemd inits are far weaker here:

| Init        | Native per-user services? | Reality |
|-------------|---------------------------|---------|
| systemd     | **Yes** (`systemctl --user`, lingering via `loginctl enable-linger`) | First-class; closest analog to launchd Agents. |
| SysV init   | **No**                    | System-level only. "Per-user" = a system script that drops privileges (`su`/`setuidgid`). |
| OpenRC      | **Partial / newer**       | OpenRC has a `--user`/`-U` mode reading `${XDG_CONFIG_HOME}/rc/init.d` and `/etc/user/init.d`, with optional PAM-driven session management — but it is not as turnkey or as widely deployed as systemd `--user`. ([Gentoo — OpenRC user services](https://wiki.gentoo.org/wiki/OpenRC/Legacy_user_services), [OpenRC user-guide](https://github.com/OpenRC/openrc/blob/master/user-guide.md)) |
| runit       | **No (workaround)**       | No native concept. Pattern: a system service runs `runsvdir` as the user over `~/service`; **Turnstile** is a newer tool that ties per-user runit/dinit services to the login session. ([Void — per-user services](https://docs.voidlinux.org/config/services/user-services.html)) |
| BusyBox     | **No**                    | Single global inittab; no user concept. |
| s6 / dinit  | **Possible, manual**      | A user-owned supervisor can be launched, but there's no standard per-user UX comparable to systemd. |

**This is a genuine cross-platform gap.** A clean "user agent" abstraction maps well to
launchd and systemd `--user`, but does **not** have a portable, dependable equivalent across
SysV/OpenRC/runit/BusyBox. Any cross-platform "agent" feature will either be systemd-only on
Linux or rely on non-uniform workarounds.

---

## E. Synthesis & recommendation for our library

### Recommendation: **systemd-first for Linux v1**, with strict detection and a clear,
### actionable error on non-systemd — but architect the Linux side behind a pluggable
### `LinuxServiceBackend` interface so OpenRC can be added next.

Reasoning from the landscape data:

- **systemd covers the overwhelming majority of long-lived VMs/servers**, and is the *only*
  non-launchd init with a clean equivalent to nearly every launchd feature we care about
  (dependency ordering, supervision/restart, **per-user agents** via `--user`, **calendar
  scheduling** via `OnCalendar=` timers, rich status via `systemctl status`/`show`,
  socket activation). It is the natural primary target and the closest conceptual sibling to
  launchd.
- **But "systemd-only" is dangerous for a server library specifically because of containers.**
  Alpine (OpenRC) is a top container base image, and many containers have **no init at all**.
  A library that hard-assumes systemd will throw or silently misbehave in the single most
  common server deployment shape (containers). So at minimum, **detection must be first-class**
  and the failure mode must be explicit: *"This host uses OpenRC / has no service manager
  (PID 1 = `<x>`); systemd is required for v1"* — not a confusing `systemctl: not found`.
- **The pragmatic order of investment:**
  1. **v1: systemd backend** (covers most VMs) + robust detection (Section C) +
     a precise unsupported-init error.
  2. **v2: OpenRC backend** — the highest-value second target because of Alpine/containers.
  3. **Later/optional: runit** (Void + s6-overlay-style container use), then SysV (Devuan/legacy).
     BusyBox/dinit/s6 are niche enough to defer or skip.

### If/when pluggable: the common-denominator feature set

A portable backend interface can reliably express only the intersection of what these inits
provide:

**Safely portable across systemd / OpenRC / runit / SysV:**
- Define a service (program + args + working dir + env).
- Install/enable at boot, uninstall/disable.
- Start / stop / restart.
- Basic status: is it installed, is it enabled, is it running (+ PID where available).

**Features that degrade or vanish on the "lesser" inits (must be optional / capability-flagged):**

| Feature                          | systemd | OpenRC            | runit            | SysV    | BusyBox |
|----------------------------------|---------|-------------------|------------------|---------|---------|
| Auto-restart / supervision       | ✅      | ⚠️ via supervisor | ✅ (default)      | ❌      | ⚠️ `respawn` |
| Dependency ordering              | ✅      | ✅ (`depend()`)   | ⚠️ manual         | ⚠️ LSB  | ❌      |
| **Per-user agents** (launchd-like) | ✅ `--user` | ⚠️ `--user` (newer) | ❌ workaround | ❌      | ❌      |
| **Calendar scheduling** (launchd `StartCalendarInterval`) | ✅ timers | ❌ (use cron) | ❌ (use cron) | ❌ (use cron) | ❌ |
| Rich status / last-exit-code     | ✅      | ⚠️ limited        | ⚠️ limited        | ❌      | ❌      |
| Socket activation                | ✅      | ❌                | ❌                | ❌      | ❌      |

(Capability matrix synthesized from [Gentoo — Comparison of init systems](https://wiki.gentoo.org/wiki/Comparison_of_init_systems)
plus the per-system sections above.)

**Implication for the API design:** model Linux backends with an explicit **capability set**.
Calendar scheduling and per-user agents — both first-class in *launchd* — are essentially
**systemd-only** on Linux (elsewhere you'd fall back to `cron` and to drop-privilege system
services). The cross-platform public surface should treat those as *optional capabilities the
backend may not support*, and the library should report capability gaps rather than silently
emulating them.

---

## Sources

- eylenburg — Comparison of Linux distributions: https://eylenburg.github.io/linux_comparison.htm
- It's FOSS — Systemd-free Linux distributions: https://itsfoss.com/systemd-free-distros/
- ArchWiki — Arch compared to other distributions: https://wiki.archlinux.org/title/Arch_compared_to_other_distributions
- Tecmint — Best modern Linux init systems: https://www.tecmint.com/best-linux-init-systems/
- Alpine Linux — Working with OpenRC: https://docs.alpinelinux.org/user-handbook/0.1a/Working/openrc.html
- Alpine wiki — OpenRC: https://wiki.alpinelinux.org/wiki/OpenRC
- nixCraft — Enable/start services on Alpine: https://www.cyberciti.biz/faq/how-to-enable-and-start-services-on-alpine-linux/
- Void Linux Handbook — Services and Daemons (runit): https://docs.voidlinux.org/config/services/index.html
- Void Linux Handbook — Per-User Services: https://docs.voidlinux.org/config/services/user-services.html
- ArchWiki — Runit: https://wiki.archlinux.org/title/Runit
- HackerStack — SysV init and systemd essentials: https://www.hackerstack.org/linux-system-v-init-and-systemd-essentials/
- Debian Wiki — LSBInitScripts: https://wiki.debian.org/LSBInitScripts
- Debian Manpages — chkconfig: https://manpages.debian.org/testing/sysv-rc-conf/chkconfig.8.en.html
- systemd — Compatibility with SysV (INCOMPATIBILITIES): https://systemd.io/INCOMPATIBILITIES/
- LWN — Systemd 257 released: https://lwn.net/Articles/1001657/
- Debian devel list — sysv-init scripts without systemd unit (2025): https://lists.debian.org/debian-devel/2025/05/msg00062.html
- FOSSLinux — Systemd v260 removed SysV init (2026): https://www.fosslinux.com/157348/systemd-v260-removed-sysv-init-convert-legacy-scripts.htm
- BusyBox — example inittab: https://github.com/brgl/busybox/blob/master/examples/inittab
- Foxipex — BusyBox init system: https://www.foxipex.com/2024/11/15/busybox-init-system-a-lightweight-approach-to-system-initialization/
- Gentoo wiki — Comparison of init systems: https://wiki.gentoo.org/wiki/Comparison_of_init_systems
- Gentoo wiki — s6 and s6-rc-based init system: https://wiki.gentoo.org/wiki/S6_and_s6-rc-based_init_system
- Gentoo wiki — OpenRC/Legacy user services: https://wiki.gentoo.org/wiki/OpenRC/Legacy_user_services
- OpenRC — user-guide.md: https://github.com/OpenRC/openrc/blob/master/user-guide.md
- just-containers — s6-overlay: https://github.com/just-containers/s6-overlay
- antiX 23.2 init diversity (dinit, 2025): https://itsfoss.community/t/antix-23-2-init-diversity-2025-remaster-edition-includes-dinit/13696
- Baeldung — Identify the service manager: https://www.baeldung.com/linux/service-manager-identify
- get_init_system (POSIX init detection): https://github.com/ecxod/get_init_system
- dotLinux — Detecting the service manager: https://www.dotlinux.net/blog/detecting-which-system-manager-is-running-on-linux-system/
- HackerNoon — PID 1 in containers: https://medium.com/hackernoon/my-process-became-pid-1-and-now-signals-behave-strangely-b05c52cc551c
