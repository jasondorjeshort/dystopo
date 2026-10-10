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
6. **The user controls how the map is drawn.** Contour interval, units, shading, slope-angle thresholds, which layers show, and **label/text size** are settings. Most map apps draw text far too small on average phone screens. Default to larger, readable labels, scaled to screen size and density, with a user text-scale setting.

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
| Local map generation | Can we produce topo maps (contours, hillshade, slope shading, base features) from raw data on the device or the user's own machine instead of relying on third-party tile servers? | **Elevation side proven on desktop** (see Phase 0 log). OSM base map is next. |
| OSM map + routing | Full offline vector map and trail/road routing from OpenStreetMap | Base map: starting (tasks T5, T6). Routing: future |
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

### Raw data + on-device rendering vs downloaded tiles
Rough estimates for one 1°×1° cell (~9,000 km² at 40°N), to be measured in phase 0:

| What's stored offline | Size per 1°×1° | Device CPU when viewing | Restylable on device? |
|---|---|---|---|
| Raster topo tiles to z16 (CalTopo/BCN style) | ~1.5–2.5 GB | Almost none (decode images) | No |
| Raster topo tiles to z15 | ~0.4–0.6 GB | Almost none | No |
| OSM vector tiles (PMTiles) | ~20–100 MB | GPU rendering, low CPU | Yes |
| DEM, 30 m (Copernicus GLO-30), compressed | ~15–30 MB | See below | Yes |
| DEM, 10 m (USGS 3DEP 1/3″), compressed | ~100–250 MB | See below | Yes |
| DEM, 1 m lidar | Several GB | See below | Yes; small areas only |
| Raw OSM extract (.osm.pbf) | Similar to vector tiles | **Not renderable directly**: needs preprocessing that is heavy in RAM | n/a |

**Elevation: generate on the fly. Feasible and cheap.**
- Hillshade and slope shading: a small per-pixel kernel. MapLibre already does hillshade on the GPU from DEM tiles. Effectively free.
- Contours: marching squares per tile, ~1–10 ms per 256–512 px tile on one phone core, parallel across cores. maplibre-contour does this in browsers today.
- Memory: a 512×512 16-bit DEM tile is 512 KB. A 50-tile LRU cache is ~25 MB.
- Generated contour tiles are cached on disk, so CPU is spent once per area, not on every pan.
- The DEM needs zoomed-out overview levels (a pyramid) so low-zoom contours stay smooth and fast.
- **Product win:** contour interval, units (ft/m), shading, and slope-angle thresholds become user settings rather than baked into images.

**OSM: download vector tiles, don't process raw OSM on the phone.**
- Raw OSM has no spatial index. Turning it into something renderable means resolving every node location, which takes lots of RAM and minutes to hours. Fine on desktop/server (Planetiler), impractical on a phone beyond a county-sized area.
- Vector tiles are already "data rendered on device": MapLibre styles them on the GPU at view time. OsmAnd, Organic Maps, and Mapsforge-based apps have used this model on phones for over a decade.
- Possible later experiment: on-device conversion of small raw extracts.

**How offline navigation apps do it.** OsmAnd (`.obf`), Organic Maps (`.mwm`), MapFactor Navigator, and Mapsforge-based apps all use the same model: a server converts OSM once into **one compact file per country/state**, and the phone renders and routes from it. Each file holds:
- Vector geometry with a spatial index and pre-simplified versions for zoomed-out views.
- A **routing graph** (roads/paths as a network with costs) for on-device route finding.
- A search index (place names, addresses).
"Very simple map" apps draw only a subset (roads, water, places) with minimal styling, which keeps files small and rendering cheap.

**Our equivalent: region packs.**
- One pack per country/state (matching standard extract boundaries, e.g. Geofabrik), built by Planetiler + routing tools on desktop/server/peers.
- A pack contains: OSM vector tiles (PMTiles), a routing graph (format depends on BRouter vs GraphHopper), a search index, and the DEM pyramid. Each part is optional and separately versioned.
- **Two ways to download:** whole region (simple, like Navigator) or a drawn area (like BackCountry Navigator). Area downloads are cut from region packs; PMTiles supports extracting a bounding box by HTTP range requests.
- Map detail is a style choice, not a data choice: the same pack can be drawn as a simple navigation map or a detailed topo map.

**Decision (proposed): hybrid.**
- Offline packs contain **DEM (as a pyramid) + OSM vector tiles + overlays**, not rendered rasters.
- Hillshade, slope shading, and contours are generated on device and cached.
- The same packs are what peers share: small, content-addressable, and style-independent.
- Raster exports (PNG/MBTiles/XYZ for other apps) are rendered from these on desktop/server, or on device on request.

