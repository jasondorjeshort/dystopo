# 0049. The routing graph shipped with a pack, and a streaming graph build

Date: 2026-10-30
Status: draft

## Question

Every pack's trail graph is built on the phone from the pack's vector archive, in one pass that holds
the whole network in memory (`RoutingGraphBuilder.build`), on first use (T149). T149 measured that
build at 48 MiB plus 300 B per routable vertex (fitted, garbage included; the guard adds x1.5) and
fenced it with a heap guard: on the API 35 emulator's 576 MB heap the one-pass build tops out near a
1 degree rural box, and T149's z10 city pack (1.23 million vertices, built on the phone with no
server) is refused with a sentence (estimate 630 MB against 475 MB free). T149's research found no
surveyed app that builds its routing graph on the phone: the field ships a prebuilt structure the
phone reads in pieces. This proposal decides three things together, because each changes what the
other two must do: (a) whether a server-built pack carries its graph, in which format, announced
how, and what older builds do with it (a format other phones and the server read, ADR 0033 §4);
(b) what the phone's own builder keeps doing; (c) whether the build streams, partitioned by the
`GraphTiler.ZOOM` (z11) tiles it already writes. In ten years the answer should let a phone route
across any pack it can hold on disk, whichever path brought the pack, without ever building more than
one graph tile's worth in memory, and let a server, a peer or the phone itself be the graph's maker
with the same file and no further format change.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing
  (`docs/PLANNING.md` › Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL);
  offline first; every feature agent-drivable (devtool command, debug route); agents write all code;
  the design goals (ADR 0025, principle 5: routing is tiled, not per pack).
- The graph file exists and is already read in pieces: `<vector archive>-graph.idx` beside the vector
  archive, `DYSG` version 6 (`GraphTileFile`): a checksummed header with a tile directory at z11, then
  one checksummed body per tile; readers open the header by ranged reads and load tiles on demand into
  one LRU (`GraphTileCache`, 32 MB). Every reader (the phone's `RoutePlanner.run`, the server's
  `RouteRoutes.openOrBuild`, `devtool route`) accepts a file found there when its version is 6, its
  tile zoom is 11 and its two fingerprints (`RoutingGraphBuilder.fingerprint` of the vector and DEM
  archive headers) match the archives beside it; anything else is rebuilt.
- The graph is derived data (ADR 0033 §2): invalidated and rebuilt by a version, never misread; a
  mismatch between builds refuses plainly with a reason (§3). It is never part of a pack's identity.
- Readers that a shipped graph must not break: the pack catalog (`PackCatalog.parse`: an entry with a
  file of unknown `role` is skipped whole, and `contentHash` is checked over every listed file), the
  area manifest (`ManifestItem.parse`: an unknown item `kind` throws; the `graph` item is a `LocalItem`
  that reads `from`, `available` and `note` and ignores other keys), a peer's catalog (T78, the same
  document), and `RegionDownload`, which skips a `pack` item whose role it does not know.
