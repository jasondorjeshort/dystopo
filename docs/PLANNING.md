# Planning

## Purpose
A topographic map and backcountry navigation system. Reference apps: BackCountry Navigator, Gaia GPS, CalTopo (app + web).
Android first. Designed to grow into a full-stack system: a sync server, then desktop and iOS clients.

## Hard requirements
- **Offline-first.** Everything works with no connection; going online only adds things (sync, fresh tiles, sharing).
- **Distributed communication.** All modules (phone, server, future desktop and iOS clients) exchange data through one well-defined sync protocol. The server is a peer that is always on, not the single source of truth.
- **Don't preclude:** local map generation, a full OSM map with routing, iOS/desktop clients.
- **Agent-operable.** AI agents do most or all of the coding and debugging. Every feature must be buildable, runnable, observable, and testable from a command line with no human at the device. See [Agent-driven development](#agent-driven-development).

## Differentiating features
The core problem: each reference app has features the others lack. The goal is the union, without their weaknesses.

| App | Strength to keep | Gap to fix |
|---|---|---|
| CalTopo | Generates its own maps from national elevation data (NED/3DEP) + OSM. Strong planning tools on the web. | US-only. Its maps can't be used in other navigation apps or tools. |
| BackCountry Navigator | Very easy offline map downloads. Long-time daily driver. | Very dated interface. Limited, stale map sources (no OSM). Missing simple features. |
| Gaia GPS | _TBD_ | _TBD_ |

### Product principles that follow
1. **Global coverage.** Use the best available data for each region: global baselines (Copernicus GLO-30 DEM, OSM) layered with higher-resolution national sources where they exist (USGS 3DEP, and similar datasets in other countries).
2. **Our maps are open and portable.** Anything we generate can be exported or served in standard formats (PMTiles, MBTiles, XYZ/TileJSON URLs, GPX/KML/GeoJSON) so other apps and tools can use it. Interoperability is a feature, not an afterthought.
3. **Offline downloads as easy as BackCountry Navigator.** Pick an area and zoom range, see the size estimate, download, done.
4. **Many map sources, kept fresh.** A pluggable map-source catalog: built-in sources, user-added XYZ/WMS/WMTS sources, and regularly regenerated OSM-based maps.
5. **Modern UI.**

## Architecture goal: rapid feature growth, small footprint where it counts
We expect features to multiply quickly. Total code complexity can grow large, but **the Android app must stay small in CPU, memory, battery, and APK size.**

- **Small core, feature modules.** The Android app has a lean core (map display, location, tracks, offline storage, sync). Features are separate Gradle modules behind well-defined interfaces, so adding one doesn't bloat or destabilize the rest. Rarely used ones can become Play Feature Delivery on-demand modules.
- **Heavy lifting goes where resources are.** Bulk processing (OSM tile builds, large DEM processing, global datasets) runs on the server or desktop. The phone does only what has to happen locally or offline.
- **Different budgets per module.** Server and desktop modules can trade footprint for features. Android modules can't.
- **Budgets are enforced, not hoped for.** APK size, cold-start time, steady-state memory, and battery cost of GPS tracking are measured in automated checks (Macrobenchmark, APK size checks, memory stats from the debug server). A regression fails the build, so agents see it immediately.
- **Lazy by default.** Load data, map sources, and features only when used. Stream tiles from disk instead of holding them in memory.

## Exploration tracks
| Track | Question | Status |
|---|---|---|
| Local map generation | Can we produce topo maps (contours, hillshade, slope shading, base features) from raw data on the device or the user's own machine instead of relying on third-party tile servers? | Explore first; may abandon |
| OSM map + routing | Full offline vector map and trail/road routing from OpenStreetMap | Future |
| Multi-platform | Android → server → desktop → iOS | Architecture must allow it from day 1 |

## Proposed architecture

### Code sharing: Kotlin Multiplatform (KMP)
- One `shared` module holds the domain model, sync engine, storage, and geo math. It compiles for Android, JVM (server + desktop), and iOS.
- Platform modules: `androidApp` (Jetpack Compose), `server` (Ktor), later `desktopApp` and `iosApp`.
- **Constraint:** code in `shared` must be pure Kotlin. Java libraries (JTS, GraphHopper, Planetiler…) work on Android, server, and desktop, but **not iOS**. Wherever we use one, it goes behind an interface in `shared` with JVM-only implementations.

### Map rendering: MapLibre Native (proposed)
- Open source, with Android/iOS SDKs and a web counterpart (MapLibre GL JS) for a CalTopo-style site.
- Renders vector tiles, raster tiles, hillshade from DEM, and 3D terrain.
- Alternative: Mapsforge, which is pure Java and fully offline but Android/desktop only, with weaker styling. Not proposed.

### Map data formats
- Offline packs: **PMTiles** (single file, works over HTTP range requests and from local disk). MBTiles as a fallback.
- Vectors: Mapbox Vector Tiles (MVT). Elevation: terrain-RGB / Terrarium DEM tiles.
- User data import/export: GPX, KML/KMZ, GeoJSON.

### Local map generation (feasibility notes)
| Layer | Source | On-device feasibility |
|---|---|---|
| Hillshade, slope-angle shading | DEM tiles | **High.** Per-tile math, GPU-friendly, MapLibre does hillshade natively |
| Contour lines | DEM tiles | **Good.** Marching squares per tile, parallel across cores |
| Base map (roads, trails, water, land use) | OSM extracts | **Region-sized only.** Do it on the server/desktop with Planetiler (Java), then ship PMTiles to the device |
| Land ownership, other overlays | Public datasets (e.g. PAD-US) | Server-side preprocessing |

DEM sources to evaluate: USGS 3DEP (US, high resolution), Copernicus GLO-30 (global), AWS Terrain Tiles.

### Offline-first data and sync
- Local SQLite (SQLDelight, KMP-compatible) is the source of truth on every device.
- Every entity (waypoint, track, route, map, folder, share) has a UUID and is never identified by a server-assigned ID.
- Changes are recorded as an **operation log** with hybrid logical clock (HLC) timestamps. Peers exchange the ops they're missing. Conflicts resolve per field (last-writer-wins, plus CRDT types where merging matters, e.g. track point lists).
- Transport is pluggable: HTTPS/WebSocket to the server first, possibly device-to-device later (Wi-Fi Direct / Bluetooth / LAN) with the same protocol.
- Wire format: versioned schema (kotlinx.serialization with protobuf or CBOR) so old clients keep working.
- Candidates to evaluate instead of building our own: Automerge, Electric/PowerSync-style sync, Couchbase Lite.

### Routing (future)
- BRouter (Java, offline, hiking/bike profiles, already runs on Android) or GraphHopper (Java, offline-capable). Both are JVM-only, so they sit behind a `Router` interface.

### Concurrency
- Kotlin coroutines + Flow throughout.
- CPU-heavy work (DEM decoding, contour generation, tile rendering) runs on `Dispatchers.Default`, which sizes to the device's cores. Large jobs are split per tile so they parallelize. This is where multi-core and hyperthreaded CPUs pay off.
- I/O (disk, network, database) runs on `Dispatchers.IO`. GPS tracking runs in a foreground service.

## Agent-driven development
Agents can't tap a screen or watch a map scroll, so every feature also needs an interface an agent can drive.

### Principles
1. **Logic lives in `shared`, not the UI.** Anything in the shared module can be tested on the JVM in seconds, with no emulator. The UI layer stays thin.
2. **Every feature ships with its debug hooks and tests.** A feature isn't done until an agent can trigger it, observe the result, and check it automatically.
3. **Everything is deterministic and injectable.** Clock, GPS location, network state, file system, and random seeds are interfaces that tests can replace with fakes.
4. **Output an agent can read.** Structured JSON logs and state dumps, plus PNGs for anything visual (agents can view images).
5. **Debug tooling ships only in debug builds.** It's compiled out of release builds.

### Tooling layers
| Layer | What it is | Used for |
|---|---|---|
| JVM unit tests | `shared` module tests, run with Gradle | Geo math, DEM decoding, contour generation, sync logic, storage |
| Dev CLI (`devtool`) | JVM command-line app that calls into `shared` | `devtool contours --bbox … --out x.png`, `devtool sync-sim --peers 3`, inspecting PMTiles/GPX files |
| Golden-image tests | Rendered tiles and screens compared against approved PNGs | Map rendering, hillshade, contours, UI screens (Roborazzi/Paparazzi run Compose screenshots on the JVM) |
| Sync simulator | Many in-process peers over a fake network with latency, partitions, and reordering, driven by a seed | Proving offline/online convergence; any failure replays exactly from its seed |
| In-app debug server | Debug builds run a small HTTP/JSON server, reached via `adb forward` | Query state, invoke actions, inject GPS fixes and tracks, toggle offline mode, load fixtures, dump the DB, fetch logs, take screenshots, read map camera/layers |
| UI end-to-end | Maestro flows (YAML) or UI Automator, run against the emulator | Critical user journeys |
| Server debug endpoints | Same debug API on the Ktor server; local multi-peer setup via Docker Compose | Server-side state and sync inspection |

### Command-line environment
- Gradle wrapper builds everything; no Android Studio required for agents (it's still useful for humans).
- Android SDK command-line tools + `adb` + headless emulator (`emulator -no-window`).
- Single entry points, e.g. `./gradlew check` runs everything that doesn't need a device; one script boots the emulator, installs the app, and opens the debug port.
- A `CLAUDE.md` at the repo root documents these commands and conventions for agents.

## Licensing
The project will be fully open source. Code and map content may need different licenses. _Not decided yet; notes below._

### Code: GPL family
| Option | Effect | Fit |
|---|---|---|
| GPLv3-or-later | Anyone distributing modified apps must release their source | Default candidate for the apps |
| AGPLv3 | Also covers running modified code as a network service | Candidate for the server, so hosted forks must share changes |
| LGPLv3 | Others can use the library in closed apps; changes to the library itself must be shared | Candidate for the `shared` core if we want wide reuse |

- Use **v3**, not v2: Apache-2.0 dependencies (Planetiler, GraphHopper) are compatible with GPLv3 only.
- Expected dependencies are compatible with GPLv3: MapLibre (BSD-2), Planetiler (Apache-2.0), BRouter (MIT), GraphHopper (Apache-2.0), JTS (EPL-2.0/EDL dual license, use EDL).
- **iOS App Store vs GPL:** Apple's terms conflict with GPL distribution of code contributed by others. A sole copyright holder can grant Apple an exception or dual-license. Decide on a contribution policy (CLA or a GPL app-store exception) **before** taking outside contributions.
- F-Droid distribution works with any of these.

### Map content
| Source | License | Consequence |
|---|---|---|
| OpenStreetMap | ODbL 1.0 | Vector tiles that contain OSM data are a derived database, so they **must stay ODbL** (share-alike). Rendered raster images are a "produced work" and can use any license, with OSM attribution. |
| USGS 3DEP / NED, SRTM | Public domain | No restrictions |
| Copernicus GLO-30 DEM | Free, attribution required | Fine for generated contours/hillshade |
| User-added third-party sources | Per provider | Many forbid caching or redistribution; user-added sources stay on the user's device and are never republished |

- The likely result: **ODbL for vector tiles** (forced by OSM), and **CC BY-SA 4.0** (the share-alike, GPL-like content license) or CC BY 4.0 for raster tiles and non-OSM layers.
- Each tile set records its sources and attribution in its metadata, and the app shows attribution for every visible layer.

## Tech stack
| Area | Choice | Status |
|---|---|---|
| Language | Kotlin (Java libraries where useful, JVM targets only) | Decided |
| Code sharing | Kotlin Multiplatform | Proposed |
| Android UI | Jetpack Compose (Compose Multiplatform later?) | Proposed |
| Map renderer | MapLibre Native | Proposed |
| Local DB | SQLDelight (SQLite) | Proposed |
| Sync | Op log + HLC, pluggable transport | Proposed; evaluate libraries first |
| Server | Ktor (Kotlin, JVM) | Proposed |
| Tile generation | Planetiler (server/desktop) + on-device DEM processing | To explore |
| Routing | BRouter or GraphHopper | Future |
| Build | Gradle (Kotlin DSL) | Proposed |
| Min SDK | _TBD_ | Open |

## Dev environment setup
- [x] Git
- [ ] Android Studio (bundles JDK, Android SDK, emulator)
- [ ] Emulator or physical device with USB debugging

## Open questions
1. Which "simple features" is BackCountry Navigator missing? What does each reference app do that must be kept?
2. What does "local map generation" mean: on the phone itself, or on infrastructure you control (your own server/desktop) instead of third-party tile providers?
3. What does "distributed" mean: (a) multi-device sync for one user, (b) multi-user collaboration like CalTopo shared maps, (c) device-to-device with no internet (e.g. a group in the backcountry), or all three?
4. ~~Region~~ Decided: global, using the best available data for each region.
5. ~~Open source?~~ Decided: fully open source. Still open: GPL vs AGPL vs LGPL per module, and CC BY-SA vs CC BY for non-OSM tiles. See [Licensing](#licensing).
6. App name and package ID.
