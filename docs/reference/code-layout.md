---
verified: 2026-10-12
source: moved verbatim from CLAUDE.md in T84 (text as of 0547203); observed in the tasks named in each paragraph
---

# Code layout: modules and where things live

The module map with the detail that agents need when they edit a module. The system map is `../ARCHITECTURE.md`; the Kotlin conventions are `.claude/rules/kotlin.md`.

## Modules

- `shared/` — Kotlin Multiplatform core (JVM and Android targets; iOS later). `commonMain` must stay pure Kotlin: **no `java.*` imports**, so it compiles for every target. Platform-specific pieces (image decoding, HTTP, zlib) are injected through small interfaces such as `ByteRangeSource` and `Decompressor`. T80: the elevation readers live in `shared/.../sources/elevation/` (3DEP, lidar, Copernicus, the national sources, geoids, `BestSource`, `RemoteTiff`, `Lerc2`); their platform pieces are `expect`/`actual` in `Platform.kt` (`SourceFiles` file cache, `IoPool`, `parallelForEach` on a fixed 16-thread pool, zlib, the default HTTP client) with the JVM and Android actuals in `src/jvmCommonMain` and the HTTP client in `jvmMain`/`androidMain`. `SourceIo` holds the cache root (`Caches.root` on the server sets it), the replaceable HTTP client, the `beforeNetwork` hook (`Http.beforeNetwork`), and the phone's pools (`tiffPool`, `decodedBlocks`; null on the server). serverlib keeps the old class names as type aliases (`ElevationSources.kt`); for a nested type such as `ElevationSource.Result` import the shared class.
- `devtool/` — JVM command-line tool that runs shared-core features and prints JSON reports. JVM-only code (image I/O, PNG rendering) lives here.
- `serverlib/` and `server/` — the server pieces (`LiveTiles`, `BuildRoutes`, `ServeStatic`, `SyncRoutes`, `PackCatalog`, the elevation sources under their old names; package `...dystopo.server`) shared by `devtool serve` and the standalone server (`./gradlew :server:installDist`, config file, `/catalog`, `/healthz`, `/metrics`, token, body and time limits, a rotating JSON log, Dockerfile): see `docs/SERVER.md`, whose Operations section covers running it for real; `python -I tools/server-probe.py` (73 probes, must leave the server alive) and `tools/server-soak.ps1` (hours under load, SVG chart) are the T82 checks, and the nightly workflow runs a 20-minute soak.
- `tilebuild/` — JVM-only build tool: OSM extract → PMTiles via Planetiler (heavy dependencies stay here, out of devtool). `./gradlew :tilebuild:run --args="--extract liechtenstein --out out/liechtenstein.pmtiles"`; first run downloads ~1.45 GB of aux data into the cache.
- `tools/` — repo tooling: `PrivacyCheck.java` and the git hooks in `tools/git-hooks`.
- `areas.json` — public named test areas. Personal areas go in `devtool.local.json` (gitignored, same format, main checkout only).
