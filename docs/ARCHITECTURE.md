# Dystopo architecture

A map of the system as built, with pointers to where each piece is documented in more depth. Design history and
measurements are in [PLANNING.md](PLANNING.md) (the dated development log at the end records every merged task);
the task board with ownership and follow-ups is [TASKS.md](TASKS.md); the server is in [SERVER.md](SERVER.md);
commands, endpoints and the agent workflow are in [CLAUDE.md](../CLAUDE.md); one line per merged task is in
[CHANGELOG.md](../CHANGELOG.md). Releases: tag-driven drafts from `.github/workflows/release.yml`, verified from the draft and published by hand per the Publishing paragraph in CLAUDE.md; the first public build was v0.1.2-alpha (2026-10-10).

## Modules

| Module | What it is | Runs on |
|---|---|---|
| `shared/` | Kotlin Multiplatform core. `commonMain` is pure Kotlin (no `java.*`): geo math, elevation compositing, contours, hillshade and slope, PMTiles and MVT read/write, the declarative map style and its MapLibre translation, GPX and the track model, the SQLDelight track store, the sync log and merge, search and routing indexes, PNG decode, units. Platform pieces (zlib, image codecs, HTTP ranges, text measuring) are injected through small interfaces. | JVM, Android (iOS later) |
| `serverlib/` | The server side shared by the devtool and the standalone server: elevation sources (USGS 3DEP 10 m and 1 m, Copernicus GLO-30, swissALTI3D, Kartverket, BEV, NRCan HRDEM, LINZ, with a LERC decoder for LINZ's tiles), geoids, live tiles, pack builds (DEM, OpenFreeMap, Overpass + tilebuild outdoors, Geofabrik extracts), static files, the pack catalog, the sync peer. Package `io.github.jasondorjeshort.dystopo.server`. | JVM |
| `devtool/` | Command-line tool and JSON reports for every feature (`render`, `compare`, `tiles`, `pmtiles`, `mvt`, `tour`, `gpx`, `route`, `search`, `sprite`, `contours bench`, `sync`, `sync-sim`, `serve`). Also hosts the browser demo in `serve`. | JVM |
| `server/` | The deployable always-on peer: config file, JSON logs, `/healthz`, catalog, bearer token, CORS, Dockerfile. See SERVER.md. | JVM, Docker |
| `tilebuild/` | Planetiler with the outdoors profile (OpenMapTiles 3.16 plus trails, cliffs, wilderness, climbing routes); XML-to-PBF for Overpass answers; water polygon indexing. The only module where LGPL dependencies are tolerated. | JVM |
| `androidApp/` | Compose + MapLibre Native. A loopback HTTP server feeds MapLibre from packs and generates contour, shade and cliff tiles on the device; the same server exposes the debug routes in debug builds. | Android 8+ |
| `tools/` | Repo tooling: privacy hooks, demo page and checkers, emulator and release scripts, CI helpers, notices generator. | host |

## Data flow

1. **Elevation.** Sources are read by HTTP range requests straight from their public cloud files (COGs, or fixed
   blocks where only a coverage service exists), cached under `DYSTOPO_CACHE`. Every source converts to **EGM2008**
   through geoid grids from the PROJ CDN, then `best` composites them (Copernicus under the national sources under
   the US sources) with 300 m feathering. Output is Terrarium PNG tiles, in region packs (PMTiles) or live.
2. **Map data.** OpenStreetMap through three paths: a Planetiler build with the outdoors profile (from a Geofabrik
   extract or an Overpass box), OpenFreeMap tiles as the world-wide base, or an existing pack. Vector packs are
   PMTiles with the OpenMapTiles schema plus the outdoors layers; `dystopo:profile` in the metadata says which.
3. **Packs.** A region pack is `<name>-dem.pmtiles` plus `<name>-outdoors.pmtiles`. Beside them live derived files
   the app builds on first use: `<name>-search.idx`, `<name>-outdoors-graph.idx`, the contour cache. The server's
   `/catalog` lists packs with content hashes; the phone verifies every download.
4. **Rendering.** The one declarative style (`shared/.../style/DefaultOsmStyle`) drives the desktop PNG renderer,
   the MapLibre style for the phone and browser, the sprite sheet and the marker table test. Contours, hillshade,
   slope bands and cliff ticks are generated from the DEM on whichever side renders.
5. **User data.** Tracks, waypoints, routes and settings are an append-only operation log with hybrid logical
   clocks; the SQLite tables are a materialised view. Sync is a merge of logs: through a `.dsync` file, the server
   peer over HTTP (paged), or another phone, on the same network or through the server's relay, end to end
   encrypted under a 16-character share code (T72). The merge is deterministic
   and the simulator proves convergence under partitions, reordering, crashes and clock skew.

## Formats we own

| Format | Where | Notes |
|---|---|---|
| Terrarium DEM tiles | `shared/.../dem/Terrarium.kt` | RGB PNG, 1/256 m, NaN as no-data |
| PMTiles v3 reader/writer | `shared/.../pmtiles/` | pure Kotlin, leaf directories, extract and dedup |
| MVT encoder/decoder | `shared/.../mvt/` | hand-written protobuf |
| GPX 1.1 with `dystopo:id` | `shared/.../gpx/` | hand-written XML, extensions preserved |
| Search index `*-search.idx` | `shared/.../search/` | versioned, checksummed, pack fingerprint |
| Routing graph `*-graph.idx` | `shared/.../routing/` | `DYSG` v2, trail names, both pack fingerprints |
| Sync bundle `DSYNC` | `shared/.../sync/` | v1 whole, v2 paged; gzip; one exception type on bad input |
| Encrypted frame `DSEAL`, relay reply `DRLY` | `shared/.../sync/EncryptedBundle.kt`, `sync/crypto/` | ChaCha20-Poly1305 under HKDF(share code, per-frame salt); LAN link and server relay (T72) |
| Track database | `shared/src/commonMain/sqldelight/` | schema versions frozen under `databases/`, migrations verified by a JVM test |
| Pack catalog JSON | `serverlib/.../PackCatalog.kt` | stable id, per-file SHA-256, pack hash |

## How things are tested

Every feature has three surfaces: unit tests in `shared` (offline, fast; goldens for rendering), a devtool command
that prints JSON or PNG, and a debug route on the phone's loopback server (debug builds only, behind a per-launch
token). On top of that run the sweeps: the world tour (every area in `areas.json` with plausibility checks), the
browser tour (Playwright against the static page and against `serve`), the device tour (`tools/android-check.py`
against a headless emulator, including a text-drawn check and a settled-screenshot check), the loopback probe,
the release smoke tour through a deep link, and the sync simulator. CI runs the JVM side on every push.

## Where each topic is documented

- Commands and endpoints, agent workflow, privacy and licence rules: `CLAUDE.md`.
- Design decisions and the dated log of every task with measurements: `docs/PLANNING.md`.
- Task board, ownership, done criteria, follow-ups: `docs/TASKS.md`.
- Server deployment, config keys, catalog, token, Docker: `docs/SERVER.md`.
- Outdoors profile layers and fields: `tilebuild/PROFILE.md`.
- Data sources, licences and attribution: `shared/src/commonMain/resources/sources.json` (the registry).
- Third-party notices: `NOTICES.md` (generated by `tools/notices.py`).
- Contributing and the human-readable workflow: `CONTRIBUTING.md`.
- Route-level detail: the KDoc at the top of each `*Routes.kt` file documents every debug route it serves.
