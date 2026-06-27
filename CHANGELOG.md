# Changelog

All notable changes to ServicePal are documented here. The format is loosely based
on [Keep a Changelog](https://keepachangelog.com/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

A uniform Java API for creating and managing OS-level background services across macOS
(launchd), Linux (systemd and OpenRC), and Windows (Service Control Manager + Task
Scheduler).

### Features
- Cross-platform `ServiceManager` facade with runtime platform detection, an immutable
  `ServiceSpec` builder, and a uniform model (`RunAs`, `Installation`, `RestartPolicy`,
  `Schedule`, `Capabilities`, `ServiceStatus`).
- Full lifecycle on every platform: `install` (upsert) / `uninstall`, `start` / `stop` /
  `restart`, `enable` / `disable`, plus the `installEnableStart` convenience.
- Discovery and inspection: `list` / `listManaged` / `isManaged`, `read` (parsed spec) /
  `readNative` (verbatim definition), `status`, and `discover`.
- **macOS** backend over launchd (`.plist` + `launchctl`).
- **Linux/systemd** backend over `.service` units (`systemctl`).
- **Linux/OpenRC** backend over `/etc/init.d` scripts (`rc-service` / `rc-update`).
- **Windows** backend: daemons run as real services via a bundled pure-Java FFM service host
  that speaks the SCM control protocol; scheduled jobs route to Task Scheduler.
- Per-platform option blocks (`.mac` / `.systemd` / `.windows` / `.openrc`) with fail-fast
  validation for wrong-platform options and unsupported capabilities.
- A read-only discovery CLI and a self-test CLI that exercises a real lifecycle.
