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
_TBD: what the reference apps are missing that this app must do._

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
1. Which features are the reference apps missing?
2. What does "local map generation" mean: on the phone itself, or on infrastructure you control (your own server/desktop) instead of third-party tile providers?
3. What does "distributed" mean: (a) multi-device sync for one user, (b) multi-user collaboration like CalTopo shared maps, (c) device-to-device with no internet (e.g. a group in the backcountry), or all three?
4. Region: US-only (good public data) or global?
5. Open source, personal project, or commercial? This affects the choice of data sources and licenses (OSM is ODbL; some DEM sources restrict use).
6. App name and package ID.
