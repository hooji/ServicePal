# Changelog

All notable changes to ServicePalForJava are documented here. The format is loosely based
on [Keep a Changelog](https://keepachangelog.com/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Cross-platform service-manager API design (macOS / systemd / OpenRC / Windows) — the
  three-tier uniformity model, `ServiceManager` facade, and `ServiceSpec` builder.
- macOS launchd **discovery & inspection** backend: `list` / `listManaged` / `read` /
  `readNative` / `status`, with `PER_USER`-then-`SYSTEM_WIDE` auto-resolution.
- Read-only discovery CLI (`servicepal.jar`).
- Automated releases: tag-driven `release.yml` and PR-merge `version-bump.yml`.

### Not yet implemented
- Mutation (`install` / `uninstall` / `start` / `stop` / `enable` / `disable`).
- The systemd, OpenRC, and Windows backends.
