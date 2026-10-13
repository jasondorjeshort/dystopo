---
verified: 2026-10-12
source: moved verbatim from CLAUDE.md in T84 (text as of c875bc9); observed in the tasks named in each paragraph
---

# Devtool commands

Every command prints one JSON object to stdout and writes PNGs to `out/` (view them). The short list is in `CLAUDE.md`; `render --help` lists every render option.

## Requirements and command list

JDK 21 is required. If `java` isn't on PATH in your shell, set `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` first.

```
./gradlew check                                           # all tests (no device needed)
./gradlew :devtool:run --args="areas"                     # list named areas
./gradlew :devtool:run --args="render --area rainier"     # hillshade + contours -> out/rainier-best-z13.png + JSON report
./gradlew :devtool:run --args="render --area rainier --source 3dep"   # sources: best (default; global, EGM2008), copernicus (global 30 m),
                                                                      #   3dep (US 10 m), 3dep-1m (US lidar, NaN = magenta), 3dep-best, swissalti3d (CH 2 m), kartverket (NO 1 m), bev (AT 1 m), hrdem (CA 1/2 m), linz (NZ 1 m), terrarium (legacy)
./gradlew :devtool:run --args="compare --area rainier --zoom 14"      # sources side by side + diff stats
DYSTOPO_3DEP_DESTRIPE=1 ./gradlew :devtool:run --args="render --area rainier --source 3dep"   # T70 Destripe on the 3DEP 1/3" grid (off by default; destripe* counters)
./gradlew :devtool:run --args="mvt dump --file tile.mvt"  # summarize a Mapbox Vector Tile (--gzip, --layer, --samples)
./gradlew :devtool:run --args="pmtiles info --file x.pmtiles"   # PMTiles header, metadata, per-zoom counts (--url for remote, --tile z/x/y)
./gradlew :devtool:run --args="tiles --area rainier --min-zoom 8 --max-zoom 13 --verify --preview-zoom 13"   # Terrarium XYZ tiles -> out/rainier-best/
./gradlew :devtool:run --args="render --area rainier --shading multi --slope-overlay"   # shading hillshade|multi|none; CalTopo-style slope bands
./gradlew :devtool:run --args="sources --ids best"        # data source registry; composed attribution for a source
./gradlew :devtool:run --args="tour --zooms 9,12"          # world sanity sweep: areas.json expectedElevation/tags -> out/tour/summary.json + contact-sheet.png; exit 1 on failure
./gradlew :devtool:run --args="gpx info shared/src/commonTest/resources/gpx/skyline-loop.gpx"   # track stats; also gpx render --osm ... and gpx export
./gradlew :devtool:run --args="tour --zooms 9,12"         # world tour: every area with expectedElevation, plausibility checks, contact sheet
./gradlew :devtool:run --args="sprite"                    # MapLibre sprite sheet (out/sprite.png/.json, @2x) from DefaultOsmStyle's marker table, drawn with the renderer's MarkerShapes; serve writes it too
./gradlew :devtool:run --args="route --pack out/rainier-outdoors.pmtiles --from 46.786,-121.736 --to 46.802,-121.729"   # trail route (graph <archive>-graph.idx, e.g. rainier-outdoors-graph.idx: z11 graph tiles, DYSG v4 since T73, built on demand; --mode bike|drive, --gpx); PNG + JSON. T73 drive: --to 46.9145,-121.6425 --mode drive drives Paradise to Sunrise on the park roads (packs from outdoors profile 2; older packs answer drive_unsupported)
./gradlew :devtool:run --args="sync-sim --peers 3 --ops 2000 --seed 42 --partitions 5 --reorder"   # sync simulator: in-process peers over a faulty network; --seeds N, --store sql
./gradlew :devtool:run --args="sync with --db out/sync/a.db --peer http://localhost:8687"          # a sync replica: status, with (peer URL or .dsync file), export, import, import-gpx, gpx
./gradlew :devtool:run --args="render --help"             # all render options
```

## Fonts in PNGs

PNG text (labels, contact sheets) is drawn with the bundled Noto Sans TTFs (`serverlib/src/main/resources/fonts/`, OFL; `RenderFonts`), never the platform `SansSerif`, so goldens match on every OS without installing fonts.

## Working directory, cache and output

`devtool` runs with the repo root as working directory. Downloads are cached in `cache/` (or `$DYSTOPO_CACHE`), output goes to `out/`. Both are gitignored. **View output PNGs** to check rendering; several bugs so far were only visible in the image.
