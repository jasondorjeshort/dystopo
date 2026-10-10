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

**2026-10-09: T24 merged (PR #23): tracks, waypoints and GPX.** The first phase-2 piece, in `shared` so every platform shares it: waypoint/track/route model with UUID ids, GPX 1.1 import and export on a hand-written XML reader (extensions preserved byte for byte, Garmin heart rate and cadence read), stats with documented rules (3 m turning-point hysteresis for ascent, 0.3 m/s moving threshold), and `devtool gpx info|render|export`. The render draws a track with direction arrows, waypoints and a route over the terrain and OSM layers with an elevation profile strip. The fixture is a hand-written Skyline Trail loop (public trail geometry rounded to 10 m, invented times), never a recording.

**2026-10-09: T23 merged (PR #22): leftover sweep.** Nine recorded follow-ups closed. Notable: one marker table now drives both the renderer and the browser demo, enforced by a test that parses the demo page; cliff ticks are decided per segment from a bilinearly sampled DEM; Mount Rainier Wilderness is a wilderness again in the outdoors profile (OSM tags it `protect_class=1a` plus wilderness tags); no more NaN in tile reports over open ocean. 270 tests, both tours green.

**2026-10-09: T10 merged (PR #24): Android shell.** Phase 1 starts. `androidApp` (Compose + MapLibre Native, min SDK 26) shows a region pack: hillshade from the DEM PMTiles, the outdoors vector PMTiles, and contour tiles generated on the device from the DEM pack by the shared `Contours` + MVT encoder, cached on disk. MapLibre cannot read PMTiles, so a loopback-only HTTP server (port 8686) serves `/dem`, `/osm`, `/contours`, `/glyphs` and `/style.json`; the style is translated from the devtool's `DefaultOsmStyle` by `MapLibreStyle`. Debug endpoints (`/health`, `/screenshot`, `/camera`, `/stats`, `/location`, `/reload`, `/cache/clear`) exist in debug builds only. `shared` gains an `android` target that is only wired when an SDK is present, so SDK-less machines still build; `devtool serve` runs the same routes on the JVM so the phone style can be checked with `demo-check.py` without a device. Verified: 808 JVM tests, lint and license check green (91 Android runtime artifacts, all permissive); debug APK 55.9 MB (MapLibre native libs 9 to 13 MB per ABI, uncompressed); JVM stand-in contour tiles 95 / 183 / 308 ms (min / avg / max) at z13. Android SDK installed under `C:\Users\Dorje\Android\sdk` by `tools/android-sdk.ps1`; the emulator needed Windows Hypervisor Platform, which is now enabled (`emulator -accel-check` passes). First emulator run (Pixel 6 AVD, API 35 x86_64, WHPX, 4 cores, 4 GB): the whole pipeline works on the device. Screenshots at z12, z13.5 and z15 show hillshade, brown contours with index lines, trails, roads, streams, lakes, forest and POI dots; nothing blank or black. Defects: no text renders at all (no names, no peak elevations; only 2 glyph requests were served), `/screenshot?mode=window` returns a white page, the location puck is not visible after a `/location` inject, the attribution shows a literal `&copy;`, and `/health` reports `idle=false` for a minute after load. Footprint: PSS 184 MB total (Java heap 26 MB used of 201 MB max, native 109 MB, mostly MapLibre), APK 58.6 MB, packs 227 MB. Contour tiles: z13 to z15 take 0.2 to 0.5 s, but z12 tiles from DEM z13 take 6.7 to 9.7 s and z7/z8 tiles 7 to 8 s, and generation is serialised on a lock (logcat: monitor contention in `TileService.contourTile`). These are wave 6 tasks. Follow-ups: the contour disk cache is keyed by unit, tile size and z/x/y only, so a replaced DEM pack serves stale contours until `/cache/clear`; the glyph font licence (Noto Sans, OFL 1.1) is only in a script comment, not in `sources.json`; the loopback server has a 4-thread pool with an unbounded queue and no slow-connection guard; contour labels are sparse on the phone.

**2026-10-10: T26 merged (PR #29): contour tile performance and cache identity.** The 7 to 10 s low-zoom contour tiles were three things at once: tiles below z13 traced the DEM at full resolution, one global lock serialised generation, and the map's own rendering competed for the CPU under the software GPU. Now `ContourTiles.plan` picks the DEM zoom per tile zoom (full resolution at z13+, one level coarser at z10 to z12, two below) with slope cut and simplification kept in screen pixels; the DEM pyramid cache is thread-safe with decoding outside the lock; up to cores-1 tiles generate at once with same-tile requests sharing one result; the contour disk cache and the style's tile URLs carry a checksum of the DEM pack (size, mtime, header), so a replaced pack serves fresh contours and old folders are deleted in the background. Verified on the emulator with the host GPU: warm tiles 90 to 480 ms (slowest zoom-in 637 ms), three concurrent, pack swap regenerates without `/cache/clear`; JVM z8 tiles went from 46 to 4 ms average. Cold start after a fresh install still shows 2 to 2.9 s z12 tiles (JIT and first pack reads); Java heap peaks at 82 MB after a zoom-in. Follow-ups: tracing in `render/Contours.kt` dominates at z13+ (170 to 500 ms per tile); about 15% duplicate decodes when threads miss the same tile together; re-pushing identical bytes changes the mtime and regenerates once.

**2026-10-10: T30 merged (PR #30): attribution and sources on the device.** A shared `PackAttribution` composer reads the registry ids that both pack writers already put in PMTiles metadata (the DEM `provenance` array, tilebuild's `dystopo:provenance`), resolves them against `SourceRegistry`, decodes HTML entities and builds per-pack and per-source credits plus a compact line. The app shows the line on the map (tap opens an About screen with pack cards, one card per source with licence and links, the font, and the app licence), and `GET /attribution` returns the same JSON on the device and in `devtool serve`. Noto Sans is registered as a `font` source (OFL-1.1). For the Rainier pack the credits are USGS 3DEP (10 m and 1 m), GEOID18, EGM2008, OSM, OSM water polygons, Natural Earth, OpenMapTiles and Noto Sans; Copernicus is absent because the pack holds no Copernicus pixels (a Glacier/Waterton pack credits both, covered by a test). Follow-ups: friendly names instead of raw ids on pack cards; a short-name field in the registry for the compact line; the `noto-sans` registry url is the glyph template rather than a landing page.

**2026-10-10: T31 merged (PR #28): tracks on the phone.** The first user data on the device, on T24's model. `shared` gains a `TrackStore` interface with an in-memory implementation for tests and a SQLDelight 2.1 implementation (Apache-2.0; schema in `commonMain/sqldelight`, generated code is pure Kotlin, the JDBC driver is test-only and the app uses the Android driver), a recorder (drops fixes worse than 50 m or out of order, new segment after a pause, live T24 stats, closes tracks left recording after a crash) and GPX export. The app records through a foreground service fed by a small `LocationSource` (real GPS, or fixes injected through `POST /location` and `POST /location/replay`, which plays a GPX at a chosen speed); the track is drawn as a magenta line with start and end dots and re-added after style reloads; Compose gives record/stop/waypoint buttons, a live stats card and a tracks list with GPX export to the files dir. Verified: a 100× replay of the Skyline fixture gives identical distance (8141 m), ascent/descent (548/546 m), elevation range and point count in the live `/tracks` stats, the exported GPX and `devtool gpx info`; a force-stop mid-recording keeps both tracks and closes the interrupted one with its points. SQLDelight's migration-verification task is disabled (its SQLite DLL unpack fails under the Gradle daemon on this machine) until the first migration exists. Follow-ups: one unreproduced replay recorded a single point (possible service start race); `GET /tracks` recomputes stats per track; no pause/resume; waypoint dots lack labels; track ids are not kept through GPX.

**2026-10-10: T25 merged (PR #27): labels on the device and text scale.** The missing text was the emulator, not the style: under the software GPU (`-gpu swiftshader_indirect`, which the script used to pass) MapLibre Native draws no symbol layers and no dash patterns at all, while `-gpu host` renders every label, elevation and dashed trail. MapLibre placed the labels in every mode (a new `GET /labels` route counts them per layer), so the symptom was invisible, not absent, text. There was also a real bug: glyph requests queued behind contour tiles on the server's four threads, timed out and were cancelled; glyphs now load straight from `files/glyphs` through a `file://` URL with the HTTP route as fallback, and `/stats` shows zero glyph requests over HTTP. The text-scale setting (the design goal that phone labels are too small) is a multiplier from 0.5 to 3 over the built-in 1.35 applied when the style is built, to text, halos, marker discs, padding and spacing; `AppSettings` persists it, `GET`/`POST /settings` change it live, `devtool serve --text-scale` mirrors it. Verified on the host GPU: peak names with elevations, place names, road names, contour labels and trailheads at z13.5; at 1.6 the text is clearly larger with collision dropping a few labels instead of piling up; the setting survives a restart. Follow-ups: out-of-range values clamp rather than 400; POI discs are circles without collision, so large labels can overlap them (a sprite sheet would fix it); contour labels are still sparse; the emulator script must default to the host GPU (T28).

**2026-10-10: T27 merged (PR #25): debug screenshots, location puck, loopback server hardening.** `mode=window` screenshots were white because MapLibre draws into its own SurfaceView, which a window PixelCopy cannot see; the capture now copies the map surface (snapshot fallback) and composes the Compose overlays on top. MapLibre's own location component never drew (it is symbol layers, see T25), so the puck is now ours: a GeoJSON source with three circle layers fed by `LocationManager` or by injected fixes (an injected fix detaches device fixes until `POST /location?device=1`, so the emulator's default position cannot drag it away). `/health.idle` now means style loaded, no camera transition, no tile in flight or queued, last frame fully rendered, all held for 500 ms, with `notIdleBecause` listing the blockers; measured 5 to 6 s after a camera move. `LocalServer` has a bounded queue (4 workers + 64 queued, then 503), head and per-read deadlines (408), line, header-count and body limits (414/431/413), 400 for non-HTTP input and for tile coordinates that are negative, huge or outside z 0 to 24; `tools/android-probe.py` sends 20 bad requests and checks the server survives (it did, with the refusal counters in `/health`). Attribution text decodes HTML entities. Follow-ups: idle stays false for about a minute after first launch (`frameNotFullyRendered`) until the first camera move; debug routes share the four worker threads with tile requests, so `/health` can wait behind contour tiles; the CLAUDE.md endpoint bullets should mention `notIdleBecause`, `?device=1` and the probe script.

**2026-10-10: T29 merged (PR #26): region packs on the device.** The app manages packs instead of assuming one Rainier pair. A pack is `<name>-dem.pmtiles` plus an optional `<name>-outdoors.pmtiles` (the unnamed pair the push script writes is pack `default`); shared `packs/` code names packs, reads bounds, zoom range, tile counts, size and attribution from the PMTiles header and metadata (checking the file is as long as the header says), fits a zoom to bounds, and defines how a download resumes. The app lists packs, switches the active one (camera fits the bounds, contour cache cleared), deletes them, and downloads a pack pair from a URL with progress, writing `.part` files and persisting state so an interrupted download resumes at startup with a range request. `devtool serve` hosts `out/` with range support and a `?kbps=` throttle as the test host. Verified: a 20 KB/s download force-stopped at 39% resumed from the saved offset with a 206, finished with matching checksums; activating a DEM-only Paradise pack moved the camera and rendered contours without OSM; the static route and pack names reject path traversal. Follow-ups: the cleartext allowance for the emulator host address (10.0.2.2) sits in the main network config rather than a debug-only one; `MainActivity` builds the attribution line and no-pack message once at startup, so they go stale after a pack switch; status-bar icons are white over the light pack screen; the throttle is undocumented in `serve --help`; a missing optional outdoors file shows as `missing` on a `done` job.

**2026-10-10: T32 merged (PR #32): live world mode for the viewer.** The browser demo and `devtool serve` were pack-only, so panning away from Rainier showed nothing. `serve --live` now answers every tile from a pack when one holds it and otherwise live: DEM tiles are rendered on request from the `best` source (3DEP where covered, Copernicus elsewhere, EGM2008) at z6 to z15 the same per-tile way `tiles` does and cached under the shared cache with a record of which sources contributed; contours come from that same pack-then-live DEM with T26's sampling; OSM comes from the pack or from OpenFreeMap (free, no key, ODbL, attribution required; registered in `sources.json`; each tile fetched once with at most four in flight, cached on disk). The page takes `?live=1` (or is opened from serve), shows a source box per visible tile (pack, live-3dep, live-copernicus, openfreemap) and a "Build pack here" helper that prints the `tiles` and `tilebuild` commands for the current view; `/live/sources`, `/live/info`, `/phone` and `/stats` report sources, cache hits and timings. Verified: Mont Blanc, Kilimanjaro, Fiordland and Torres del Paine render from live Copernicus plus OpenFreeMap with contours, labels and peaks (cold views 5 to 14 s, warm under 2.5 s including page load; a cold server renders 96 live DEM tiles at about 580 ms each), Rainier is served from the pack with no live fetches, the Glacier/Waterton border and Fiji across the antimeridian show no seams, repeat views make no new OpenFreeMap requests; pack-mode demo tour 21/21 and world tour 50/50 unchanged. Follow-ups: `CopernicusSource` does not cache missing ocean cells, so live ocean tiles repeat a 404 each time; live DEM tiles pick the source level from each tile's own latitude rather than once per zoom (a row seam is possible in theory, none seen); at a pack edge OSM comes from the pack and stops at its boundary, leaving a strip; live attribution lists every `best` source even outside the US; the Python demo server refuses connections under heavy machine load.

**2026-10-10: T37 merged (PR #33): server pack build from live sources.** "Browse online, download a region" now has its server half. `devtool serve --live` answers `POST /build?name=&bbox=&minZoom=&maxZoom=` with a job queue (one build at a time, four waiting), progress at `GET /build/{id}`, cancel, and limits (20,000 tiles per job, 3° per side, 409 on an existing name unless `overwrite=1`; all env-configurable and reported under `GET /build`). The DEM pack comes from the live per-tile renderer at z6 to z15 with the sources actually used as provenance (Mont Blanc: Copernicus only); the OSM pack is written by the new shared `OsmPackWriter` from OpenFreeMap tiles (fetched once, four in flight, cached) with OpenMapTiles metadata whose profile field says plainly that it is not the outdoors profile and which outdoors layers are absent. Files are staged and renamed atomically, served immediately in live mode and under `/out/` for the phone downloader; the browser's "Build pack here" button has name and zoom fields, progress and cancel. Measured: Mont Blanc z8 to z13 from the browser in 12.7 s (44 + 44 tiles, 7.2 MB + 1.4 MB), a mostly cold z8 to z14 box in 34 s (455 tiles each side, OpenFreeMap at about 20 tiles/s), cancel stops fetches within the four in flight and leaves nothing behind; the built pack renders in pack mode and on the emulator with peaks, glaciers and the cable car. Follow-ups: pack precedence is by the `-outdoors` name, so a plain OpenMapTiles pack could outrank a real outdoors pack where they overlap (rank by `dystopo:profile`); OpenFreeMap's 19 KB `vector_layers` metadata could be trimmed; antimeridian boxes are refused; the DEM pack has no licence field; `maxZoom` above 15 answers 400 rather than 413; devtool stdout is cp1252 on Windows when redirected.

**2026-10-10: T39 merged (PR #34): offline place search.** A search index in `shared` built from a vector pack's names (peaks, places, water, trails, huts, trailheads, parks, from the outdoors or the OpenMapTiles schema), scanning z14, z12 and z10 and merging the same name across tiles; our own versioned, checksummed file format (`<pack>-search.idx`, with a pack fingerprint so a changed pack rebuilds); accent folding with a small table; case-insensitive prefix search where every query word must match, with a one-typo fallback only when a word matches nothing (so `ranier` finds Mount Rainier but short queries are not flooded); ranking by match quality, kind, first-shown zoom, peak elevation and distance to a point. `devtool search build|query`, `GET /search`, and a search box on the map whose results move the camera and drop a marker; the index builds in the background on first open with progress under `search` in `/packs`. Rainier: 77 KB for 2,117 places, 0.9 s to build on the JVM, 1.2 s idle on the emulator (15 s on first open while the map was loading under heavy host load), 0.5 s to load after restart, about 12 ms per query. Skipping layers without names before decoding cut the build six-fold. Follow-ups: `para` does not match Panorama Point (prefix search; `pa` and `pano` do); search covers only the active pack; the result marker has no label; a deleted pack leaves its index file; the Packs and Tracks chips nearly overlap on the map.

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
| Android UI | Jetpack Compose (Compose Multiplatform later?) | In use (T10) |
| Map renderer | MapLibre Native 11 (Android); MapLibre GL JS in the browser demo | In use (T10) |
| Local DB | SQLDelight (SQLite) | Proposed |
| Sync | Op log + HLC, pluggable transport | Proposed; evaluate libraries first |
| Server | Ktor (Kotlin, JVM) | Proposed |
| Tile generation | Elevation: own code in `shared` (working). OSM: Planetiler on server/desktop | Elevation done on desktop; Planetiler is task T6 |
| Routing | BRouter or GraphHopper | Future |
| Build | Gradle 8.14 (Kotlin DSL), JDK 21, Kotlin 2.2 | Decided |
| Package ID | `io.github.jasondorjeshort.dystopo` | Decided (GitHub namespace) |
| Min SDK | 26 | Decided (T10) |

## Dev environment setup
- [x] Git, GitHub CLI (`gh`), repo at https://github.com/jasondorjeshort/dystopo (private until there's something to show)
- [x] JDK 21 (Temurin), Gradle wrapper
- [x] Android SDK command-line tools + headless emulator under `C:\Users\Dorje\Android\sdk` (`tools/android-sdk.ps1`, `tools/android-emu.ps1`; Windows Hypervisor Platform enabled)
- [ ] Physical device with USB debugging

## Open questions
1. Which "simple features" is BackCountry Navigator missing? What does each reference app do that must be kept?
2. What does "local map generation" mean: on the phone itself, or on infrastructure you control (your own server/desktop) instead of third-party tile providers?
3. ~~What does "distributed" mean?~~ Decided: ideally peer-to-peer with no central server (BitTorrent-like); a server is an optional always-on peer. Not built early. See [Peer-to-peer](#peer-to-peer-long-term-goal).
4. ~~Region~~ Decided: global, using the best available data for each region.
5. ~~Open source?~~ Decided: fully open source, with working license choices that can be revisited. See [Licensing](#licensing).
6. ~~App name~~ Decided: **Dystopo** (dystopia + topographic); tagline TBD. Package ID: `io.github.jasondorjeshort.dystopo`.
7. Bare-earth elevation outside the US: Copernicus includes tree canopy. Which national lidar sources to add first?