### Elevation compositing: seamless multi-source DEM (key differentiator)
Source quality varies hugely: USGS 3DEP (1 m lidar in much of the US, 10 m everywhere in the US) is far better than global sources (Copernicus/SRTM, ~30 m). Some other countries have national lidar too (e.g. Switzerland, Austria, Norway, UK, NZ). **No existing app moves smoothly between sources.** That's a core feature to build.

What "smooth" requires:
- **A source catalog.** Each source has a coverage footprint (polygon or tile mask; 3DEP 1 m is patchy), resolution, accuracy, capture date, vertical datum, and license/attribution.
- **Best available per location.** For any area and zoom, use the highest-ranked source that covers each cell. Don't fetch 1 m data for a zoom-10 view.
- **Common vertical datum.** Sources use different datums (US NAVD88, EGM96, EGM2008, ellipsoid heights). Unconverted, they disagree by metres and leave a step at every boundary. Convert everything to one datum before blending.
- **Feathered seams.** Near a coverage edge, blend the two sources over a transition band (weight by distance to the edge) so there's no cliff or kink in the hillshade or contours.
- **Void filling.** Holes in a high-resolution source (water, data gaps) are filled from the next source down.
- **Provenance.** Output records which sources contributed, for attribution and so the app can show where high-resolution data exists.
- **Where it runs.** Compositing runs on the server/desktop when building region packs, so phones get one seamless DEM pyramid. The same `shared` code can composite on device when a user has downloaded raw sources.

