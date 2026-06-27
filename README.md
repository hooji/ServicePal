# ServicePalForJava

A clean, immutable-first Java API for **creating and managing OS-level background
services / daemons** with one uniform surface across **macOS (launchd), Linux
(systemd & OpenRC), and Windows (SCM + Task Scheduler)**.

> Repo is still named `JLaunchdManagerForMacs` (historical); it will be renamed
> *ServicePalForJava*. Root package is `com.u1.servicepal`.

## Status

Design is complete (see `docs/`). Implementation is in progress, vertical-slice first:

| Area | macOS | systemd | OpenRC | Windows |
|------|:----:|:-------:|:------:|:-------:|
| **Discovery / inspection** (`list`, `read`, `status`) | ✅ | ⬜ | ⬜ | ⬜ |
| **Mutation** (`install`, `start`, `enable`, …) | ⬜ | ⬜ | ⬜ | ⬜ |

The current build implements **discovery + inspection on macOS** end-to-end, plus the
full cross-platform model, platform detection, and a discovery CLI. Mutation and the
other backends throw a clear `UnsupportedOperationException` for now.

## Build & test

```sh
mvn verify
```

Requires JDK 21+ for now (the implemented macOS/Linux paths are subprocess + file I/O,
no FFM). The baseline rises to **JDK 25** when the Windows FFM service host lands.

## Try the discovery CLI

```sh
mvn -q package
java -jar target/servicepal.jar            # list all discovered services
java -jar target/servicepal.jar --managed  # only services ServicePal created
```

On macOS it enumerates the launchd jobs in `~/Library/LaunchAgents`,
`/Library/LaunchDaemons`, and `/Library/LaunchAgents`, enriched with live state from
`launchctl list`. On other platforms it prints a friendly "not implemented yet" note.

## Releases

Releases are automated (see `.github/workflows/`):

- **Tag Release** (`release.yml`) — on a pushed `v*` tag (or manual dispatch), builds and
  publishes a GitHub Release with `servicepal-<version>.jar` (runnable fat jar) and
  `servicepal-<version>-sources.jar`. The version comes from the tag via `-Drevision`.
- **Bump version and release** (`version-bump.yml`) — when a PR is merged into `main`,
  computes the next version from the latest `v*` tag (patch by default; `release:minor` /
  `release:major` PR labels override; `release:skip` label or `[skip release]` in the title
  opts out), pushes the tag, and dispatches Tag Release. Also runnable manually.

## Layout

- `docs/research/` — per-platform research + the cross-platform synthesis.
- `docs/design/api-design.md` — the API design (the three-tier uniformity model).
- `docs/ROADMAP.md` — deferred items.
- `src/main/java/com/u1/servicepal/` — the library (`ServiceManager`, `model/`, `internal/`).
- `CLAUDE.md` — project knowledge base for contributors and AI agents.
