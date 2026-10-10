# Task board

Work is split into tasks that don't depend on each other, so several agents can run at once. One agent per task, one git worktree per agent; see `CLAUDE.md` › Working in parallel.

Every task is done only when:
1. `./gradlew check` passes (existing tests plus new ones).
2. An agent can exercise the feature from the command line and see the result as JSON or PNG.
3. The PR description reports what was measured (timings, counts, image observations), using only public areas from `areas.json`.

"Owns" lists the files a task may change freely. Touching anything else should be a few lines at most and called out in the PR. Two tasks that both need `MapRenderer.kt` are told below which part is theirs.

## Wave 1 (independent)

### T1. Contour readability
**Goal:** contours that read well at every zoom. (1) A zoom → interval table for feet and meters, with index contours, replacing the fixed `--interval` default (keep the option as an override). (2) Douglas–Peucker simplification with a tolerance in pixels, and optional smoothing. (3) Suppress or thin contours where lines would be closer than ~3 px (cliffs). (4) Elevation labels on index contours: placed along straight-ish stretches, oriented along the line with the text upright, large enough for a phone (a `--text-scale` option, default generous). Hillshade must stay legible under the labels (halo).
**Owns:** `shared/.../render/Contours.kt`, new `shared/.../render/ContourStyle.kt`, `ContourSimplify.kt`, `ContourLabels.kt` and their tests; the contour-drawing part of `devtool/.../MapRenderer.kt` and `StyleOptions`.
**Done when:** unit tests cover the interval table, simplification (point reduction and max deviation), and label placement; `render --area rainier` at zoom 11, 13, and 15 produce readable PNGs; the JSON report includes points before/after simplification and label count.
**Status: merged (PR #3).** Follow-ups: drop closed rings that simplify to fewer than 3 points (`ContourSimplify.kt`); curved per-glyph label placement so low zooms get labels.

### T2. Slope-angle shading and better hillshade
**Goal:** (1) Slope angle per cell (degrees) in `shared`. (2) CalTopo-style slope-angle shading bands (27–29, 30–31, 32–34, 35–45, 46–50, 51–59, 60+ degrees) as a translucent overlay. (3) Multidirectional hillshade (blend of several azimuths) as an option; keep single-azimuth as default. (4) `--shading hillshade|multi|slope|none` and `--slope-overlay` options.
**Owns:** `shared/.../render/Hillshade.kt`, new `shared/.../render/Slope.kt` and tests; a new `devtool/.../Shading.kt` that produces the base pixel array. `MapRenderer.kt` should call it in one place; the contour-drawing code there belongs to T1.
**Done when:** unit tests on planes of known slope; PNGs for `rainier` and `mont-blanc` with each shading mode; report lists the fraction of cells in each slope band.

### T3. PMTiles v3 in `shared`
**Goal:** a pure-Kotlin PMTiles v3 reader and writer: header, root and leaf directories (varint encoding, run-length and delta fields), Hilbert tile IDs ↔ z/x/y, metadata JSON, compression via an injected decompressor/compressor (like `GeoTiff`'s `Decompressor`), reader over `ByteRangeSource`.
**Owns:** new `shared/.../pmtiles/` and tests; a `devtool pmtiles info --file|--url` command (JSON header, metadata, tile counts) in a new `devtool/.../PmtilesCommand.kt`.
**Done when:** round-trip tests with thousands of synthetic tiles (including leaf directories); `pmtiles info` reads a real archive over HTTP range requests (read-only, header + a few tiles; the Florence sample at `https://pmtiles.io/protomaps(vector)ODbL_firenze.pmtiles` is small, and `https://build.protomaps.com/<date>.pmtiles` is the planet).
**Status: merged (PR #2).** Follow-up (small, any model): harden against corrupt input: bounds-check directory counts before allocating, reject lengths/runs above `Int.MAX_VALUE`, cap MiniJson recursion depth, guard `open()` against servers that ignore range requests.

### T4. DEM tile export
**Goal:** write the composited `best` DEM as Terrarium-encoded PNG tiles in an XYZ directory for a zoom range, so a map renderer (MapLibre, task T10) can show hillshade and so T3 can package it later. Each tile is rendered through its own `MercatorWindow` with a halo so feathering at coverage edges gives identical values on both sides of a tile border. Reuse overviews: zoomed-out tiles read coarser source levels. Parallelize across tiles.
**Owns:** new `devtool/.../TilesCommand.kt` (`tiles --area --min-zoom --max-zoom --source --out dir`), new `shared/.../dem/TileWindows.kt` (per-tile window + halo math) and tests. Don't touch `pmtiles/` (T3).
**Done when:** tests prove adjacent tiles agree exactly on shared edges and that decoding a tile back equals a direct render of that window (max error ≤ 1/256 m); `tiles --area rainier --min-zoom 8 --max-zoom 13` runs and reports tile count, bytes, and time per zoom.
**Status: merged (PR #4).** Follow-ups: cache TNM index results per 1° cell instead of per window (cold z8 tile took 58 s); pick overview levels per export rather than per window so tall exports can't get a resampling seam at a latitude threshold.

### T5. MVT decoder in `shared`
**Goal:** decode Mapbox Vector Tiles (protobuf wire format, hand-written parser; no protobuf library) into layers, features (id, properties, geometry type), and geometry in tile coordinates (MoveTo/LineTo/ClosePath commands, zigzag deltas). Include a minimal encoder for tests.
**Owns:** new `shared/.../mvt/` and tests; a `devtool mvt dump --file tile.mvt` command printing a JSON summary.
**Done when:** round-trip tests for points, lines, polygons with holes, and properties of every value type; a decoded real tile (produced by T6, or any `.mvt` fetched by hand) dumps correctly. Tests must not need the network.
**Status: merged (PR #1).**

### T6. OSM tile build with Planetiler
**Goal:** a new Gradle module `tilebuild` (JVM only; keeps `devtool` lean) that wraps Planetiler (Apache-2.0) to turn an OSM extract into PMTiles for a bounding box. Start with Planetiler's OpenMapTiles-compatible profile; a custom outdoors profile (trails, tracks, paths with surface and visibility, water, land cover, peaks, passes, huts, place names) comes later. Download extracts from Geofabrik into the cache.
**Owns:** new `tilebuild/` module, one `include` line in `settings.gradle.kts`, its entries in `gradle/libs.versions.toml`.
**Done when:** `./gradlew :tilebuild:run --args="--extract liechtenstein --out out/liechtenstein.pmtiles"` builds a small archive and reports size and time; a second run on `washington` clipped to the `rainier` bbox works (large download: report time). ODbL attribution is written into the PMTiles metadata.

### T7. Dependency license check
**Goal:** enforce the licensing rule: a Gradle task wired into `check` that fails if any runtime dependency has a GPL/AGPL/unknown license in any module, or an LGPL license outside the build-tooling modules (currently only `tilebuild`, where GeoTools LGPL-2.1 is accepted). Use POM license metadata (a permissively licensed plugin such as `com.github.jk1.dependency-license-report` is fine, or a custom task; `tilebuild` already has a reporting-only `licenseReport` task to build on). Allowlist by license name, per-module tolerance list, and an explicit per-artifact override file for artifacts whose POM lacks metadata (known: `jgridshift-core` LGPL-2.1 via GeoTools, `jcip-annotations` CC-BY-2.5, `planetiler-openmaptiles` BSD-3).
**Owns:** root `build.gradle.kts`, new `gradle/licenses/` config, `docs/PLANNING.md` › Licensing hard rule 3 (mark done).
**Done when:** `./gradlew check` passes on `main`; the PR shows it failing when a GPL artifact is added temporarily.

### T8. Golden-image tests
**Goal:** rendering regressions caught by `./gradlew check` with no network. A `SyntheticTerrain` generator in `shared` (seeded: cones, ridges, a saddle, a flat lake, a cliff) feeds the full pipeline (hillshade + contours), and the PNGs are compared to approved images with a tolerance. `./gradlew updateGoldens` regenerates them.
**Owns:** new `shared/.../testing/SyntheticTerrain.kt`, `devtool/src/test/`, golden PNGs under `devtool/src/test/resources/goldens/`, Gradle task wiring in `devtool/build.gradle.kts`.
**Done when:** tests pass; deliberately changing the hillshade azimuth makes them fail; goldens are small (a few hundred KB total).

### T9. Data source registry
**Goal:** the registry from Licensing hard rule 4. A `sources.json` resource and `SourceRegistry` in `shared` listing every elevation (and later OSM) source: id, name, coverage summary, resolution, vertical datum, license, attribution text, URL template, update cadence. The devtool sources take their `attribution` from the registry instead of hard-coded strings. `render`/`compare` reports include provenance: which registry ids contributed.
**Owns:** new `shared/.../sources/` and the resource; the `attribution` property lines in each `devtool/.../*Source.kt`; a `devtool sources` command.
**Done when:** a test asserts every `ElevationSource.ids` entry has a registry entry with non-empty license and attribution; reports show registry-sourced attribution.

### T10. Android shell
**Goal:** phase 1 scaffolding. An `androidApp` module (Kotlin, Jetpack Compose, MapLibre Android) that shows a local terrain pack (Terrarium XYZ tiles from T4; until T4 lands, tiles rendered by hand from `render`) with MapLibre hillshade and the current location. A debug HTTP server in debug builds (`/health`, `/screenshot`, `/camera`) reachable through `adb forward`. Android SDK command-line tools and a headless emulator installed by a script (`tools/android-sdk.ps1`, `tools/android-emu.ps1`), no Android Studio. Add the `android` target to `shared`.
**Owns:** new `androidApp/`, `tools/android-*.ps1`, the `android` target block in `shared/build.gradle.kts`, Android entries in `gradle/libs.versions.toml`, the Android section of `CLAUDE.md`.
**Note:** installing the SDK and emulator changes the machine; ask before installing. Proposed min SDK 26.
**Done when:** `./gradlew :androidApp:assembleDebug` builds; the emulator script boots, installs, and launches the app; `curl localhost:<port>/screenshot` returns a PNG showing the map.

## Wave 2 (after wave 1 merges)
- T3 + T4: package DEM tiles as PMTiles; `pmtiles extract --bbox` for area downloads.
- T5 + T6: draw OSM layers (trails, water, names) over terrain in `devtool`, with T1's label sizing.
- T1 + T10: on-device contours; measure per-tile time and memory on the emulator.
- Vertical datum beyond CONUS (GEOID12B for Alaska/Hawaii); bare-earth national lidar outside the US.
- A real lidar coverage edge in a public area, to verify the lidar → 10 m seam.
- Gaia GPS: what to keep and what's missing (open question 1).
