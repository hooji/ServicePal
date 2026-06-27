# Java FFM API & Native-OS-Access Options for JLaunchdManagerForMacs

**Research doc — current as of June 2026.** Verified against primary sources (OpenJDK
JEPs, Oracle docs, vendor docs). All claims are cited inline; see **Sources** at the end.

**Question:** the owner wants to call OS-native libraries (launchd / Windows SCM /
systemd) from a cross-platform Java service-manager library **without shipping compiled
native binaries**, using Java's Foreign Function & Memory (FFM) API. This doc covers FFM
status, per-platform feasibility, and a concrete FFM-vs-subprocess recommendation.

---

## A. FFM API status & Java version requirements

### 1. JEP history — Panama incubator → preview → finalized in JDK 22

The FFM API went through **two incubations and three previews before being finalized in
JDK 22 by JEP 454** ([openjdk.org/jeps/454](https://openjdk.org/jeps/454)):

| JEP | Stage | Delivered in |
|-----|-------|--------------|
| JEP 412 | First incubator | JDK 17 |
| JEP 419 | Second incubator | JDK 18 |
| JEP 424 | Preview | JDK 19 |
| JEP 434 | Second preview | JDK 20 |
| JEP 442 | **Third preview** | **JDK 21 (LTS)** |
| **JEP 454** | **Final / standard** | **JDK 22** |

So **JDK 22 is the first release where FFM is final and needs no `--enable-preview`
flag** (JEP 454). Everything up to and including **JDK 21 LTS** had it as a *preview* API
that requires `--enable-preview` at both compile and run time (JEP 442,
[openjdk.org/jeps/442](https://openjdk.org/jeps/442)). With FFM finalized, OpenJDK's own
position is that "in most cases, there is no longer any reason to use JNI"
([JEP 454](https://openjdk.org/jeps/454)).

### 2. LTS situation & recommended baseline

- **JDK 21 (LTS, Sept 2023):** FFM is **preview only** — requires `--enable-preview`,
  which also makes the whole build/runtime a preview build (a non-starter for a stable,
  redistributable library). ([JEP 442](https://openjdk.org/jeps/442))
- **JDK 22 / 23 / 24:** non-LTS, but FFM is final.
- **JDK 25 (LTS, GA 16 Sept 2025):** confirmed **Long-Term Support**, with **at least
  five years of Oracle Premier Support**. FFM is **final and stable** here (it was
  finalized back in JDK 22; JDK 25 simply ships the mature API).
  ([InfoQ: Java 25 released](https://www.infoq.com/news/2025/09/java25-released/),
  [openjdk.org/projects/jdk/25](https://openjdk.org/projects/jdk/25/))

**Recommendation — minimum baseline: JDK 25 LTS (Java 25).**

Rationale: FFM final without preview flags requires **22+**. The only sane LTS that gives
that is **25**. Picking 25 LTS means: no `--enable-preview`, a supported long-life branch,
and the mature FFM surface (Arena lifetimes, libffi fallback linker, etc. landed across
21→25). The cost is adoption breadth — many shops are still on 17/21. If the owner needs
to support 21 shops, the clean answer is **not** "FFM under `--enable-preview`" (too
fragile for a library) but **subprocess-only on those JVMs**, with FFM as a 25+ opt-in.
Do **not** target 17/21 *with* FFM.

### 3. Key API surface (`java.lang.foreign`)

Core types you'd bind native code with ([JEP 454](https://openjdk.org/jeps/454),
[Oracle FFM guide](https://docs.oracle.com/en/java/javase/25/core/restricted-methods.html)):

- **`Linker`** (`Linker.nativeLinker()`) — produces downcall handles (Java→native) and
  upcall stubs (native→Java) for the platform C ABI.
- **`SymbolLookup`** — resolves a function/global by name to its address
  (`SymbolLookup.libraryLookup("advapi32", arena)`, `Linker.defaultLookup()`).
- **`FunctionDescriptor`** — the C signature (return + argument layouts).
- **`MemorySegment`** — a typed view over native (or heap) memory; also models pointers
  and function pointers.
- **`Arena`** — scoped lifetime/allocation for native memory; closing frees it
  (`try (Arena a = Arena.ofConfined())`).
- **`MemoryLayout` / `StructLayout` / `ValueLayout`** — describe scalar and struct
  layouts (incl. alignment/padding) for marshalling.

**Binding a C function (sketch):**
```java
try (Arena arena = Arena.ofConfined()) {
    Linker linker = Linker.nativeLinker();
    SymbolLookup adv = SymbolLookup.libraryLookup("Advapi32.dll", arena);
    MethodHandle openSCM = linker.downcallHandle(
        adv.find("OpenSCManagerW").orElseThrow(),
        FunctionDescriptor.of(ADDRESS,            // returns SC_HANDLE
                              ADDRESS, ADDRESS, JAVA_INT)); // lpMachine, lpDB, dwAccess
    MemorySegment hScm = (MemorySegment) openSCM.invoke(
        MemorySegment.NULL, MemorySegment.NULL, SC_MANAGER_ALL_ACCESS);
}
```
**Passing a struct:** define a `StructLayout` mirroring the C struct, allocate it in the
Arena (`arena.allocate(layout)`), set fields via `VarHandle`s derived from the layout, and
pass the segment where the C function expects a pointer/by-value struct.

### 4. `jextract` — binding generator

`jextract` mechanically generates FFM Java bindings from C headers (it parses them via the
clang C API) ([dev.java/jextract](https://dev.java/learn/jvm/tools/complementary/jextract/),
[github.com/openjdk/jextract](https://github.com/openjdk/jextract)).

- **It is a separate download, NOT bundled in the JDK.** Pre-built binaries are published
  on [jdk.java.net/jextract](https://jdk.java.net/jextract/); the latest is
  **`25-jextract+1-1` (released 2025-09-25), based on JDK 25**. (Can also be built from
  source against LLVM.)
- **Would it help us?** Yes for the surface-area-heavy targets:
  - **Windows:** point it at `winsvc.h` / `winbase.h` to generate `advapi32` SCM bindings
    (handles the WCHAR structs, `SERVICE_STATUS`, etc. mechanically).
  - **Linux:** point it at `systemd/sd-bus.h` to generate `libsystemd` bindings.
  - Generated code is verbose and ABI-specific; we'd commit a curated subset behind our
    interface, not regenerate at build time. For macOS there's little worth extracting
    (no public job-management header — see B).

### 5. Upcalls (native → Java callbacks)

**FFM supports upcalls** — `Linker.upcallStub(...)` turns a Java `MethodHandle` into a
native function pointer (`MemorySegment`) that C can call back through
([JEP 454](https://openjdk.org/jeps/454)). This is **directly relevant to Windows**:
`StartServiceCtrlDispatcher` / `RegisterServiceCtrlHandlerEx` require a service-main and a
handler callback. FFM can supply those function pointers without JNI. (Lifetime caveat:
the upcall stub must outlive any native use, so bind it to a long-lived `Arena`.)

### 6. Native-access restrictions / integrity-by-default (`--enable-native-access`)

The restricted-methods regime applies to FFM (and JNI) uniformly
([JEP 472](https://openjdk.org/jeps/472),
[Oracle: Restricted Methods, JDK 25](https://docs.oracle.com/en/java/javase/25/core/restricted-methods.html)):

- Calling restricted FFM methods (linking native libs, etc.) from code that wasn't granted
  native access produces a **warning today**.
- `--enable-native-access=<module>|ALL-UNNAMED` grants it; `--illegal-native-access=allow|warn|deny`
  controls the response. **`warn` is the default in JDK 24 and 25.**
- **Direction of travel:** "a future JDK release will throw exceptions by default" — i.e.
  **`deny` becomes the default and `allow` is removed** in a later release
  ([JEP 472](https://openjdk.org/jeps/472),
  [inside.java integrity-by-default](https://inside.java/2025/01/03/evolving-default-integrity/)).

**Implication for us (a library):** native access is granted to the **end application**,
not the library. If we use FFM, the *consuming app's* launch command must pass
`--enable-native-access=...` (and eventually will *have* to, once `deny` is the default).
This is a real adoption tax we push onto downstream users — a strong point in favor of
keeping a subprocess path that needs no such flag. Document the flag requirement loudly.

---

## B. Per-platform native targets via FFM

### Windows — `advapi32.dll` SCM (the strongest FFM case)

**Feasible and genuinely valuable.** The Service Control Manager API
(`OpenSCManagerW`, `CreateServiceW`, `ControlService`, `QueryServiceStatusEx`,
`DeleteService`, `CloseServiceHandle`) is a plain C/Win32 API — exactly what FFM is good at
([MS Learn: OpenSCManager](https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-openscmanagera)).
Gotchas:

- **Wide strings:** use the `...W` (UTF-16LE) entry points. Encode Java strings as
  UTF-16LE into a `MemorySegment` (null-terminated). Avoid the `...A` variants.
- **Struct marshalling:** `SERVICE_STATUS` / `SERVICE_STATUS_PROCESS` etc. need precise
  `StructLayout`s (alignment/padding). `QueryServiceStatusEx` gives **rich structured
  status with no text parsing** — the key win over `sc.exe`.
- **Error handling:** Win32 funcs return BOOL/handle and set thread-local error; call
  `GetLastError` (kernel32) immediately after a failed call and map the code. (FFM
  downcalls don't auto-capture errno/GetLastError unless you add the
  `Linker.Option.captureCallState("GetLastError")` capture option — use it.)
- **Privileges:** opening a writable SCM handle / `CreateService` requires Administrator;
  this is an OS reality regardless of FFM vs `sc.exe`.
- **Upcalls:** the service *host* side (a process that actually *runs as* a service) needs
  `StartServiceCtrlDispatcher` + a handler callback — doable via FFM upcalls (§A.5), but
  see the service-host-shim caveat in §C.

**COM Task Scheduler is a different story.** The Task Scheduler 2.0 API is **COM**
(vtable-based `IUnknown`/`ITaskService`). Raw FFM has **no COM support** — you'd hand-walk
vtables, manage `AddRef`/`Release`, and marshal `BSTR`/`VARIANT` by hand, which is
painful and brittle ([vtable/IUnknown background](https://www.timdbg.com/posts/vtables/),
[Java Code Geeks: Panama FFI examples](https://www.javacodegeeks.com/2025/08/project-panama-native-interfacing-practical-foreign-function-interface-examples.html)).
**For any Task Scheduler need, shell out to `schtasks.exe` or PowerShell** rather than COM
via FFM. (For *services*, stick with the flat advapi32 API.)

### Linux — `sd-bus` in `libsystemd` vs `systemctl`

`sd-bus` (in `libsystemd`) is a flat C D-Bus client API — technically FFM-bindable
([0pointer: sd-bus](https://0pointer.net/blog/the-new-sd-bus-api-of-systemd.html),
[freedesktop sd-bus(3)](https://www.freedesktop.org/software/systemd/man/latest/sd-bus.html)).
But it's a **poor cost/benefit** for us:

- **D-Bus is a message protocol, not simple function calls.** Even via sd-bus you build
  messages, manage a bus connection, handle async replies and reference-counted objects —
  far more FFM surface than the SCM case, with many structs and ownership rules.
- **`libsystemd` may be absent** on non-systemd distros (OpenRC, runit, s6, Alpine's
  default, etc.). FFM-linking a missing `.so` fails at runtime. We'd need fallbacks anyway.
- **`systemctl` is the documented, supported management interface** and covers our needs
  (enable/disable/start/stop/status, `--user` vs system) with stable, parseable output
  (`systemctl show --property=...` gives key=value, no scraping of human text). The native
  `busctl`/sd-bus path buys efficiency we don't need for occasional service ops.

**Verdict: shell out to `systemctl` on Linux.** sd-bus via FFM is feasible but not worth
the binding burden, the libsystemd-presence risk, or the per-distro testing.

### macOS — launchd

**There is no stable public C API for launchd job management.** Apple **deprecated the
ServiceManagement C functions (`SMJobSubmit`/`SMJobRemove`/`SMJobCopyDictionary`/…) back in
OS X 10.10**, and the old `launch.h` SPI is private/deprecated; **`launchctl` is the
supported interface** ([Apple Dev Forums on SMJob deprecation](https://developer.apple.com/forums/thread/51395),
[launchd-dev: replacements for SMJobSubmit](https://lists.macosforge.org/pipermail/launchd-dev/2016-October/001229.html)).
The modern `SMAppService` (macOS 13+) only registers/unregisters *the calling app's own*
bundled agents/daemons (Objective-C/Swift framework, app-bundle-scoped) — it is **not** a
general job-management API and is unusable from a plain Java library.

**Verdict: macOS stays subprocess (`launchctl`) — confirmed.** There is nothing useful to
bind via FFM here; writing the `.plist` + driving `launchctl` (bootstrap/bootout/kickstart/
print) as already planned in CLAUDE.md is the correct and only supported path.

---

## C. Strategic recommendation

### FFM vs subprocess, per platform

| Platform | Mechanism | Recommendation |
|----------|-----------|----------------|
| **macOS** | `launchctl` subprocess | **Subprocess only.** No public launchd C API exists; launchctl is the supported interface. FFM adds nothing. |
| **Linux** | `systemctl` subprocess | **Subprocess (default).** sd-bus/libsystemd via FFM is feasible but high-effort, fragile (D-Bus message plumbing), and libsystemd may be absent on non-systemd distros. `systemctl show` gives structured output. Optional far-future FFM/sd-bus path only if a measured need appears. |
| **Windows (services)** | `advapi32` SCM via **FFM** | **FFM — this is where it pays.** Flat C API, rich structured status (`QueryServiceStatusEx`) with **no text parsing**, proper error codes, no `sc.exe` output-format drift. UTF-16 + struct marshalling are routine for FFM. |
| **Windows (scheduled tasks)** | `schtasks`/PowerShell subprocess | **Subprocess.** Task Scheduler is COM; raw FFM has no COM support (hand-rolled vtables = brittle). Not worth it. |

**This is a hybrid strategy, and it's the right one.** Use FFM where the native API is a
flat C ABI that materially beats CLI parsing (**Windows SCM**); use subprocess CLIs where
they are the *documented, supported* management path and/or where the native route is COM
or D-Bus heavy (**macOS launchctl, Linux systemctl, Windows schtasks**).

### On the "no compiled binaries" goal

FFM **achieves the owner's goal for *calling* existing OS libraries** — we link
`advapi32.dll` (already on every Windows box) at runtime with zero shipped native code.
Good.

**But flag the Windows service-host-shim problem.** Managing services (create/start/stop/
query) via SCM is fine from a normal process. **Running an arbitrary user program *as* a
Windows service** is different: a Windows service must be a process that calls
`StartServiceCtrlDispatcher` and answers SCM control callbacks. Options:
1. A **pure-Java service host** (a Java main that does the dispatcher + handler via FFM
   upcalls and then launches/supervises the target) — keeps "no native binaries" but is
   real work and must be rock-solid.
2. A **tiny native shim exe** (e.g. WinSW/NSSM-style) — violates "no compiled binaries."

If the library's scope is **"manage services that already exist / wrap a command,"** the
pure-Java-host (FFM upcalls) route preserves the no-binaries goal. If we'd otherwise pull
in a prebuilt shim, that's the line where the goal breaks — **call this out to the owner
before committing to Windows "run-any-exe-as-a-service" scope.** (macOS/Linux don't have
this problem: launchd/systemd host the process for you from the unit/plist.)

### Testing implication

**FFM downcalls are hard to unit-test off-platform** (you can't link `advapi32` on a Linux
CI box; calls hit real OS state). Subprocess layers, by contrast, are trivial to stub (a
fake process runner returning canned output) — which is exactly the pattern CLAUDE.md
already proposes for `Launchctl`.

**Therefore: keep ALL native access behind interfaces**, regardless of mechanism. Mirror
the existing `Launchctl` interface design with, e.g., `WindowsServiceControl` (FFM impl +
stub) and `Systemctl` (subprocess impl + stub). The facade calls interfaces only; the FFM
and subprocess details live behind them and never leak. This keeps the cross-platform code
testable on any CI runner and lets us swap a subprocess fallback for FFM (or vice versa)
per platform without touching the facade.

---

## Sources

- JEP 454 — Foreign Function & Memory API (final, JDK 22): https://openjdk.org/jeps/454
- JEP 442 — FFM Third Preview (JDK 21): https://openjdk.org/jeps/442
- JEP 424 — FFM Preview (JDK 19): https://openjdk.org/jeps/424
- JEP 472 — Prepare to Restrict the Use of JNI: https://openjdk.org/jeps/472
- Oracle — Restricted Methods (JDK 25), `--enable-native-access`/`--illegal-native-access`:
  https://docs.oracle.com/en/java/javase/25/core/restricted-methods.html
- inside.java — Evolving default integrity (deny becomes default):
  https://inside.java/2025/01/03/evolving-default-integrity/
- inside.java — JDK 24 prepares restricted native access:
  https://inside.java/2024/12/09/quality-heads-up/
- InfoQ — Java 25 (LTS) released: https://www.infoq.com/news/2025/09/java25-released/
- OpenJDK — JDK 25 project page: https://openjdk.org/projects/jdk/25/
- jextract — project repo: https://github.com/openjdk/jextract
- jextract — early-access builds (25-jextract+1-1, 2025-09-25): https://jdk.java.net/jextract/
- dev.java — jextract tool guide: https://dev.java/learn/jvm/tools/complementary/jextract/
- Microsoft Learn — OpenSCManager (winsvc.h):
  https://learn.microsoft.com/en-us/windows/win32/api/winsvc/nf-winsvc-openscmanagera
- Java Code Geeks — Project Panama FFI examples (Aug 2025):
  https://www.javacodegeeks.com/2025/08/project-panama-native-interfacing-practical-foreign-function-interface-examples.html
- TimDbg — vtables / IUnknown (why raw COM is hard): https://www.timdbg.com/posts/vtables/
- 0pointer (Lennart Poettering) — the new sd-bus API of systemd:
  https://0pointer.net/blog/the-new-sd-bus-api-of-systemd.html
- freedesktop — sd-bus(3) man page:
  https://www.freedesktop.org/software/systemd/man/latest/sd-bus.html
- Apple Developer Forums — launchd & SMJobSubmit deprecation concerns:
  https://developer.apple.com/forums/thread/51395
- launchd-dev — replacements for deprecated SMJobSubmit/SMJobRemove:
  https://lists.macosforge.org/pipermail/launchd-dev/2016-October/001229.html