- Packs reach a phone five ways: a server build through `POST /area` or `/build` (`BuildRoutes`), a
  server's catalog (`PackCatalogClient`), the phone's own build with no server (`runLocal`,
  `LocalPackBuild`, from public tiles: T149's refused city pack came this way), an imported file
  (`PackManager.importFiles`), and a peer (T78). Tile areas (corridors, polygons) carry no graph
  (ADR 0030, ADR 0041); this proposal does not change that.
- Routes must follow what the map draws: the graph comes from the same vector pack, not from a second
  import of the network (PLANNING › Routing, T77's reason for rejecting a second network).
- Memory (T149, merge dbe6e69): the Rainier outdoors pack 57,826 vertices, peak growth 65.1 MB in
  5.8 s; a 1 degree by 0.7 degree box around Rainier 346,512 vertices, 143.2 MB in 17.6 s; the
  Washington 3 degree box 124 MB of archive, about 7.9 million vertices, did not fit the device and
  needed a JVM heap of 2.25 GB (1,127 s). `GraphTileFile.encode` also builds the whole file in memory
  before writing it, a second peak on top of the graph.
- No Wi-Fi gates (ADR 0023): extra bytes are shown, not gated. Settings only where practice splits
  (ADR 0044); the research found no split.

## Prior art

From T149's technical survey (2026-10-27, ADR 0044, `out/wave21-research/T149-graph-build.md`,
web sources only, nothing run); items marked (k) are general knowledge the survey did not fetch.

| Source | Where the graph is built | How the device reads it | Memory bound | URL |
|---|---|---|---|---|
| BRouter (used by Locus Map) | Server, weekly, from the planet; `rd5` segment files of 5 by 5 degrees | Downloaded per segment; no on-device build | The 5 degree cell split; JVM flags only | https://brouter.de/brouter/offline.html |
| OsmAnd | Desktop map creator or server; routing is a section of the `.obf` map file | Ships with the map ("uses predownloaded maps as data source") | The creator warns of high RAM and advises clipping extracts | https://docs.osmand.net/docs/user/navigation/routing/osmand-routing |
| Organic Maps, Maps.me | The generator; car routing a separate `.mwm.routing` made on request | Prebuilt; whether it ships with every map: not established | Not established | https://github.com/mapsme/omim/blob/map-downloader/docs/MAPS.md |
| Mapbox Navigation | Mapbox servers; TileStore "copies the routing data from the server" | Regions loaded ahead of time as routing tiles (15 km radius 42 to 90 MB) | Not a device build | https://docs.mapbox.com/android/navigation/v2/guides/advanced/offline/ |
| Valhalla | `valhalla_build_tiles`, off the device, from a PBF | Graph stored as tiles; clients fetch tiles into a disk and memory cache | Tiling is the stated reason: "limits on how much of the graph resides in memory at any one time" | https://valhalla.github.io/valhalla/concepts/why-tiles/ |
| GraphHopper | A server import step | Serves from RAM or memory-mapped files | MMAP halves a planet import's heap but takes days | https://github.com/graphhopper/graphhopper/blob/master/docs/core/deploy.md |

Common practice: no surveyed app builds a routing graph from map data on the phone; the graph is built
on a server or by a generator and delivered with the map or as its own download, and the phone reads
it in pieces (cells, tiles, sections). Builders bound memory by partitioning (Valhalla, BRouter) or by
running on large machines with memory-mapped storage (GraphHopper, OsmAnd's creator). Outliers:
Dystopo, which builds on the phone from vector tiles in one pass; and a separate routing network
imported from OSM is universal in the sample, where Dystopo derives the graph from the drawn vector
pack. Not established in the survey: how any of them versions its routing data against the app
(BRouter and OsmAnd both refuse files of another format version, (k), Claims to verify); BRouter's
and OsmAnd's on-device routing memory; Organic Maps' download contents; Valhalla's build memory;
Komoot, Gaia and AllTrails. No source estimates a build's peak heap from a header (T149 measured its
own). BRouter (MIT), Valhalla (MIT, k) and GraphHopper (Apache-2.0, k) are design references only;
nothing is copied, and OsmAnd is GPL.

## Options

Three parts: where the graph is made (A to D), what ships and how it is announced (B's shape against
E and F), and how a build bounds its memory (C against the rejected G).

### A. Keep today's split: the phone builds, in one pass, behind the guard

Every pack's graph is built on the phone at first use, refused past `HeapGuard`'s estimate (T149).
Meets every format constraint by changing nothing. Cost: none. Risk: the measured ceiling stays (a
1 degree rural box, no city pack at z10 on a 576 MB heap; on a phone with a smaller heap, less); the
server's own build (`RouteRoutes`) keeps needing a 2.25 GB heap for a 3 degree box. Nobody in the
field does this. Exit cost: none. Moving parts: none. Settings: none.

### B. A server-built pack ships the graph file it already knows how to write

The pack build (`BuildRoutes.run`) ends with a `graph` phase that writes `out/<name>-outdoors-graph.idx`
with today's builder and encoder (the file `RouteRoutes` already writes there on a first route), its
SHA-256 sidecar beside it, before the job is `done`. The graph is announced as **derived data, never as
a pack file**:

- The catalog entry gains `"derived": [{"kind": "graph", "file": "<name>-outdoors-graph.idx", "url":
  "/out/...", "bytes": n, "sha256": "...", "format": "DYSG", "version": 6, "tileZoom": 11,
  "fingerprint": "...", "demFingerprint": "..."}]`, outside `files`, so a v0.1.14 phone (which skips an
  entry with an unknown file role and checks `contentHash` over `files`) reads the entry unchanged and
  builds the graph itself as today; `contentHash` stays the hash of the archives, so a rebuilt graph is
  not a new pack. The catalog's `version` stays 1. The list shape lets the search index or the drive
  overlay's cell files (PLANNING › Routing, "Not yet") ride later with no new field.
- The area manifest's `graph` item keeps its kind and gains the same fields (`file`, `url`, `bytes`,
  `sha256`, `version`, `tileZoom`, the fingerprints) with `onDevice` false; an old reader parses it as
  a `LocalItem` and ignores them, so the manifest's version is the lowest that holds the rest (ADR
  0041's rule 1, the `versions` precedent). A new item kind is not used: the old parser throws on one.

