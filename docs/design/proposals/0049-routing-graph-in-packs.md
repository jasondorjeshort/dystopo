# 0049. The routing graph shipped with a pack, and a streaming graph build

Date: 2026-10-30
Status: under review (revised on the critic's review, `0049-critic-review.md`)

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
  archive headers) match the archives beside it; anything else is rebuilt. The tile zoom is a header
  field, but every reader hard-checks it against `GraphTiler.ZOOM` and `TiledGraph` requires one zoom
  across archives (critic, objection 7).
- The graph is derived data (ADR 0033 §2): invalidated and rebuilt by a version, never misread; a
  mismatch between builds refuses plainly with a reason (§3). It is never part of a pack's identity
  and never a `PackRole` (`PackHash.combine` iterates `PackRole.entries`, so a role would change every
  pack's hash; `PackNames`, `PackPrecedence`, the downloader and the tile service all enumerate roles).
- Readers that a shipped graph must not break: the pack catalog (`shared`'s `PackCatalog.parse`, used
  by the phone and, through `CatalogPack.of`, by a phone's peer catalog: an entry with a file of
  unknown `role` is skipped whole, and `contentHash` is checked over every listed file), the area
  manifest (`ManifestItem.parse`: an unknown item `kind` throws and fails the whole manifest; the
  `graph` item is a `LocalItem` that reads `from`, `available` and `note`, ignores other keys, and
  writes `onDevice: true` unconditionally), and `RegionDownload`, which skips a `pack` item whose role
  it does not know.
- Packs reach a phone five ways: a server build through `POST /area` or `/build` (`BuildRoutes`), a
  server's catalog (`PackCatalogClient`), the phone's own build with no server (`runLocal`,
  `LocalPackBuild`, from public tiles: T149's refused city pack came this way), an imported file
  (`PackManager.importFiles`, which accepts at most the two `.pmtiles` archives today), and a peer
  (T78). Tile areas (corridors, polygons) carry no graph (ADR 0030, ADR 0041); this proposal does not
  change that.
- Routes must follow what the map draws: the graph comes from the same vector pack, not from a second
  import of the network (PLANNING › Routing, T77's reason for rejecting a second network).
- Memory (T149, merge dbe6e69, `GraphBuildEstimateTest`): the Rainier outdoors pack 57,826 vertices,
  peak growth 65.1 MB in 5.8 s; a 1 degree by 0.7 degree box around Rainier 346,512 vertices,
  143.2 MB in 17.6 s; the Washington 3 degree box 124 MB of archive, about 7.9 million vertices, did
  not fit the device; on the JVM a 2 GB heap failed, 2.25 GB built it in 1,127 s, 2.5 GB in 729 s and
  2.75 GB in 489 s (the 1,127 s is garbage-collection bound at the smallest heap that worked, not the
  build's cost). The record also holds a statewide Washington outdoors pack (245 MB of archive) whose
  graph built in 49 to 70 s on a desktop with the JVM's default heap (PLANNING › Routing, T77 and T89).
  `GraphTileFile.encode` also builds the whole file in memory, two copies of every body at its end,
  before writing it: a second peak on top of the graph.
- The server's heap: no `-Xmx` in `server/build.gradle.kts` or `devtool/build.gradle.kts`, so
  `devtool serve` gets the JVM default (a quarter of physical memory); the server image sets
  `JAVA_OPTS="-XX:MaxRAMPercentage=60"` (`server/Dockerfile`). The largest box a server builds is
  3 degrees a side (`DYSTOPO_BUILD_MAX_SPAN_DEG`, `RegionDownload.packFits`).
- No Wi-Fi gates (ADR 0023): extra bytes are shown, not gated. Settings only where practice splits
  (ADR 0044); the research found no split.

## Prior art

From T149's technical survey (2026-10-27, ADR 0044, `out/wave21-research/T149-graph-build.md`,
web sources only, nothing run); items marked (k) are general knowledge the survey did not fetch; the
critic fetched the licence files and two readers' sources (claim 15 of the review).

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
pack. Versioning against the app (critic's check of the sources): BRouter refuses a segment file
whose lookup-data version differs from its `lookups.dat` ("lookup version mismatch (old rd5?)") and
one whose index checksum fails, but does not compare a format version with a constant; OsmAnd's
reader only checks that a file's trailer repeats the version its header states. The project's own
rule (a version byte refused by name, then a rebuild) is stronger than either and needs no precedent.
Not established in the survey: BRouter's and OsmAnd's on-device routing memory; Organic Maps' download
contents; Valhalla's build memory; Komoot, Gaia and AllTrails. No source estimates a build's peak heap
from a header (T149 measured its own). BRouter (MIT), Valhalla (MIT) and GraphHopper (Apache-2.0) are
design references only, their licence files checked by the critic; nothing is copied, and OsmAnd is GPL.

## Options

Three parts: where the graph is made (A to D), what ships and how it is announced (B's shape against
E and F), and how a build bounds its memory (C against the rejected G).

### A. Keep today's split: the phone builds, in one pass, behind the guard

Every pack's graph is built on the phone at first use, refused past `HeapGuard`'s estimate (T149).
Meets every format constraint by changing nothing. Cost: none. Risk: the measured ceiling stays (a
1 degree rural box, no city pack at z10 on a 576 MB heap; on a phone with a smaller heap, less).
Nobody in the field does this. Exit cost: none. Moving parts: none. Settings: none.

### B. A server-built pack ships the graph file it already knows how to write

The pack build (`BuildRoutes.run`) gains a `graph` phase **after** `publish`, with today's one-pass
builder and encoder (the file `RouteRoutes` already writes there on a first route): it writes
`out/<name>-outdoors-graph.idx` and its SHA-256 sidecar. A failure of the phase is a note on the job,
never a failed pack (`run`'s catch today fails the job and deletes both archives, so the phase sits
outside it), and the catalog then omits the graph; the job is `done` only when the phase has ended
either way, so `RegionDownload`'s poll waits for the graph with the phase named. The sidecar is keyed
like the archives' (size and time) or deleted whenever the graph is rewritten (`RouteRoutes.openOrBuild`
and the server's `POST /route/build` rewrite it through `.tmp` today and touch no sidecar), so a
rebuild never ships a stale hash.

Cost on the server: one-pass memory by the guard's fit (48 MiB + 300 B per vertex, x1.5: about 3.6 GB
for the 3 degree box by the fit; 2.25 GB measured as the floor) and time from the measured series (489
to 1,127 s for the 3 degree box by heap, 49 to 70 s for the statewide pack on a desktop heap); the
server image already takes 60 percent of its container's memory. A server too small for its largest
pack notes the failed phase and ships no graph; the phone builds, as today.

The graph is announced as **derived data, never as a pack file**:

- The catalog entry (`CatalogPack.parse` and `json` in `shared`, written by `serverlib`'s catalog and by
  a phone's peer catalog through `CatalogPack.of`) gains a `derived` list outside `files`:
  `{"kind": "graph", "file": "<name>-outdoors-graph.idx", "url": "/out/...", "bytes": n, "sha256": "...",
  "format": "DYSG", "version": 6, "key": {"tileZoom": 11, "fingerprint": "v1:...", "demFingerprint":
  "v1:..."}}`. The common keys sit at the top and each kind's match keys under `key`, so a later
  `search` or overlay-cell entry carries its own `key` and no nulls; a reader fetches only a kind it
  knows whose `key` it can compare. A v0.1.14 phone (which skips an entry with an unknown file role and
  checks `contentHash` over `files`) reads the entry unchanged and builds the graph itself;
  `contentHash` stays the hash of the archives, so a rebuilt graph is not a new pack. The catalog's
  `version` stays 1. `fingerprint()`'s `v1:` tuple becomes a wire value: a future `v2:` makes every
  older server's shipped graph unmatched and rebuilt, which heals itself, and the ADR records it.
- The area manifest's `graph` item keeps its kind and gains the same fields (`LocalItem` gains them,
  or a `graph` item with a `url` parses to a file-shaped item; `onDevice` is written false for it,
  which today's `json()` cannot do). An old reader parses it as a `LocalItem` and ignores them, so the
  manifest's version is the lowest that holds the rest (ADR 0041's rule 1, the `versions` precedent:
  here an old reader ignoring the fields builds its own graph, the correct behaviour). A new item kind
  is not used: the old parser throws on one and fails the whole manifest. This is not "graph tiles as a
  manifest kind" (the supersede trigger of ADR 0030, 0041 and 0047): those triggers stay on F.

On the phone, a graph is fetched (1) after a pack's archives verify (`startCatalog`, `RegionDownload`),
(2) with a pack update, and (3) when `ensureRegion` finds a pack's graph missing, stale or refused and a
server is set: it asks the catalog client's cached document for a matching `derived` entry before it
builds (`CatalogStatus` keys on the archives' hash, so a phone would otherwise never learn the server
ships one). It fetches only when `version` and `key` are ones it reads and the fingerprints match the
archives it holds, through `PackDownloader` with a role-less file job (`FileJob` and `startCatalog` are
`PackRole`-typed today; resume matches by role label), and writes the file under
`RoutingGraph.fileNameFor` beside the vector archive, where `RoutePlanner` already opens it with no
build. A version it does not read is not fetched; the phone builds its own (the old behaviour, under the
guard), and `GET /route/status` says `source: shipped|built` with the reason (`shipped: hash mismatch`
when a download fails its hash) and, for a shipped graph, the server's `builtAtMs`; messages that name a
file (`Router.separate`, `drive_unsupported`) name the phone's file, not the header's `sourceFile`. A
route started while the graph downloads waits while the download progresses and builds when it stalls
past `RouteSession.WAIT_MS`. Version skew is never worse than today and never a misread: the reader's
refusal ("routing graph version 7, expected 6") is the backstop. A downgraded phone finds a v6 file it
reads; a future v7 file on a downgraded phone is refused and rebuilt (derived data).

**The other makers** the Question names come with B, so the graph is "the same file from any maker":
`PackManager.importFiles` accepts an optional `<name>-outdoors-graph.idx` beside the two archives
(every reader accepts it when the fingerprints match, so `devtool route build` then an import or a push
is a shipped graph with no server), and a phone's peer catalog lists its graph under `derived` when the
graph's fingerprints match its archives. The phone builder stays for every path without a matching
graph: the no-server build, imports without one, servers older than this change or too small for the
pack, and a failed or skipped download. `RoutePlanner.graphFiles` gains the graph's `.tmp` and `.part`
(and, with C, the staging file) so a deleted pack or a crash leaves none.

Size (the record, critic's objection 1): Rainier's graph was 400,760 bytes at version 4, 12 to 15
percent of its outdoors archive and about 0.3 percent of the pack as the user sees it (the 151.0 MB
Rainier pack is 147.6 MB of DEM); the statewide Washington pack's graph was 103 to 113 MB for 245 MB of
outdoors archive, 42 to 46 percent of the vector archive. The owner's view is asked on the pack as the
user sees it (DEM plus vector plus graph); the vector-archive ratio is reported beside it. The size-first
card's pack line ("With routing and place search (a map pack)") includes the graph's bytes.

Who else: everyone in the prior-art table ships a prebuilt structure; the sibling file is BRouter's
and Mapbox's shape (a routing download beside the map) rather than OsmAnd's section inside the map
file. Risk: it does not touch T149's refused case (a pack built on the phone with no server), and the
server builds in one pass with the heap above.
Exit cost: low. Old readers already ignore the fields, so removing them means phones build again; the
`derived` name stays reserved.
Moving parts: in `serverlib`, the `graph` phase after publish, its sidecar rule and the catalog writer;
in `shared`, `CatalogPack.parse` and `json` (the `derived` list), `LocalItem` (the download fields,
`onDevice`), and the parsers' fixtures from v0.1.14; on the phone, a role-less file job in
`PackDownloader`, the three fetch points, `importFiles` taking an optional graph, the peer catalog's
`derived`, `graphFiles`' cleanup, the planner's wait rule and `source` on the status route. Strings for
the owner (ADR 0031): "Downloading the trail network" (the route card while it waits) and "Trail
network downloaded with the map" (the route status card). Settings: none (bytes shown with the pack,
ADR 0023; the research found no app that lets users skip the routing data).

### C. A streaming build partitioned by the z11 graph tiles; nothing ships

`RoutingGraphBuilder` builds one z11 tile at a time and writes each body as it goes, for every caller
(phone, server, devtool). It rests on a property the code already has: the builder clips every line to
its read-zoom tile, z14 for packs, so no edge spans two z11 tiles when the read zoom is 11 or deeper and
a pack graph has no cuts at z11 (`GraphTiler` KDoc; PLANNING › Graph tiles; T89 found one cut 7 cm past
a border before `MIN_CUT_M`). Per partition, in row order:

1. Count the routable vertices of the z14 tiles inside the z11 tile (at most 64, `routableVertices`),
   ask `HeapGuard` for that partition's estimate, then read them, accumulate, snap, add crossings,
   split, and sample the DEM for ascent and descent, exactly as today on that subset. The exact count
   costs one extra decode of those tiles and replaces any proxy (critic, objection 13).
2. **Exact twins at the borders.** Within one file the router matches twins exactly (`Router` calls
   `twin(..., tolerant = false)` when both pieces share a `GraphSource`), and the one-pass build gets
   that from snapping both sides of a border to one vertex. The streaming build keeps a border table:
   **every vertex within `SNAP_M` of the partition's border, with its level and its clip flag** (T89: a
   bridge or tunnel cut at a border snaps only to the same structure's cuts), and the segments within
   `SNAP_M` of the border, for the tiles done so far (one row of tiles at a time). A vertex of the next
   partition within `SNAP_M` of a table vertex of its level snaps to it, and a crossing whose point
   falls within `tol` of the border (`addCrossings` accepts up to `tol` past a segment's end) is found
   against the table's segments, so both bodies hold the same microdegrees. The table is released row
   by row.
3. Write the body (`RoutingGraph.writeBody` and its checksum) to a staging file and keep only its
   directory entry; drop the partition's arrays.
4. **Components** (T89's summary, which the router trusts as conclusive in a one-file network): each
   partition labels its own foot and drive components and records which border nodes carry which
   label; a union-find over (partition, label) through the border twins gives the global labels, the
   open flag coming from the archive's tile set as today (24,266 keys for the 3 degree box, kept whole);
   a second pass reads, relabels and rewrites each body, one at a time.
5. Write the header and directory, then the bodies, to `<file>.tmp` and rename, as today.

**A refused partition** (critic, objection 3; coordinator's decision): the staging file
`<file>.staging` carries a cursor (the partitions done, their directory entries, their component
records, and the border table of the current row). When a partition still exceeds the free heap after
the trim and the 10 s wait, the build stops at the cursor in state `refused` with `part`, `parts` and
`densestVertices`; the next use (after `REFUSED_RETRY_MS`) resumes from the cursor and says
`resumedFrom`. The staging file is deleted when the pack is deleted, when either archive's fingerprint
changes, or when the build completes. The sentence for this case names the part and the remedy, not the
map: "Part of this map is too dense to build the trail network on this phone (part 12 of 40). It will
try again when there is more memory; closing other apps helps." (for the owner, ADR 0031). Who lifts
it: the user, by retrying later (the next route resumes, nothing is lost); a debug build's
`POST /route/build?force=1` and `POST /route/guard?maxBytes=` as today; and the code, by the
sub-partition below, which removes the case; the release build has no override, as ADR 0041 §3's
pattern allows for a memory refusal.

**The sub-partition and the z11 pin** (objection 7): a z11 tile too dense to build is built in z12 or
z13 parts through the same border table. Whether those parts merge back into one z11 body or the file
carries z12 bodies is decided on a measurement, not by default: task one of C measures the densest z11
tile's routable vertices and its decoded `approxBytes` on T149's city pack and the 3 degree box (a
dense body is also decoded whole on read, and `GraphTileCache.trim` keeps at least one entry whatever
its size). The ADR records that `tileZoom` is a per-file header field, and that accepting 11 to 13 per
archive is a reader change with no version bump (`TiledGraph`'s one-zoom rule and `tilesIn` go per
archive); the overlay's cell grid also sits on `GraphTiler.ZOOM` (T77), so a change of the graph zoom
moves it too.

The result is the version 6 layout, so no reader changes and no version is bumped; node order inside a
tile differs from the one-pass build, so files are equal in routes, not in bytes; the one-pass and the
partitioned snap orders can still differ on degenerate chains of vertices within 1 m of a border, which
"equal in routes" tolerates and the ADR admits. A vector archive whose deepest zoom is below z11 (packs
normally reach z14, `MAX_ZOOM`, but a pack built with a low `maxZoom` could stop earlier) is partitioned
at its read zoom and cut into z11 bodies with `GraphTiler.split`, as now.
Who else: Valhalla's and BRouter's shape (the research's recommendation (c)). Cost: the largest single
piece of work here: the border table, the two passes, the staging writer and its cursor, and tests that
the streaming and one-pass graphs route alike; build time a little above one pass. Risks: the border
cases above, which only synthetic tests exercise (Rainier's 20 rural tiles have none). Benefits beyond
the phone: the server and `devtool route build` lose the multi-gigabyte requirement.
Exit cost: low. The file is unchanged, so reverting to the one-pass builder rewrites one class and
migrates nothing.
Moving parts: the partitioned builder, its staging writer and cursor in `shared` (a `GraphTileWriter`
taking a `TileStaging`-like sink, as `PmtilesWriter` does); the per-partition count and admission in
`RoutePlanner`; status fields `part`, `parts`, `densestVertices`, `resumedFrom`; progress in parts
("Building the trail network, part 12 of 40"); the staging file in `graphFiles` and its KDoc. Settings:
none.

### D. Both: the server ships a graph; the phone builds by partitions when nothing ships

B's announcement, download and makers, and C's builder everywhere once it exists (the server's phase
then calls C). The phone uses a shipped graph when one matches, else builds. Cost: B plus C, each a task.
Exit cost: as B and C, each separable. Moving parts: as B and C. Settings: none.

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
tiles, so corridors and polygons route too (ADR 0030's, 0041's and 0047's supersede trigger, "graph
tiles become a manifest kind"). Mapbox's and Valhalla's model. Against it now: the server-wide graph is
a second network unless it is built from the same vector tiles the phone draws, the server must keep
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

**D, built as B then C** (the coordinator's order after the critic). B first, with today's one-pass
builder and the server's heap: one task, no builder change in `shared`, the field's practice to the
letter, and it fixes every server-built and catalog pack and, through the import and peer makers, every
pack someone has built a graph for. C second, the only fix for a pack built on the phone with no server
(T149's refused case) and the largest piece of work with the one real correctness risk: **its launch
waits on the owner's pending answer on the board** (`docs/TASKS.md` › Owner actions pending: whether the
z10 refusal bites on the owner's own packs). If it bites, C is wave 23's second task after B merges; if
it does not, C waits until a no-server user or a server too small for its packs meets the refusal. The
ADR records the condition and who answered it.

Task one (B; `serverlib`, `shared`'s catalog and manifest types, the phone's download): the `graph`
phase after publish with its sidecar rule and failure note; `CatalogPack`'s `derived` list and the
`graph` item's fields (SERVER.md field by field); the role-less file job, the three fetch points, the
planner's wait, `importFiles` with an optional graph, the peer catalog's `derived`, `graphFiles`'
cleanup, `source: shipped|built` on the status route; fixtures of v0.1.14's catalog and manifest
parsers reading the new documents unchanged (a regression check); the strings listed. Its report carries
the version 6 graph's bytes for the Rainier pack and for the largest server build it runs, against the
vector archive and against the whole pack, before the ADR is final on bytes.

Task two (C; `shared` and devtool, then the planner): the partitioned builder writing the version 6
layout, `devtool route build --stream` beside the one-pass path, and tests that can fail: synthetic
nets with two lines crossing within 0.5 m of a z11 border, a bridge and a road meeting at a border, and
a three-vertex chain within `tol` straddling one, each built both ways with routes, node and edge counts
and component labels (up to renaming) equal; every z11 border twin exact (each body's border nodes find
their twin at the same microdegrees in the neighbour); and the Rainier outdoors pack's golden route
(Paradise to Panorama Point: 2,953.3 m, +416.4 m, 223 points) as the regression, not the proof. Then
the per-partition count and admission, the staging file's cursor and resume, the refusal's state and
sentence, `GET /route/status` gaining `part`, `parts`, `densestVertices` and `resumedFrom`; the densest
z11 tile's vertices and `approxBytes` measured on T149's city pack and the 3 degree box, and the
sub-partition either built or shown unneeded by that measurement, in the Done-when; the one-pass path
kept behind `POST /route/build?onepass=1` for measurement for one wave, then removed.

Where this departs from the field, and why: (1) the phone keeps a builder, which no surveyed app has,
because packs reach it without a server (the no-server build, imports, peers) and an alpha server may
be older than the phone or too small for its pack; (2) the graph is derived from the vector pack the map
draws, not imported from OSM separately, so routes follow the drawn trails and one network serves map
and router (T77); (3) the graph ships as a sibling file announced as derived data, not as a section
inside the map file (OsmAnd, Organic Maps), so the PMTiles archives stay readable by other tools and old
phones and the pack's identity does not move when only the graph changes; (4) version skew falls back to
building rather than refusing the pack, which ADR 0033 §2 allows for derived data.

Strings for the owner (ADR 0031): B's "Downloading the trail network" and "Trail network downloaded
with the map"; C's progress line "Building the trail network, part 12 of 40" and its refusal sentence
above. No setting (ADR 0044): the research found no app that splits on this, and both behaviours only
remove a failure.

## What would change this recommendation

- The owner answers that the z10 refusal does not bite on their packs: C waits (above); B alone ships.
- The owner answers that it bites on packs built on the phone with no server: C follows B in wave 23.
- The densest z11 tile of a large city pack still exceeds a 512 MB phone's free heap after streaming:
  the sub-partition is built in C's task, and the measurement chooses merged z11 bodies or z12 files.
- The streaming build cannot make the z11 border twins exact on the synthetic border cases: the file
  gains a header capability bit for tolerant joins within one file and the version moves to 7, with the
  old refusal message kept.
- Shipped graphs turn out over a quarter of the pack as the user sees it (DEM plus vector): B's download
  waits for the owner's view of the bytes, and the server keeps building graphs for its own routes only.
- The owner wants corridors and polygons to route offline soon: F follows D (the supersede triggers of
  ADR 0030, 0041 and 0047).
- Prediction that can fail: a v0.1.14 APK pointed at a server running B lists its packs, downloads both
  archives of a new pack and builds its own graph, with no error from the new fields.
- Prediction that can fail: with B, a phone downloading the Rainier pack from the server routes Paradise
  to Panorama Point with `source: shipped` and no build started.
- Prediction that can fail: a shipped graph is under 10 percent of its pack as the user sees it (DEM plus
  vector) on the Rainier pack and on the largest server build of B's task; the vector-archive ratio is
  reported, expected near the record's 12 to 46 percent.
- Prediction that can fail: the streaming build routes Paradise to Panorama Point on the Rainier
  outdoors pack identically to the one-pass build (2,953.3 m, +416.4 m, 223 points).
- Prediction that can fail: T149's z10 city pack (1.23 million vertices, refused today at 630 MB)
  builds by partitions on the 576 MB emulator heap, with its densest z11 tile under 400,000 vertices, a
  per-partition estimate under 260 MB and a measured peak growth under 200 MB.
- Prediction that can fail: the Washington 3 degree box builds by partitions on the JVM with a 512 MB
  heap in under 600 s, and on the emulator in under 15 minutes.

## Claims to verify

- Readers accept any graph file beside the vector archive whose version is 6, tile zoom 11 and both
  fingerprints match, with no check of who built it: `RoutePlanner.run` (the `a.source.fingerprint`
  test), `RouteRoutes.openOrBuild`, `RouteCommand.kt`. (Critic: confirmed.)
- A v0.1.14 catalog parser skips a whole entry with an unknown file role and checks `contentHash` over
  `files`, reading only named keys: `shared/.../packs/PackCatalog.kt`, `CatalogFile.parse` and
  `CatalogPack.parse`. (Confirmed.)
- A v0.1.14 manifest parser throws on an unknown item kind, failing the whole manifest, and ignores
  extra keys on a `graph` item: `shared/.../areas/AreaManifest.kt`, `ManifestItem.parse`, `LocalItem`.
  (Confirmed.)
- `RegionDownload.run` skips a `pack` item of unknown role: `app/online/RegionDownload.kt` (the
  `PackRole.entries.firstOrNull` line). (Confirmed.)
- `/out/` serves any file there with byte ranges: `serverlib/.../LiveTiles.kt` (the `/out/` line) and
  `ServeStatic.kt`. (Confirmed; it serves `.tmp` and `.building` files too.)
- Deleting a pack deletes its graph files by the current and legacy names only:
  `app/packs/PackManager.kt`, `RoutePlanner.graphFiles`. (Confirmed; B and C add the rest.)
- No edge of a pack graph spans two z11 tiles when the read zoom is 11 or deeper:
  `RoutingGraphBuilder` step 1 and the `GraphTiler` KDoc; a test on the Rainier pack can assert
  `GraphTiler.split` adds no cut node. (Confirmed.)
- Within one file twins are matched exactly, across files within `JOIN_M`: `routing/Router.kt` (the
  two `twin` calls) and `TiledGraph.kt`. (Confirmed.)
- The component labels are global in a file and conclusive when one file makes up the network:
  `RoutingGraphBuilder` step 6, `Router.separate`, `GraphPieces.fileCount`. (Confirmed.)
- `GraphTileFile.encode` holds the whole file in memory before it is written, two copies of each body
  at its end: `routing/GraphTiles.kt`. (Confirmed.)
- T149's numbers and the JVM series: dbe6e69's body, `RoutingGraphBuilder`'s `ESTIMATE_*` constants,
  `GraphBuildEstimateTest`. (Confirmed.)
- Graph sizes: Rainier 400,760 bytes at version 4 (PLANNING › Routing, the File paragraph); the
  statewide Washington pack 103 and 113 MB for 245 MB of outdoors archive (PLANNING › Routing, the T77
  and T89 paragraphs). Version 6 sizes: measured in B's task.
- The densest z11 tile's routable vertices and decoded bytes on T149's city pack and the 3 degree box:
  not established; measured in C's task before the sub-partition's shape is chosen.
- The server's heap: no `-Xmx` in `server/build.gradle.kts` or `devtool/build.gradle.kts` (the JVM
  default); `server/Dockerfile`'s `-XX:MaxRAMPercentage=60`. (Confirmed.)
- `PackDownloader.FileJob` and `startCatalog` are `PackRole`-typed and resume matches by role label;
  `importFiles` takes at most two `.pmtiles` files; `BuildRoutes.run`'s catch fails the job and deletes
  both archives: `app/packs/PackDownloader.kt`, `PackManager.kt`, `BuildRoutes.kt`. (Confirmed.)
- The prior-art rows: the research file and its URLs; BRouter's and OsmAnd's version handling from their
  sources (`btools/mapaccess/PhysicalFile.java`, `BinaryMapIndexReader.java`), checked by the critic.
- How long a server pack build already takes for the 3 degree box (tilebuild plus DEM), against which
  the graph phase's minutes are weighed: not established (Rainier's outdoors pack builds in 33 s,
  `machines.md`).

## Critic's objections and responses

From `0049-critic-review.md` (0 blocking, 7 serious, 10 minor). The coordinator decided two points
for the revision: the order (B first, C conditional on the owner's answer) and C's refusal path.

1. **Serious, the size prediction contradicted by the record.** Response: the Size paragraph in B now
   states the record (Rainier 12 to 15 percent of the vector archive, the statewide pack 42 to 46
   percent), the prediction and the trigger are restated against the pack as the user sees it (DEM plus
   vector), the vector ratio is reported beside it, and the owner is asked on that denominator. Since B
   now comes first, the version 6 bytes are measured in B's task, not C's. Resolution: accepted with
   change (the measurement moves to B's task).
2. **Serious, B's server cost misstated and the order resting on it.** Response: the Constraints and B
   give the guard's fit as the heap requirement and the measured series as time, with the Dockerfile's
   setting; the order is the coordinator's: B first with today's builder, C conditional on the owner's
   pending answer, recorded in the Recommendation and in "What would change". Resolution: accepted with
   change (B first whichever way the owner answers; the answer decides only whether C launches).
3. **Serious, the per-partition refusal undefined.** Response: C now defines the staging file and its
   cursor, resume on the next use with `resumedFrom`, deletion rules, the sentence naming the part, and
   who lifts it; the sub-partition is in C's Done-when (built, or shown unneeded by the measured densest
   tile). Resolution: accepted.
4. **Serious, the border table underspecified, the exactness test unable to fail.** Response: the table
   is every vertex within `SNAP_M` of the border with its level and clip flag, plus the segments within
   `SNAP_M` for crossings; the three synthetic border tests are in C's task and the Rainier goldens are
   the regression. Resolution: accepted.
5. **Serious, installed and updated packs undefined.** Response: B fetches after a download, with an
   update, and from `ensureRegion` through the cached catalog before building; `source` says which path
   and why. Resolution: accepted.
6. **Serious, the moving parts omit the `shared` types and two makers.** Response: `CatalogPack.parse`
   and `json`, `LocalItem`, a role-less file job in `PackDownloader` and `importFiles` with an optional
   graph are named; the peer catalog lists a matching graph; the rule that the graph is never a
   `PackRole` is in the Constraints for the ADR. Resolution: accepted.
7. **Serious, C pins z11 as the read unit.** Response: C's task measures the densest tile's vertices and
   `approxBytes` and chooses merged bodies or z12 files on the number; the ADR records `tileZoom` as a
   per-file field and the reader change it would take, and the overlay's coupling. Resolution: accepted.
8. **Minor, a stale `derived.sha256` after a server rebuild.** Response: the sidecar is keyed like the
   archives' or deleted on every rewrite; `shipped: hash mismatch` in the status. Resolution: accepted.
9. **Minor, a failed graph phase failing the pack.** Response: the phase runs after publish, its failure
   a job note with no `derived`, the job `done` after it either way. Resolution: accepted.
10. **Minor, the (k) sentence on version refusals half wrong.** Response: Prior art corrected from the
    critic's reading of the two sources; the project's rule needs no precedent. Resolution: accepted.
11. **Minor, prediction 5 cannot fail.** Response: the fixtures stay as a regression check in B's task;
    the prediction is now the v0.1.14 APK against a server running B. Resolution: accepted.
12. **Minor, graph-specific keys at the top of `derived`.** Response: common keys at the top, the
    kind's match keys under `key`. Resolution: accepted.
13. **Minor, the byte-length proxy unneeded.** Response: C counts the partition's routable vertices,
    then asks the guard, then builds; the proxy and its claim are dropped. Resolution: accepted.
14. **Minor, leftovers not in `graphFiles`.** Response: `.tmp`, `.part` and the staging file are added
    and named in the KDoc. Resolution: accepted.
15. **Minor, the planner's wait against a long download.** Response: the rule (wait while the download
    progresses, build when it stalls past the wait), the "Downloading the trail network" string, and the
    graph's bytes in the size-first card's pack line. Resolution: accepted.
16. **Minor, the server's `sourceFile` and `builtAtMs` in a shipped header.** Response: messages name the
    phone's file; status shows `builtAtMs` under `source: shipped`. Resolution: accepted.
17. **Minor, the supersede triggers.** Response: B states that its `graph` item is not "graph tiles as a
    manifest kind"; the triggers of ADR 0030, 0041 and 0047 stay on F. Resolution: accepted.

Totals: 15 accepted, 2 accepted with change, 0 rejected. The critic's residual risks are carried: the
snap-order difference on degenerate chains (C, admitted for the ADR), the read-side bound at z11
measured only on T149's pack (C's measurement), and the bytes question as the owner's (B's report).