Status: built in `devtool` as of 2026-10-09 (details in the Phase 0 log). Sources: `copernicus` (GLO-30, global), `3dep` (USGS 10 m), `3dep-1m` (USGS lidar, found through the TNM API), and `best`, which layers them with feathered edges in a common EGM2008 datum. `terrarium` (Mapzen's frozen 2017 mosaic) is kept only for comparison. Still missing: the source catalog with coverage footprints and dates, provenance in outputs, and per-tile compositing with halos.

### Local map generation (feasibility notes)
| Layer | Source | On-device feasibility |
|---|---|---|
| Hillshade, slope-angle shading | DEM tiles | **High.** Per-tile math, GPU-friendly, MapLibre does hillshade natively |
| Contour lines | DEM tiles | **Good.** Marching squares per tile, parallel across cores |
| Base map (roads, trails, water, land use) | OSM extracts | **Region-sized only.** Do it on the server/desktop with Planetiler (Java), then ship PMTiles to the device |
| Land ownership, other overlays | Public datasets (e.g. PAD-US) | Server-side preprocessing |

DEM sources in use: USGS 3DEP 1/3″ and 1 m lidar (US), Copernicus GLO-30 (global). Candidates to add as more layers: national bare-earth lidar outside the US (Canada HRDEM, swissALTI3D, Austria, Norway, UK, NZ). FABDEM (bare-earth global) is CC BY-NC-SA, which conflicts with our licensing; don't use it.

### Offline-first data and sync
- Local SQLite (SQLDelight, KMP-compatible) is the source of truth on every device.
- Every entity (waypoint, track, route, map, folder, share) has a UUID and is never identified by a server-assigned ID.
- Changes are recorded as an **operation log** with hybrid logical clock (HLC) timestamps. Peers exchange the ops they're missing. Conflicts resolve per field (last-writer-wins, plus CRDT types where merging matters, e.g. track point lists).
- Transport is pluggable: HTTPS/WebSocket to the server first, possibly device-to-device later (Wi-Fi Direct / Bluetooth / LAN) with the same protocol.
- Wire format: versioned schema (kotlinx.serialization with protobuf or CBOR) so old clients keep working.
- Candidates to evaluate instead of building our own: Automerge, Electric/PowerSync-style sync, Couchbase Lite.

### Peer-to-peer (long-term goal)
Ideally modules talk directly with no central server, BitTorrent-style. Not built early, but these choices keep it possible:
- **Identity is a keypair, not a server account.** Each device/user has a public key; ops are signed. A server can vouch for keys but isn't required.
- **Content-addressed data.** Tile packs and large files are identified by hash and split into chunks, so any peer can serve any chunk and the receiver verifies it. This is the BitTorrent/IPFS model.
- **The op log is already peer-friendly.** UUIDs + HLC timestamps + "send me the ops I'm missing" works the same between two phones as between a phone and a server.
- **Transport stays pluggable.** Start with direct HTTP/WebSocket to a known peer. Later: LAN discovery (mDNS), a DHT for finding peers, relays for NAT traversal. Evaluate jvm-libp2p (MIT/Apache) before building our own.
- **The server is just an always-on peer** that also seeds content and can act as a relay.

### Routing (future)
- BRouter (Java, offline, hiking/bike profiles, already runs on Android) or GraphHopper (Java, offline-capable). Both are JVM-only, so they sit behind a `Router` interface.

### Concurrency
- Kotlin coroutines + Flow throughout.
- CPU-heavy work (DEM decoding, contour generation, tile rendering) runs on `Dispatchers.Default`, which sizes to the device's cores. Large jobs are split per tile so they parallelize. This is where multi-core and hyperthreaded CPUs pay off.
- I/O (disk, network, database) runs on `Dispatchers.IO`. GPS tracking runs in a foreground service.

## Phasing
Build and explore ideas first; the full system comes later.

| Phase | Goal | Where it runs |
|---|---|---|
| 0. Explore map generation | DEM → hillshade, slope shading, contours. Output PNGs and tiles; check quality and speed. | JVM: `shared` + `devtool` CLI (fast iteration, no emulator) |
| 0b. OSM base map on desktop | Planetiler → PMTiles for a region; MVT decoding in `shared`; trails, water, and names drawn over the terrain in `devtool` | JVM |
| 1. Android shell | MapLibre map showing phase-0 output from local files; GPS location; debug server | Android, early, so phone constraints show up quickly |
| 2. Offline + user data | Download manager, tracks, waypoints, GPX import/export, local DB | Android |
| 3. Sync | Op log, sync simulator, then a Ktor peer | `shared` + server |
| Later | OSM base map + routing, P2P transports, desktop, web, iOS | |

Phases 0b and 1 are broken into independent tasks for parallel agents in [TASKS.md](TASKS.md).

### Phase 0 log
**2026-10-09: first pipeline working.** `devtool render` fetches Terrarium elevation tiles (Mapzen/AWS, global, z≤15), stitches and crops them to an area, then draws hillshade + contours to PNG. JVM timings for Mount Rainier at z13 (1166×1279 px, 13 m/px, 30 tiles): hillshade 41 ms, contours 577 ms (273 levels at 40 ft, 1.1M points), drawing 785 ms. That's whole-area work in ~0.6 s total, so per-tile work on a phone looks very feasible.

Findings to follow up:
- **Contour density must depend on zoom.** 40 ft at 13 m/px is an unreadable solid mass on steep terrain; 200 ft is clean. Need a zoom → interval table, and probably to hide lines where they'd merge (steep cliffs).
- **Faint striping on smooth slopes** (visible on Rainier's glaciers in the hillshade). Likely the source being upsampled from lower resolution; check other zooms, and try light smoothing before hillshade/contours.
- **Contours are unsmoothed and unsimplified.** Add Douglas–Peucker simplification (point count) and possibly smoothing.
- Terrarium is a convenient starting source, but its resolution varies by region. Next sources to try directly: Copernicus GLO-30 and USGS 3DEP.

**2026-10-09: USGS 3DEP 1/3″ source + `devtool compare`.** Pure-Kotlin GeoTIFF reader in `shared` (tiled float32, LZW + floating-point predictor, georeferencing; deflate injected on JVM) reads 3DEP Cloud-Optimized GeoTIFFs over HTTP range requests: only 22 requests and 20 internal tiles for a 16 km area instead of two ~400 MB files. Compared with Terrarium on an identical Web Mercator grid (a 16 km Colorado mountain area, z14, 4.6M cells): mean difference 0.9 m, RMS 7 m, max 140 m (cliffs). 3DEP removes Terrarium's seam line and terrace striping and gives crisper gullies and ridges. Next: 3DEP 1 m lidar, then compositing.

**2026-10-09: 1 m lidar + first compositing.** `shared` gained UTM projection math (Krüger series), GeoTIFF overviews and predictor 2, and `Composite.over` (fills gaps from a fallback, smoothstep-feathered over a band at coverage edges using a chamfer distance transform). New devtool sources: `3dep-1m` (lidar only: finds tiles via the USGS TNM API, samples each survey project separately, composites newer surveys on top) and `3dep-best` (lidar over the 10 m DEM). Each lidar tile is read at the coarsest internal overview that still matches the output resolution, so a zoomed-out render reads 4 m data, not 1 m.

Test in a Colorado mountain area covered by two overlapping lidar surveys:
- At ~1.9 m/px (z16) lidar shows talus, gullies, and cliff bands that 10 m data smooths away. The highest point matches the published summit elevation to within 0.2 m; the 10 m DEM is 4 m low.
- At ~7.5 m/px (z14) over 16 km: no visible seam between the two surveys. Lidar vs 10 m: mean −0.04 m, RMS 2.3 m. First fetch 13 s (180 range requests), 2.4 s once cached.
- Not yet exercised on real data: the lidar → 10 m fallback seam (this area is fully covered). It's unit-tested; needs a real test area at a lidar coverage edge.
- For per-tile rendering later: feathering needs a halo of neighboring data, or tiles will disagree at their borders when a coverage edge sits just outside a tile.

**2026-10-09: global coverage + common vertical datum.** All sources now output **EGM2008** heights. 3DEP (NAVD88) is converted per pixel with NGS GEOID18 + the NAD83(2011)→WGS84 ellipsoid shift − NGA EGM2008, using public-domain grids from the PROJ CDN read by range requests. New `copernicus` source (GLO-30 COGs on AWS; samples across 1° cell boundaries so cells join seamlessly) and `best` source: Copernicus globally, 3DEP 10 m over it (300 m blend band), lidar over that (60 m band). `best` is now the default.
- Datum check on bare, flat ground (Bonneville Salt Flats): converted 3DEP minus Copernicus = **+0.20 m** mean (0.40 m mean abs). Unconverted it would be +1.0 m; with the ellipsoid shift's sign flipped, +1.5 m. Conversion confirmed.
- Border seam (Glacier/Waterton, 49°N): contours and hillshade continue smoothly through the US→Canada transition. US data's edge follows lidar UTM tile edges, not the border, so the transition is irregular; the feathering handles it.
- Copernicus is a surface model (includes forest canopy): in forest it sits several meters above bare-earth 3DEP (mean −3.7 m best vs Copernicus at the border). Feathering hides that as a gentle ramp, but contours outside the US will run high in forests. Bare-earth global alternatives: FABDEM (CC BY-NC-SA, non-commercial: check against our licensing before use), or national lidar DTMs (Canada HRDEM, Swisstopo, etc.) added as more layers.
- Bug found by viewing output: pixel-is-point cells (Copernicus) left a 1-px seam at every 1° boundary until sampling read neighbors across cells.

**2026-10-09: T5 merged (PR #1): MVT decoder.** Hand-written protobuf wire parser and Mapbox Vector Tile decoder/encoder in `shared/.../mvt/` (pure Kotlin): points, lines, polygons with holes by winding, every property value type. `devtool mvt dump` summarizes a tile. A real z2 tile from the MapLibre demotiles set (BSD-3; Natural Earth data) is a committed fixture: 147 features, 20.9k vertices, decoded in 16 ms. First task delivered by the parallel-agent workflow (implementation on one agent, verification on a cheaper one).

**2026-10-09: T3 merged (PR #2): PMTiles v3.** Pure-Kotlin reader and writer in `shared/.../pmtiles/` (header, Hilbert tile IDs, run-length directories, leaf directories, injected codecs, a small JSON parser for metadata) plus `devtool pmtiles info`. Tests include a 30 001-tile archive that forces leaf directories (0.12 s). Against the real 138 GB Protomaps planet archive, three Rainier-area tiles cost 7 range requests and 449 KB, and the decoded tiles name Mount Rainier National Park and the Nisqually River, which confirms the Hilbert mapping. This is the container format for region packs (vector tiles now; DEM tiles once T4 lands).

**2026-10-09: T1 merged (PR #3): contour readability.** Contour interval now follows zoom (feet: 1000 ft at z11 down to 20 ft at z16+; meters: 200 m down to 5 m), with `--interval` as an override. Douglas–Peucker in pixel units cuts Rainier z13 from 1.15M points to 35k with no visible change; non-index lines are thinned where they'd be closer than 3 px, so cliffs show index lines instead of a brown smear; elevation labels sit on straight stretches of index contours, upright, collision-free, haloed, sized for a phone (`--text-scale`, default 1.4). Review on the images: 14000/13000 ft on the crater rings, labels dense but never overlapping on the z15 faces. Open: curved labels for low zoom (z11 on a small window gets none), and a degenerate 2-point ring case in simplification.

**2026-10-09: T4 merged (PR #4): DEM tile export.** `devtool tiles` writes the composited `best` DEM as Terrarium XYZ tiles plus `metadata.json`, each tile rendered through its own window with a halo (feather band rounded up to 16 cells) so the feathering gives identical values on both sides of every border; `--verify` compares neighbours bit for bit. Rainier z8–13: 52 tiles, 8.7 MB, 5 s warm (81 s cold, nearly all of it the z8 tile's 236 lidar headers; TNM index queries are the cold-start cost at low zoom). Zero seam mismatches, decode error 1/512 m. The seam check caught a real bug: the geoid-offset lattice was clamped at window edges, shifting values by ≤1.5 mm in the last 16 columns; fixed. These tiles are what the Android shell (T10) will display and what T3's writer will package.

**2026-10-09: T6 merged (PR #5): OSM → PMTiles with Planetiler.** New `tilebuild` module (JVM only). Planetiler 0.9.3 with the OpenMapTiles 3.16 profile (0.9.3 because the profile artifact on JitPack is built against it; 0.10 has no published profile). One-time aux data download of 1.45 GB (Natural Earth, water polygons, lake centerlines) into the shared cache; after that Liechtenstein builds in 31 s (182 tiles, 2.4 MB, 15 layers) and Washington clipped to the Rainier box in 47 s (364 MB extract, 170 tiles, 1.2 MB). ODbL and OpenMapTiles attribution plus `dystopo:*` provenance keys live in the archive metadata, confirmed with `pmtiles info`. Licensing: GeoTools (LGPL-2.1) comes with Planetiler; the rule was amended to tolerate LGPL in build-side tooling only. Wave 1 complete: all five tasks merged in one session. Next on this track: a custom outdoors profile (trails with surface/visibility, huts, passes), and drawing these tiles over the terrain in devtool (T5 + T6).

**2026-10-09: T2 merged (PR #6): slope-angle shading and multidirectional hillshade.** Slope angle/aspect per cell in `shared`; CalTopo-style bands (27–29 yellow through 60+ blue) as a translucent overlay (`--slope-overlay`), and `--shading multi` blending four light directions with a north-west bias so faces turned away from the light keep their ribs and couloirs. Rainier z13: 17% of cells in the 35–45° band, 1.4% over 60°; Mont Blanc 25% and 1.9%. Slope costs ~100 ms for a 1.5 Mpx area. Known: the Terrarium-era striping on Rainier's upper east-side glaciers flips bands at the 27–35° edges; DEM smoothing before slope/hillshade is the fix (follow-up).

**2026-10-09: T7 merged (PR #8): dependency license check.** `./gradlew check` now fails on any GPL/AGPL dependency in any module, on LGPL outside `tilebuild`, and on artifacts whose POM chain has no license (unless listed in `gradle/licenses/artifact-overrides.txt`). Current state: shared 2 artifacts, devtool 24, tilebuild 135 (10 LGPL, tolerated). Policy choice recorded: multiple licenses in a POM are treated as alternatives (best wins), which is what lets dual-licensed JNA/jffi pass; the report lists every license so cumulative cases can be found by hand.

**2026-10-09: T8 merged (PR #7): golden-image tests.** Six synthetic-terrain renders (cone, ridge and saddle, flat lake with shoreline, 80° cliff band, rolling ground) go through the full pipeline in `./gradlew check` and are compared against committed PNGs with a strict tolerance outside label boxes and a loose one inside. A 15° change to the light azimuth fails all six with diff images. Caveat recorded: label glyphs depend on the machine's fonts, so goldens are tied to this Windows/JDK 21 setup until CI exists. Found while making them: on a merged index-contour band along a cliff, labels stack (cosmetic; follow-up).

**2026-10-09: T9 merged (PR #9): data source registry.** Licensing hard rule 4 is now code: `sources.json` in `shared` lists every source with license, attribution (plain and HTML), vertical datum, coverage and cadence; the registry validates on load. Reports carry provenance, i.e. only the sources that actually contributed (Rainier z13: 3DEP + GEOID18 + EGM2008, no Copernicus, because US coverage is complete there; at the Glacier/Waterton border all five), tile packs get registry attribution in their metadata, and tilebuild credits come from the same place. Wave 2 complete: nine of ten tasks merged; only the Android shell (T10) remains in wave 1's list.

**2026-10-09: T11b merged (PR #10): hardening sweep.** Corrupt PMTiles archives now fail fast with a reason instead of allocating or looping (11 fixtures built by patching real writer output); tilebuild leaves no temp dirs; the license policy no longer lets "GPL ISC"-style strings through and reports multi-license choices; the registry carries the Copernicus licence document, honest 3DEP datum wording, SPDX expressions, and provenance is mandatory on every result with data.

**2026-10-09: T12 merged (PR #11): DEM region packs.** `tiles --pmtiles` writes a region's Terrarium tiles into one PMTiles archive (Rainier NP region z8–14: 1,404 tiles, 222 MB, 21 s warm), verified tile by tile against the XYZ output (identical grid digests); `pmtiles extract --bbox` cuts a sub-archive without re-encoding, including from the remote planet build. The browser demo now reads hillshade and contours from the archive. This is the "download a drawn area" mechanism from the product principles, working end to end on desktop.

**2026-10-09: T18 merged (PR #12): browser demo tour.** `tools/demo-check.py --views-file tools/demo-tour.txt` drives 14 views across the Rainier region in one headless session and judges each (errors, missing tiles, per-layer counts, screenshots). It found and fixed: contours disappearing one tile in from the data edge (the contour library dropped any tile with a missing neighbour), a seam at the z12→z13 coverage boundary (missing tiles are now built from the nearest ancestor), no contour labels at z12, a solid brown summit at z13 (interval now 80/400 ft there), and a panel that hid a third of a phone screen. Known: contouring runs on the main thread in archive mode (2–4 s per view); the hillshade layer doesn't get the ancestor fallback, so at z9 it stops a little short of the contours.

**2026-10-09: T17 merged (PR #13): world tour.** `devtool tour` renders 22 public landmarks worldwide at z9 and z12 with plausibility checks (expected elevation range, NaN fraction, datum conversion) and a contact sheet; 44/44 pass in 10 s warm, 203 s cold. The highest pixel lands on the true summit for Everest, Denali, Kibo and Batian; no seams at 180°, the equator, 0° or 1° cell corners. It found an out-of-memory crash on open ocean (contour levels from an all-NaN grid), three antimeridian bugs (bounding box, Copernicus cell naming at ±180, tile column 0), and a tilebuild load-time validation that one odd area could break. Recorded: Alaska/Hawaii download 3DEP then discard it for lack of GEOID18 (GEOID12B is the fix), lidar is queried at low zooms where it's useless (Death Valley 28 s cold), `render` crashes on a no-data grid, antimeridian tile metadata bounds, and Copernicus void-fill artifacts (a 230 m low summit near Paine Grande with a step at 51°S; coastal pits of −13 to −75 m). The tour is now the post-wave sanity check.

**2026-10-09: T11a merged (PR #14): rendering and DEM follow-ups.** Labels no longer stack on merged cliff bands (a label is rejected when another index contour passes within 2 px); `--smooth-dem 1` removes most of the band striping on noisy glaciers while keeping ridges sharp (hillshade and slope only; contours stay on the raw grid); the Horn gradient is shared between hillshade and slope; the lidar index is fetched once per 1° cell and tile reads run on a dedicated I/O pool, so a fully cold Rainier z8 tile went from 81 s to 26 s; tile exports pick one overview level per zoom; golden tests now check a label manifest (text, position, angle) so an extra or wrong label can't hide.

**2026-10-09: T13 merged (PR #15): OSM over terrain.** `render --osm` draws our own OpenStreetMap archive on top of the terrain: tints for forest, wetland, rock and glacier under the contours; lakes over them; streams, park boundaries, cased roads, tracks and trails by type; peaks with name and elevation; curved names along trails, roads, rivers and ridges, placed without overlaps and with contour labels yielding to them. The style is declarative JSON in `shared` (ordered layers, filters, zoom-dependent values), so the same style can later drive MapLibre on the phone and the website. Rainier z13 costs ~130 ms of OSM work on top of the terrain render. This is the first complete topographic map the project produces on its own data.

**2026-10-09: T16 merged (PR #16): outdoors profile.** `tilebuild --profile outdoors` builds our own vector schema: OpenMapTiles layer names kept where the data is the same, plus trails from z10 with `sac_scale`, visibility, surface, operator and hiking-route names; peaks from z9 with rank; `outdoor_poi` (trailheads, campsites, shelters, springs, viewpoints, waterfalls) and `natural_line` (cliffs, ridges); glacier, scree and wetland land cover; wilderness boundaries. Archives come out 10–20% smaller than OpenMapTiles (no buildings, house numbers or multilingual names). Verified on a z13 Paradise tile: the Wonderland Trail carries route name, `sac_scale=hiking`, surface and visibility. Gap: the default style doesn't yet draw `outdoor_poi` or `natural_line` and reads ridge names from the OpenMapTiles place; a style update is the follow-up. Wave 3 complete: 16 PRs merged in one day.

**2026-10-09: T22 merged (PR #17): the lidar → 10 m seam on real data.** A new public area, `rainier-lidar-edge` (south-west Mount Rainier NP, where a 2022 lidar project ends at about 46.77°N), finally exercises the lidar-to-10 m transition that was only unit-tested before. At z13 and z14, in hillshade, slope bands and contours, the edge is invisible: no step, no kink, no tone change, and the 60 m feather band can't be seen. Lidar vs 10 m over the covered cells: mean +0.19 m (tree canopy), so no datum bias. The only artifact was in the verifier itself: cells within a feather width of a haloed window edge are compared against a neighbour that can see no-data beyond the window; the check is being narrowed to the exactly-computed overlap (T19).

**2026-10-09: T21 merged (PR #19): datums beyond CONUS.** The geoid grid is now chosen by location: GEOID18 for CONUS and Puerto Rico/USVI (which superseded GEOID12B there), GEOID12B for Alaska (a grid that crosses 180°) and Hawaii (on NAD83(PA11), with its own ellipsoid shift). Sources check geoid coverage before downloading, so areas outside the US no longer fetch 3DEP at all. Checks on bare ground: North Slope tundra agrees with Copernicus to +0.03 m (1.5 m off without the grid), Kona lava plain +0.10 m, Bonneville unchanged at +0.20 m. Denali and Mauna Kea now render from converted 3DEP; the tour is 50/50 with the two new flat areas. Verified against the PROJ grid index and README.

**2026-10-09: T20b merged (PR #18): the browser demo reads the outdoors schema.** POI discs with class glyphs (trailheads, campsites, shelters, springs, viewpoints, waterfalls, parking), cliffs with ticks, named ridges, trails styled by `sac_scale` and faded when hard to follow, wilderness boundaries, lakes drawn over streams; the hillshade now uses the same ancestor fallback as the contours; a collapsible panel for phones. The tour grew to 21 views (phone width, lake views, outdoors views), all clean. The "missing lake names" question had a data answer: the profile only labels lakes about 48 px wide, so names appear from z14.

**2026-10-09: T19 merged (PR #20): sources and formats sweep.** The cache race that crashed cold parallel exports twice is fixed (publish without replace; the loser reads the winner's bytes); open ocean renders and compares without crashing; lidar is skipped where the output is coarser than 20 m per cell (same results, fewer downloads); the lidar index fetch retries with backoff; antimeridian exports write proper bounds; `pmtiles` commands fail with one line on corrupt archives; large extracts stage to disk; and `tiles --verify` no longer compares cells its neighbour computes with more information (T22's false positive). 249 tests; world tour 50/50 at 25 areas.

**2026-10-09: T20a merged (PR #21): renderer style for the outdoors schema.** The devtool renderer now draws everything the outdoors profile emits: POI markers by class, cliffs with ticks on the downhill side (decided from the DEM; 1 of 24 Rainier cliffs flipped from the OSM default), named ridges, trails darker and shorter-dashed as `sac_scale` rises, faded when hard to follow, wilderness boundaries. Trail names can no longer touch. A new golden renders a tiny synthetic outdoors archive built in the test (T5 encoder + T3 writer), so style regressions are caught offline; 255 tests. Wave 4 complete: 21 PRs merged today.

Phase-0 algorithms live in `shared` from the start, so the same code runs on Android in phase 1. Their speed and memory on the JVM are measured, then checked again on a real device.

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
| Dev CLI (`devtool`) | JVM command-line app that calls into `shared` | Exists: `render`, `compare`, `areas`. Later: tile export, `sync-sim --peers 3`, PMTiles/GPX inspection |
| Golden-image tests | Rendered tiles and screens compared against approved PNGs | Map rendering, hillshade, contours, UI screens (Roborazzi/Paparazzi run Compose screenshots on the JVM) |
| Sync simulator | Many in-process peers over a fake network with latency, partitions, and reordering, driven by a seed | Proving offline/online convergence; any failure replays exactly from its seed |
| In-app debug server | Debug builds run a small HTTP/JSON server, reached via `adb forward` | Query state, invoke actions, inject GPS fixes and tracks, toggle offline mode, load fixtures, dump the DB, fetch logs, take screenshots, read map camera/layers |
| UI end-to-end | Maestro flows (YAML) or UI Automator, run against the emulator | Critical user journeys |
| Server debug endpoints | Same debug API on the Ktor server; local multi-peer setup via Docker Compose | Server-side state and sync inspection |

### Privacy in the repo
This is a public, open-source repo. **Nothing that reveals where the developer lives or travels is ever committed.**
- Personal test areas are defined in a gitignored local file (`*.local.*`, e.g. `devtool.local.json`). It can hold several named areas ("home", "trip") and be changed freely.
- Committed tests, fixtures, and golden images use only well-known public places. Initial defaults: **Mount Rainier, WA** (US, high-resolution USGS lidar) and **Mont Blanc / Chamonix** (non-US, Copernicus 30 m).
- Generated output, downloaded data, and caches go in gitignored directories (`out/`, `data/`, `cache/`).
- Test GPS tracks and waypoints are synthetic or come from public places. Real recorded tracks are never used as fixtures.
- The same rule applies to the app's debug tooling: logs and state dumps that may contain locations stay local.

### Command-line environment
- Gradle wrapper builds everything; no Android Studio required for agents (it's still useful for humans).
- Android SDK command-line tools + `adb` + headless emulator (`emulator -no-window`).
- Single entry points, e.g. `./gradlew check` runs everything that doesn't need a device; one script boots the emulator, installs the app, and opens the debug port.
- A `CLAUDE.md` at the repo root documents these commands and conventions for agents.

## Licensing
The project will be fully open source. Code and map content may need different licenses. Working choices: GPLv3 for apps, AGPLv3 for the server, LGPLv3 possible for `shared`; ODbL for OSM-derived vector tiles, CC BY-SA 4.0 for other content. These can be revisited later (see below).

### Hard rules
1. **Data compatibility.** Everything we produce must comply with the licenses of OSM (ODbL) and every elevation or other data source we use, including attribution.
2. **iOS compatibility.** Tiles, the sync protocol, and anything else other modules exchange must be usable by a future iPhone app. Tile formats and the wire protocol are open, documented specs, so any client can implement them regardless of the code license.
3. **Keep relicensing possible.** We can change the license later only as long as we remain the sole copyright holders. So:
   - Write our own code. Don't copy code from other projects into the repo.
   - Dependencies must be permissively licensed (MIT, BSD, Apache-2.0, EPL/EDL, etc.). **No GPL or AGPL dependencies anywhere.** Those would lock in our license choice. **LGPL is allowed only in build-side tooling** (`tilebuild`, later the server's tile pipeline) where the library stays a separate, replaceable jar; never in `shared`, the apps, or anything distributed to users. (Decided 2026-10-09 when Planetiler brought in GeoTools, LGPL-2.1: LGPL doesn't constrain the license of code that merely uses the library, so it doesn't threaten relicensing.)
   - No outside contributions until there's a contribution policy (CLA or copyright assignment).
   - An automated dependency-license check fails the build on any disallowed license. **Done (T7):** `./gradlew licenseCheck`, part of `check`; config in `gradle/licenses/`.
   - Versions already released under a license stay available under that license; relicensing applies going forward.
4. **Data source registry.** Every data source (DEM, OSM, overlays) is listed with its license, required attribution text, and allowed uses. Tile builds read attribution from this registry and write it into tile metadata.

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
| Code sharing | Kotlin Multiplatform | Decided; in use (JVM target now, Android and iOS targets added later) |
| Android UI | Jetpack Compose (Compose Multiplatform later?) | Proposed |
| Map renderer | MapLibre Native | Proposed |
| Local DB | SQLDelight (SQLite) | Proposed |
| Sync | Op log + HLC, pluggable transport | Proposed; evaluate libraries first |
| Server | Ktor (Kotlin, JVM) | Proposed |
| Tile generation | Elevation: own code in `shared` (working). OSM: Planetiler on server/desktop | Elevation done on desktop; Planetiler is task T6 |
| Routing | BRouter or GraphHopper | Future |
| Build | Gradle 8.14 (Kotlin DSL), JDK 21, Kotlin 2.2 | Decided |
| Package ID | `io.github.jasondorjeshort.dystopo` | Decided (GitHub namespace) |
| Min SDK | _TBD_ (proposal: 26) | Open |

## Dev environment setup
- [x] Git, GitHub CLI (`gh`), repo at https://github.com/jasondorjeshort/dystopo (private until there's something to show)
- [x] JDK 21 (Temurin), Gradle wrapper
- [ ] Android SDK command-line tools + headless emulator (task T10; Android Studio is optional)
- [ ] Physical device with USB debugging

## Open questions
1. Which "simple features" is BackCountry Navigator missing? What does each reference app do that must be kept?
2. What does "local map generation" mean: on the phone itself, or on infrastructure you control (your own server/desktop) instead of third-party tile providers?
3. ~~What does "distributed" mean?~~ Decided: ideally peer-to-peer with no central server (BitTorrent-like); a server is an optional always-on peer. Not built early. See [Peer-to-peer](#peer-to-peer-long-term-goal).
4. ~~Region~~ Decided: global, using the best available data for each region.
5. ~~Open source?~~ Decided: fully open source, with working license choices that can be revisited. See [Licensing](#licensing).
6. ~~App name~~ Decided: **Dystopo** (dystopia + topographic); tagline TBD. Package ID: `io.github.jasondorjeshort.dystopo`.
7. Bare-earth elevation outside the US: Copernicus includes tree canopy. Which national lidar sources to add first?