A new phone fetches the graph after the pack's archives verify, through the same resumable, verified
downloader (`PackDownloader`, not a `PackRole`, so `PackNames`, `PackPrecedence` and the tile service
never see it), only when `version` and `tileZoom` are ones it reads and the fingerprints match the
archives it holds, and writes it under `RoutingGraph.fileNameFor` beside the vector archive, where
`RoutePlanner` already opens it with no build. A graph whose version it does not read is not fetched;
the phone builds its own (the old behaviour, under the guard), and `GET /route/status` says
`source: shipped|built` and why. Version skew is therefore never worse than today and never a misread:
the reader's existing refusal ("routing graph version 7, expected 6") is the backstop. A downgraded
phone finds a v6 file it reads; a future v7 file on a downgraded phone is refused and rebuilt (derived
data). The phone builder stays for every other path: the no-server build, imports, peers that publish
no graph, servers older than this change, and a failed or skipped graph download.

Who else: everyone in the prior-art table ships a prebuilt structure; the sibling file is BRouter's
and Mapbox's shape (a routing download beside the map) rather than OsmAnd's section inside the map
file. Cost: one build phase on the server, two JSON fields, one download step and a wait in
`RoutePlanner` (a route started while the graph is downloading waits for it instead of building).
Size: Rainier's graph was 400,760 bytes (version 4) for an outdoors archive of a few megabytes; the
bytes for large packs are a claim to verify. Risk: it does not touch T149's refused case (a pack built
on the phone), and the server still builds in one pass (2.25 GB of heap for a 3 degree box, adding
minutes to the job: 1,127 s on the JVM, partly near its heap limit).
Exit cost: low. Old readers already ignore the fields, so removing them means phones build again; the
`derived` name stays reserved.
Moving parts: the `graph` phase and sidecar in `BuildRoutes`; the two fields in `serverlib`'s
`PackCatalog` and the area manifest; the phone's graph download and the planner's wait; fixtures of
v0.1.14's parsers reading the new documents. Settings: none (bytes shown with the pack, ADR 0023; the
research found no app that lets users skip the routing data).

### C. A streaming build partitioned by the z11 graph tiles; nothing ships

`RoutingGraphBuilder` builds one z11 tile at a time and writes each body as it goes, for every caller
(phone, server, devtool). It rests on a property the code already has: the builder clips every line to
its z14 tile, so no edge spans two z11 tiles and a pack graph has no cuts at z11 (`GraphTiler` KDoc;
PLANNING › Graph tiles). Per partition, in row order:

1. Read the z14 tiles inside the z11 tile (at most 64, through the PMTiles directory), accumulate,
   snap, add crossings, split, sample the DEM for ascent and descent, exactly as today on that subset.
2. **Exact twins at the borders.** Within one file the router matches twins exactly (`Router` calls
   `twin(..., tolerant = false)` when both pieces share a `GraphSource`), and the one-pass build gets
   that from snapping both sides of a border to one vertex. The streaming build keeps a border table:
   the vertices on the south and east edges of the tiles done so far (one row of tiles at a time), and
   a vertex clipped onto a border snaps to the table's vertex within `SNAP_M` of the same level, so both
   bodies hold the same microdegrees. The table is released row by row.
3. Write the body (`RoutingGraph.writeBody` and its checksum) to a staging file and keep only its
   directory entry; drop the partition's arrays.
4. **Components** (T89's summary, which the router trusts as conclusive in a one-file network): each
   partition labels its own foot and drive components and records which border nodes carry which
   label; a union-find over (partition, label) through the border twins gives the global labels, the
   open flag coming from the archive's tile set as today; a second pass reads, relabels and rewrites
   each body, one at a time.
5. Write the header and directory, then the bodies, to `<file>.tmp` and rename, as today.

The result is the version 6 layout, so no reader changes and no version is bumped; node order inside a
tile differs from the one-pass build, so files are equal in routes, not in bytes. A vector archive whose
deepest zoom is below z11 (packs normally reach z14, `MAX_ZOOM`, but a pack built with a low `maxZoom`
could stop earlier) is partitioned at its read zoom and cut into z11 bodies with `GraphTiler.split`,
as now. Peak heap becomes the base plus the densest
partition: the guard's estimate moves from the whole archive to the densest z11 tile (the per-tile
vertex count from the sample, or from the directory's tile byte lengths scaled by the sampled
vertices per byte, a claim to verify), and the build asks `HeapGuard` before each partition, so it can
wait between tiles instead of being refused whole. A further cut (z12 or z13 sub-partitions merged into
one z11 body through the same border table) is possible without a format change if the densest city
tiles still exceed a phone's heap.
Who else: Valhalla's and BRouter's shape (the research's recommendation (c)). Cost: the largest single
piece of work here: the border table, the two passes, a staging writer, and tests that the streaming
and one-pass graphs route alike; build time a little above one pass (the second pass rereads each body
once). Risks: a crossing or a snap within `SNAP_M` of a z11 border that the one-pass build joins and
the partitioned one misses (the border table must hold border-near segments, not just vertices, or the
difference must be shown to be nil on real packs); the per-tile estimate under-reading a dense tile.
Benefits beyond the phone: the server and `devtool route build` lose the 2.25 GB requirement.
Exit cost: low. The file is unchanged, so reverting to the one-pass builder rewrites one class and
migrates nothing.
Moving parts: the partitioned builder and its staging writer in `shared` (a `GraphTileWriter` taking a
`TileStaging`-like sink, as `PmtilesWriter` does); the per-partition estimate in `measure`; the guard
per partition in `RoutePlanner`; progress in parts ("part 12 of 40"). Settings: none.

### D. Both: the server ships a graph made by the streaming builder; the phone builds the same way when nothing ships

C's builder everywhere, and B's announcement and download for server-built and catalog packs. The phone
uses a shipped graph when one matches, else builds by partitions. Cost: B plus C, but B's server build
becomes C's call, so B shrinks to the phase, the fields and the download. Exit cost: as B and C, each
separable. Moving parts: as B and C. Settings: none.

### E. The graph as a third archive of the pack, a PMTiles file with role `graph`

`<name>-graph.pmtiles` (tile type unknown, z11 tiles holding today's bodies, the graph header's fields
in the metadata), listed in the catalog's `files` and in the manifest as a `pack` item, downloaded and
verified as an archive. Reuses PMTiles' directory and ranged reads. Against it: v0.1.14 skips the whole
catalog entry (unknown role), so old phones lose the pack from their list, not just the graph, which ADR
0033 §3 forbids without a refusal sentence, and the content hash would change meaning; `PackRole`,
`PackNames`, `PackPrecedence`, the downloader's roles, the peer catalog and the tile service all learn a
third role; and the `DYSG` reader, its version history and its rebuild path are replaced for no gain in
reading (it already reads by ranges). Who else: OsmAnd's and Organic Maps' "routing inside the map
download" in spirit. Exit cost: high (a pack role in five places and every phone's files).
Moving parts: a third role everywhere, a PMTiles-wrapped graph reader. Settings: none.

### F. Graph tiles served one by one, from a server-wide graph

`GET /graph/11/{x}/{y}` from a graph the server builds over its extracts, fetched and pinned like map
tiles, so corridors and polygons route too (ADR 0030's and ADR 0041's supersede trigger, "graph tiles
become a manifest kind"). Mapbox's and Valhalla's model. Against it now: the server-wide graph is a
second network unless it is built from the same vector tiles the phone draws, the server must keep
every version it ever served or the phone must refetch on a change, the phone gains a graph tile cache
kind and its eviction, and nothing here is needed to fix the measured failure. It is the natural next
step after D (the same bodies, served singly) and is recorded for that.
Exit cost: an endpoint, a cache kind, a manifest kind. Moving parts: all three plus the server graph.
Settings: none.

### G. Rejected for the record

The graph inside the vector archive (an MVT layer or a metadata blob): every reader of the map archive
would carry it, the archive's fingerprint and hash would change with each graph change, and old
builds would not refuse it plainly. A one-pass build with memory-mapped or disk-backed arrays: the
research found it no simple fit on the JVM and Android, and partitioning is the field's answer.

## Recommendation

**D, built as C then B, in wave 23.** C first, because it is the only option that fixes T149's refused
case (a pack built on the phone with no server) and it serves every path, the server's included; B
second, because once the server's build is bounded, shipping the result saves every phone the minutes
and the battery of building it, which is the field's practice for packs a server makes.

First step (task one, `shared` and devtool): the partitioned builder writing the version 6 layout,
`devtool route build --stream` beside the one-pass path, and the tests: the Rainier outdoors pack built
both ways gives the same golden route (Paradise to Panorama Point: 2,953.3 m, +416.4 m, 223 points),
node and edge counts within 0.5 percent, every z11 border twin exact (a test reads each body's border
nodes and finds the twin at the same microdegrees in the neighbour), the component labels equal up to
renaming; a synthetic net with a bridge cut at a z11 border keeps its level. Then the guard by the
densest partition and the per-partition admission in `RoutePlanner` (`GET /route/status` gains
`parts`, `part`, `densestVertices`), the one-pass path kept behind `POST /route/build?onepass=1` for
measurement for one wave, then removed. Task two (`serverlib`, the phone's download): the `graph`
phase in `BuildRoutes` with its sidecar, the `derived` field in the catalog and the fields on the
manifest's `graph` item (SERVER.md field by field), the phone's fetch after the archives and the
planner's wait, fixtures of the v0.1.14 parsers reading both documents unchanged, and `source:
shipped|built` on the status route.

Where this departs from the field, and why: (1) the phone keeps a builder, which no surveyed app has,
because packs reach it without a server (the no-server build, imports, peers) and an alpha server may
be older than the phone; (2) the graph is derived from the vector pack the map draws, not imported
from OSM separately, so routes follow the drawn trails and one network serves map and router (T77);
(3) the graph ships as a sibling file announced as derived data, not as a section inside the map file
(OsmAnd, Organic Maps), so the PMTiles archives stay readable by other tools and old phones and the
pack's identity does not move when only the graph changes; (4) version skew falls back to building
rather than refusing the pack, which ADR 0033 §2 allows for derived data.

No new strings beyond the progress line ("Building the trail network, part 12 of 40") and, for a
shipped graph, "Trail network downloaded with the map" in the route status card, listed for the owner
(ADR 0031). No setting (ADR 0044): the research found no app that splits on this, and both behaviours
only remove a failure.

## What would change this recommendation

- The densest z11 tile of a large city pack still exceeds a 512 MB phone's free heap after streaming:
  then the sub-partition (z12 or z13 merged into one body) is built in task one, not later.
- The streaming build cannot make the z11 border twins exact on real packs (a crossing or snap the
  one-pass build joins at a border): then the file gains a header capability bit for tolerant joins
  within one file and the version moves to 7, with the old refusal message kept.
- Shipped graphs turn out large against their packs (over a third of the vector archive): then B waits
  for the owner's view of the bytes, and C alone ships.
- The owner wants corridors and polygons to route offline soon: then F follows D directly (ADR 0030's
  trigger).
- Prediction that can fail: the streaming build of the Rainier outdoors pack routes Paradise to
  Panorama Point identically to the one-pass build (2,953.3 m, +416.4 m, 223 points).
- Prediction that can fail: T149's z10 city pack (1.23 million vertices, refused today at 630 MB)
  builds on the 576 MB emulator heap, with its densest z11 tile under 400,000 vertices, a per-partition
  estimate under 260 MB and a measured peak growth under 200 MB.
- Prediction that can fail: the Washington 3 degree box builds by partitions on the JVM with a 512 MB
  heap in under 600 s, and on the emulator in under 15 minutes.
- Prediction that can fail: a shipped graph is under 20 percent of its vector archive's bytes on the
  Rainier pack and the Washington 3 degree box.
- Prediction that can fail: v0.1.14's catalog and manifest parsers, fed the new documents as fixtures,
  list the same packs, hashes and items as before, and a v0.1.14 phone downloads a new server's pack and
  builds its own graph.

## Claims to verify

- Readers accept any graph file beside the vector archive whose version is 6, tile zoom 11 and both
  fingerprints match, with no check of who built it: `RoutePlanner.run` (the `a.source.fingerprint`
  test), `RouteRoutes.openOrBuild`, `RouteCommand.kt`.
- A v0.1.14 catalog parser skips a whole entry with an unknown file role and checks `contentHash` over
  `files`: `shared/.../packs/PackCatalog.kt`, `CatalogFile.parse` and `CatalogPack.parse`; unknown
  top-level keys of an entry are ignored there (the `publisher` precedent in its KDoc).
- A v0.1.14 manifest parser throws on an unknown item kind and ignores extra keys on a `graph` item:
  `shared/.../areas/AreaManifest.kt`, `ManifestItem.parse` and `LocalItem`.
- `RegionDownload.run` skips a `pack` item of unknown role: `app/online/RegionDownload.kt` (the
  `PackRole.entries.firstOrNull` line).
- `/out/` serves any file there with byte ranges, so the graph file is fetchable today:
  `serverlib/.../LiveTiles.kt` (the `/out/` line) and `ServeStatic.kt`.
- Deleting a pack deletes its graph files: `app/packs/PackManager.kt` (`RoutePlanner.graphFiles`).
- No edge of a pack graph spans two z11 tiles: `RoutingGraphBuilder` step 1 (clipping to the z14
  square) and the `GraphTiler` KDoc ("at ZOOM there are normally no cuts at all"); a test on the
  Rainier pack can assert `GraphTiler.split` adds no cut node.
- Within one file twins are matched exactly, across files within `JOIN_M`: `routing/Router.kt` (the
  two `twin(..., tolerant = q.graph.source != p.graph.source ...)` calls) and `TiledGraph.kt`.
- The component labels are global in a file and conclusive when one file makes up the network:
  `RoutingGraphBuilder` step 6 and `GraphPieces.fileCount` in `TiledGraph.kt`.
- `GraphTileFile.encode` holds the whole file in memory before it is written: `routing/GraphTiles.kt`,
  called by `RoutePlanner.build` and `RouteRoutes.openOrBuild`.
- T149's numbers (fit, packs, the refused city pack, the 3 degree box on the JVM): the merge commit
  `git log -1 dbe6e69 --format=%B` and `RoutingGraphBuilder`'s `ESTIMATE_*` constants.
- Rainier's graph file size (400,760 bytes at version 4): PLANNING › Routing, the File paragraph. The
  version 6 size of that pack and of the 3 degree box: not established; `devtool route build` reports
  the bytes.
- The routable vertices of the densest z11 tile of T149's city pack and of the 3 degree box: not
  established; a devtool count per partition settles it before the guard's per-partition constant is
  fixed. Whether a z14 tile's byte length tracks its routable vertices well enough for the estimate:
  not established.
- Whether the server's JVM has the heap for a one-pass build: `server/build.gradle.kts` and
  `devtool/build.gradle.kts` set no `-Xmx`, so the JVM default (a quarter of physical memory, (k))
  applies; not measured on a server.
- The prior art rows: the research file and its URLs. BRouter and OsmAnd refusing data files of another
  format version is general knowledge (k), not from the survey: BRouter's `lookups.dat` version against
  `rd5` files, OsmAnd's `.obf` version; the critic can check https://github.com/abrensch/brouter and
  https://docs.osmand.net.
- How long a server pack build already takes for the 3 degree box (tilebuild plus DEM), against which
  the graph phase's minutes are weighed: not established here.

## Critic's objections and responses

(none yet)
