# Critic review of proposal 0049: the routing graph shipped with a pack, and a streaming graph build

Date: 2026-10-30
Status: review of the proposal as of commit 3332e3f on `task/T169`
Reviewer: critic (Fable 5.1), read-only on the code in the T169 worktree; nothing run, no Gradle, no device.
Primary sources: the code the proposal names, T149's merge commit (dbe6e69) and `GraphBuildEstimateTest`,
PLANNING › Routing, ADRs 0025, 0033, 0041, 0047, the T149 research file, and four licence or source pages
fetched (BRouter, Valhalla, GraphHopper, OsmAnd).

## Part 1. Claims checked

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | Readers accept any graph file beside the archive whose version is 6, tile zoom 11 and both fingerprints match, with no check of who built it | Confirmed | `RoutePlanner.run` line 248 (`a.source.fingerprint == fingerprint && a.source.demFingerprint == demFingerprint && a.tileZoom == GraphTiler.ZOOM`); server `RouteRoutes.openOrBuild` line 394, the same three; `RouteCommand.kt` line 324 checks the two fingerprints only (it loads `.whole()`). `GraphTileFile.open` (lines 264 to 274) checks the magic, the version and the header checksum; `builtAtMs` and `sourceFile` are read and never compared. |
| 2 | A v0.1.14 catalog parser skips a whole entry with an unknown file role, checks `contentHash` over `files`, ignores unknown top-level keys | Confirmed | `CatalogFile.parse` lines 88 to 91 throws on any role but `dem`, `outdoors`, `osm`, `vector`; `PackCatalog.parse` lines 48 to 53 catches per entry and reports it in `skipped`; `CatalogPack.parse` lines 141 to 150 computes `PackHash.combine` over `files` by role and requires the document's hash to equal it; it reads only named keys. The phone parses through this shared class (`PackCatalogClient` line 64) and a phone's peer catalog is built through `CatalogPack.of` (`PeerServe.catalog()` line 130), so a `derived` field is a `shared` change, not a `serverlib` one (objection 6). |
| 3 | A v0.1.14 manifest parser throws on an unknown item kind and ignores extra keys on a `graph` item | Confirmed, and worse than the catalog | `ManifestItem.parse` line 52 throws; `AreaManifest.parse` line 238 maps items with no per-item catch, so one unknown kind fails the whole manifest (the proposal's choice of no new kind is right). `LocalItem` reads `from`, `available`, `note` (line 51) and its `json()` writes `"onDevice" to true` unconditionally (line 153): writing `onDevice: false` needs a `shared` change. |
| 4 | `RegionDownload.run` skips a `pack` item of unknown role | Confirmed | Line 157, `PackRole.entries.firstOrNull { it.label == f.role } ?: return@mapNotNull null`. |
| 5 | `/out/` serves any file there with byte ranges | Confirmed | `LiveTiles.kt` line 815 hands `/out/` to `ServeStatic.serve`; its KDoc lines 14 to 15 and lines 35 to 57: `Accept-Ranges`, 206 with `Content-Range`, 416, `ETag`, `If-Range`. It serves `.tmp`, `.building` and sidecar files too. |
| 6 | Deleting a pack deletes its graph files | Confirmed, current and legacy names only | `PackManager.delete` line 162 through `RoutePlanner.graphFiles` lines 439 to 442 (`fileNameFor` and `legacyFileNameFor`, `isFile`); a `.tmp`, a staging file or a `.part` of a graph download is not on the list (`delete` clears `.part` by `PackRole` only, line 212). |
| 7 | No edge of a pack graph spans two z11 tiles | Confirmed when the read zoom is at least 11 | `RoutingGraphBuilder.build` line 142 reads at `min(h.maxZoom, MAX_ZOOM)` and line 166 clips to that tile's square, so every line ends on a read-zoom border, which is a z11 border only when the read zoom is 11 or deeper. `GraphTiler` KDoc lines 27 to 28 says "normally no cuts"; T89 (PLANNING line 270) found a cut 7 cm past a z11 border before `MIN_CUT_M` (line 190). The proposed test on Rainier is sound. |
| 8 | Twins exact within one file, within `JOIN_M` across files | Confirmed | `Router.kt` lines 727 and 842, `tolerant = q.graph.source != p.graph.source`; `GraphPiece.twin` lines 65 to 67 returns -1 when not tolerant and no exact key; `JOIN_M = 2.0` (`TiledGraph.kt` line 167). `GraphSource` is a data class over file, both fingerprints, zoom, `builtAtMs`, drive (`RoutingGraph.kt` lines 17 to 33). |
| 9 | Component labels are global in a file and conclusive when one file makes up the network | Confirmed | Builder step 6 lines 459 to 498 (union-find over every edge, labels `comp * 2 + open`); `open()` lines 461 to 470 needs the archive's whole read-zoom tile set (`present`, a `HashSet<Long>`, 24,266 entries for the 3 degree box: cheap to keep global in a streaming build); `Router.separate` line 575 `if (bothOpen && tiles.fileCount != 1) return null`; `TiledGraph.fileCount = archives.size` (line 305). |
| 10 | `GraphTileFile.encode` holds the whole file in memory | Confirmed, twice over | Lines 229 to 257: every body is encoded into `bodies`, then copied into one `out` writer, so at the end two copies of every body exist beside the graph; `RoutePlanner.build` line 310 and `RouteRoutes.openOrBuild` line 405 call it. |
| 11 | T149's numbers | Confirmed, with a series the proposal omits | dbe6e69's body and `RoutingGraphBuilder` lines 673 to 679 (48 MiB, 300 B, 1.5). `GraphBuildEstimateTest` line 79: "2 GB failed; 2.25 GB built in 1,127 s, 2.5 GB in 729 s, 2.75 GB in 489 s". The 1,127 s is a garbage-collection-bound figure at the smallest heap that worked, not the build's cost (objection 2). |
| 12 | Rainier's graph 400,760 bytes at version 4; large packs' bytes not established | First confirmed; second contradicted by the record the proposal cites | PLANNING line 252 (400,760). PLANNING line 266 (T77): "one 245 MB outdoors pack of Washington, graph built in 49 s, 103 MB file"; line 278 (T89): "245 MB, graph 113 MB, built in about 70 s". That is 42 to 46 percent of the vector archive, above the proposal's "under 20 percent" prediction and its own "over a third" reversal trigger (objection 1). |
| 13 | The densest z11 tile's vertices; whether tile bytes track routable vertices | Not established, agreed | `measure` (lines 692 to 725) samples 256 tiles over the whole Hilbert order: about one sample per 95 z14 tiles on the 3 degree box, under one per z11 partition, so it cannot give a per-partition count. The exact count is available for the price of a second decode of at most 64 tiles (objection 13). |
| 14 | The server's JVM heap | Confirmed, plus the container's setting | No `-Xmx` in `server/build.gradle.kts` line 22 or `devtool/build.gradle.kts` lines 27 and 34; `gradle.properties` `-Xmx2g` is Gradle's own daemon, and `tasks.named<JavaExec>("run")` forks, so `devtool serve` gets the JVM default (a quarter of physical memory). `server/Dockerfile` line 54 sets `JAVA_OPTS="-XX:MaxRAMPercentage=60"`. |
| 15 | Prior-art (k) items: BRouter and OsmAnd refuse files of another format version; licences | Half right; licences confirmed | BRouter `LICENSE`: "The MIT License (MIT)". `btools/mapaccess/PhysicalFile.java` throws `"lookup version mismatch (old rd5?) lookups.dat=" ...` when the segment's lookup-data version differs from `lookups.dat`, and `"top index checksum error"`; the format version itself is read and not compared with a constant (two index CRC variants are accepted, each setting a divisor). OsmAnd `BinaryMapIndexReader.java` (first 100,000 characters) reads `version` and only checks the file's trailer repeats it (`"Corrupt file, it should have ended as it starts with version: "`); no refusal of an unsupported version was seen. Valhalla `COPYING`: MIT. GraphHopper `LICENSE.txt`: Apache License 2.0. OsmAnd's GPL-3 not re-fetched (a design reference only). |
| 16 | A server pack build's time for the 3 degree box | Not established, agreed | `machines.md` line 68: the Rainier outdoors pack in 33 s (tilebuild, a 0.5 by 0.3 degree box); PLANNING line 705: a z12 pack built by a server in 28 s. No 3 degree figure anywhere. |

Also checked: `PackDownloader.FileJob` takes a `PackRole` (line 52), `startCatalog` a `Map<PackRole, ...>` (line 133), `resumePending` matches by `role.label` (line 182): "through the same downloader, not a `PackRole`" needs a role-less job the proposal does not name. `PackManager.importFiles` lines 270 and 300 accept at most two `.pmtiles` files. `BuildRoutes.run` lines 428 to 452: any throwable in a phase fails the whole job and deletes both archives. `HeapGuard.admitBuild` (lines 262 to 335): trim, wait up to 10 s in 250 ms steps, refuse; `force` admits without asking; `testLimit` never waits.

## Part 2. The case against D, and for the best alternative

The proposal rests its order (C before B) on two reasons: C alone fixes the refused case on the no-server
path, and the server's one-pass build costs 2.25 GB and 1,127 s. The first is true. The second is a heap
artifact: the same box took 489 s at 2.75 GB and the statewide pack, twice as large, built in 49 to 70 s on a
desktop with the JVM default heap (claims 11 and 12), and the server image already takes 60 percent of its
container's RAM (claim 14). On the operator's machines the one-pass graph phase of the largest pack the
server allows (3 degrees, `RegionDownload.packFits`) is one to a few minutes with the heap it has today.

So the strongest case against D-as-ordered: C is the largest piece of work in the proposal and carries the
one correctness risk it admits (border twins, components), for a path (a city pack built on the phone with
no server) the owner has not said bites, while the board (`docs/TASKS.md` line 22) is waiting on exactly
that answer. B with today's one-pass builder on the server fixes every server-built and catalog pack in one
task that touches no builder in `shared`, is the field's practice to the letter, and lets the phone keep
T149's guard for everything else. Two cheap makers the proposal's own Question names and its options leave
out would cover the rest at once: the import flow accepting a `<name>-outdoors-graph.idx` beside the two
archives (every reader already accepts it when the fingerprints match, claim 1, so `devtool route build`
then a push or an import is a shipped graph with no server at all), and a phone's peer catalog listing its
graph under the same `derived` field (it is built through the shared `CatalogPack.of`, claim 2). C then
follows when a no-server user meets the refusal, or when a 2 GB server does.

What that alternative gives up: the no-server city pack stays refused on small phones until C; the server's
graph phase needs a heap of about 300 B per routable vertex times 1.5 plus 48 MiB (the guard's own fit:
about 3.6 GB for the 3 degree box, 2.25 GB measured as the floor), which a small container lacks; and 42 to
46 percent of the vector archive in extra bytes per pack, shown but not gated (ADR 0023), is a number the
owner has not seen. The proposal's end state D is right: both legs are wanted and separable, the file does
not change, old readers ignore the new fields. The order should be the owner's pending answer, not the
1,127 s figure, and either order must carry the corrections below.

## Part 3. Objections

Blocking: none. Serious: 7. Minor: 10.

1. **Serious. The shipped graph's size prediction is contradicted by the record the proposal cites, and its
   own reversal trigger is already crossed.** Prediction 4 says under 20 percent of the vector archive on
   Rainier and the 3 degree box; "What would change this" says B waits for the owner when a graph is over a
   third. PLANNING lines 266 and 278 record 103 and 113 MB of graph for a 245 MB outdoors archive (42 to
   46 percent, claim 12). Rainier is 12 to 15 percent (400,760 bytes over 2.6 to 3.4 MB); the 3 degree box
   (124 MB, 7.9 million vertices) will land near the statewide ratio. *Resolve:* state the record in the
   Size line; restate the prediction and the trigger against the pack as the user sees it (DEM plus vector,
   where a graph is a few percent) and keep the vector-archive ratio as a reported number; say in the ADR
   which denominator the owner's view is asked on; measure the 3 degree box's version 6 file with
   `devtool route build` in task one and carry the bytes into the ADR before task two is briefed.

2. **Serious. B's server cost is misstated, and the order rests on it.** "2.25 GB of heap for a 3 degree
   box, adding minutes to the job: 1,127 s" is the smallest heap that did not fail and its
   garbage-collection-bound time (claim 11: 729 s at 2.5 GB, 489 s at 2.75 GB; the statewide pack 49 to 70 s
   on a desktop heap). The Dockerfile already gives the server 60 percent of its RAM (claim 14). *Resolve:*
   give the server phase its real cost (the guard's fit as a heap requirement, the measured series as time);
   make the order explicit on the owner's answer the board asks for: if the refusal bites on server or
   catalog packs, B ships first with today's one-pass builder and the two makers of objection 6, C second;
   else C first as written. The ADR records the condition and who answered it.

3. **Serious. The per-partition guard refuses mid-build with no defined state, lift or sentence.** C asks
   `HeapGuard` before each partition "so it can wait between tiles instead of being refused whole", but a
   partition that still exceeds the free heap after the 10 s wait is refused with partitions 1 to k-1 in a
   staging file the proposal does not say is kept, resumed or deleted; the next use (30 s later) starts
   where? The release build's only lift today is "download a smaller area" (the sentence) and `force=1` is
   debug-only; under C the sentence is wrong for the case (one part is too dense, not the map) and the honest
   lift, the sub-partition, is deferred to "if the densest city tiles still exceed". *Resolve:* the staging
   file carries a partition cursor and a refused build resumes from it (the research's "resumable"); status
   gains `part`, `parts`, `densestVertices` as proposed plus `resumedFrom`; the sentence for a per-partition
   refusal names the part and the remedy; the sub-partition path is in task one's Done-when (built, or shown
   unneeded by the measured densest tile of the city pack), not a later task.

4. **Serious. The border table is underspecified where the one-pass build joins things, and the exactness
   test cannot fail on the stated risk.** `addCrossings` inserts a vertex up to `tol` beyond a segment's end
   (lines 560 to 562) and `Vertices.id` snaps to the nearest vertex within `tol` (lines 259 to 274), so a
   crossing within 1 m of a z11 border in partition A is a vertex partition B's extrapolated crossing must
   find; T89's levels (a bridge cut at a border snaps only to bridge cuts, lines 295 to 299) ride on the clip
   flags. "The vertices on the south and east edges" is not that set. Prediction 1 (Rainier's golden route)
   and the Rainier border test exercise 20 rural tiles with no such case. *Resolve:* define the table as
   every vertex within `SNAP_M` of the partition border with its level and clip flag; add synthetic tests
   that can fail: two lines crossing within 0.5 m of a z11 border, a bridge and a road meeting at a border,
   a three-vertex chain within `tol` straddling it; assert routes, node and edge counts and labels equal to
   the one-pass build on each; keep the Rainier goldens as the regression, not the proof.

5. **Serious. The shipped graph reaches a phone only on a fresh download; installed and updated packs are
   undefined.** "A new phone fetches the graph after the pack's archives verify" covers `startCatalog` and
   `RegionDownload`. `CatalogStatus.of` keys on `contentHash` over the archives by design, so a phone holding
   a pack whose graph was refused or built never learns the server ships one, and a pack update
   (`changedRoles`) leaves a stale graph the planner rebuilds. *Resolve:* `ensureRegion` finding a missing,
   stale or refused graph with a server set asks the catalog client's cached document for a matching
   `derived` entry and fetches before building; an update fetches with the archives; `source: shipped|built`
   says which path and why on both.

6. **Serious. The moving parts omit the `shared` types that must change, and with them the two makers the
   Question names.** The phone reads the catalog through `shared/.../packs/PackCatalog.kt` and publishes to
   peers through `CatalogPack.of` (claim 2); the manifest's `graph` item is `LocalItem` with `onDevice`
   hard-coded (claim 3); `PackDownloader.FileJob` is `PackRole`-typed (Part 1's note); `importFiles` refuses
   a third file. "The two fields in `serverlib`'s `PackCatalog`" is therefore wrong in place, and the
   proposal's ten-year sentence ("a server, a peer or the phone itself be the graph's maker with the same
   file") is contradicted by B's text ("peers that publish no graph", imports build). *Resolve:* name
   `CatalogPack` (`parse` and `json`, a `derived` list), `LocalItem` (`onDevice`, the download fields), a
   role-less file job in `PackDownloader`, and `importFiles` accepting an optional `-graph.idx` as moving
   parts; let the peer catalog list a graph whose fingerprints match its archives; say in the ADR that the
   graph is never a `PackRole` (`PackHash.combine` iterates `PackRole.entries`, line 225, so a role would
   change every hash).

7. **Serious. C quietly pins z11 as the read unit for the ten-year horizon.** Every reader hard-checks
   `tileZoom == GraphTiler.ZOOM` (claim 1) although the header carries the zoom as a field; C's fallback
   "z12 or z13 sub-partitions merged into one z11 body ... without a format change" keeps that so, which
   means a dense city tile is also decoded whole on read (`GraphTileArchive.tile` reads and decodes one
   body; `GraphTileCache.trim` keeps at least one entry whatever its size, line 252). On the T149 city pack
   that is tolerable (about 10 MB decoded for a 400,000-vertex tile by `approxBytes`), but the proposal
   lists the densest tile as not established and decides the fallback's shape before measuring it.
   *Resolve:* measure the densest tile's `approxBytes` with the vertex count in task one; record in the ADR
   that `tileZoom` is a per-file header field, that accepting 11 to 13 per archive is a reader change with
   no version bump (`TiledGraph` requires one zoom across archives, line 300, and `tilesIn` would go per
   archive), and choose between "merge into z11 bodies" and "write z12 files" on the number, not by default.

8. **Minor. A server-side graph rebuild leaves a stale `derived.sha256`.** `BuildRoutes.run` deletes the
   archive's sidecar at publish (line 436); `RouteRoutes.openOrBuild` rewrites the graph through `.tmp`
   (lines 405 to 408) and touches no sidecar; `POST /route/build` on the server does the same. A phone then
   fails the graph download with "hash mismatch" (no resume) and builds, silently. *Resolve:* the graph's
   hash is keyed like the archives' (size and time) or the rebuild deletes its sidecar; status says
   `shipped: hash mismatch` when it happens.

9. **Minor. A failed graph phase must not fail the pack.** `BuildRoutes.run`'s catch fails the job and
   deletes both archives (lines 445 to 452). *Resolve:* the phase runs after `publish`, its failure is a job
   note and the catalog omits `derived`; the job is `done` only after the phase ends either way (so
   `RegionDownload`'s poll waits for the graph, with the phase named).

10. **Minor. The (k) sentence on version refusals is half wrong.** BRouter refuses on a lookup-data version
    and an index checksum, not a format version constant; OsmAnd's reader checks only that the trailer
    repeats the header's version (claim 15). *Resolve:* correct the Prior art paragraph; the project's own
    rule (a version byte, refused by name, rebuilt) is stronger than either and needs no precedent.

11. **Minor. Prediction 5 cannot fail.** The code already guarantees that v0.1.14's parsers ignore a
    `derived` list and extra `graph` keys (claims 2 and 3); a fixture test is a regression check, not a
    prediction. *Resolve:* keep the fixtures; make the prediction the v0.1.14 APK downloading a new server's
    pack and building its own graph, which can fail.

12. **Minor. The `derived` entry's keys are graph-specific at the top level.** `tileZoom`, `fingerprint`,
    `demFingerprint` beside `kind`, `file`, `sha256` mean a later `search` or `overlay` entry carries nulls or
    its own ad hoc keys. *Resolve:* common keys at the top (`kind`, `file`, `url`, `bytes`, `sha256`,
    `format`, `version`) and the kind's match keys under `key`; a reader fetches only a kind it knows and
    whose `key` it can compare.

13. **Minor. The per-partition estimate proxy is unneeded.** The build is about to decode the partition's
    at most 64 tiles; counting their routable vertices first (`routableVertices`, lines 728 to 745) gives
    the exact count at the cost of one extra decode of those tiles and no proxy to verify. *Resolve:* count,
    then ask the guard, then build; drop the byte-length proxy.

14. **Minor. Housekeeping.** `graphFiles` (claim 6) does not list `.tmp`, the staging file or a graph
    `.part`; a crash or a deleted pack leaves them. *Resolve:* name the staging file in the KDoc and add the
    three to `graphFiles`.

15. **Minor. The planner's wait against a long download.** `RoutePlanner.router(waitMs)` waits on
    `finished` (line 338) for `RouteSession.WAIT_MS`; a 100 MB graph on a slow link outlasts it and the
    route answers not ready instead of building. The route card needs a line for it ("Downloading the trail
    network", a third string for ADR 0031 the proposal does not list), and the size-first card's pack line
    ("With routing and place search (a map pack)") should include the graph's bytes. *Resolve:* state the
    rule (wait while the download is progressing, build when it stalls past the wait) and list the strings.

16. **Minor. A shipped header carries the server's `sourceFile` and `builtAtMs`.** Nothing compares them,
    but `Router.separate` (line 577) and `drive_unsupported` print `source.file`, and `GET /route/status`
    reports `builtAtMs`. *Resolve:* the phone's messages name the phone's file; status shows `builtAtMs`
    under `source: shipped`.

17. **Minor. Supersede triggers.** ADR 0041 and 0047 say "supersede when graph tiles become a manifest
    kind"; B's `graph` item with download fields is not that (F is). *Resolve:* the ADR says so, so the
    triggers stay on F.

## Part 4. Refusals and their lifts

- **The heap guard** (today, and per partition under C): refuses with "This map is too large to route across
  on this phone; download a smaller area for routing"; 409 `graph_refused` on `POST /route` and
  `/route/build`. Lift while running: `POST /route/build?force=1` (skips the guard) and
  `POST /route/guard?maxBytes=` (a test limit), debug builds only, on the debug port with the token,
  documented in `android-debug-routes.md` line 32 and `android-app.md` line 66. The release build has no
  lift; the sentence names a remedy, not the lift, which ADR 0041 §3's pattern allows for a memory refusal.
  Under C the remedy is wrong for a one-part refusal: objection 3.
- **Version skew**: `GraphTileFile.open` refuses with "routing graph version 7, expected 6"; every caller
  rebuilds and `rebuildReason` carries the sentence in `/route/status`. No lift is needed (derived data,
  ADR 0033 §2); the user sees a build, not a refusal. Under B a version the phone does not read is never
  fetched; `source: built` with the reason is the proposal's answer and is enough.
- **Fingerprint mismatch** (catalog against the phone's archives): not fetched, built. Lift: update the pack
  (catalog `UPDATE`) or none needed. Status says why: adequate.
- **Graph hash mismatch on download**: `PackDownloader` fails "hash mismatch", no resume; the phone builds.
  Lift: the operator's `POST /catalog/refresh` or the sidecar rule of objection 8; the status should say it.
- **The server's graph phase failing**: objection 9; lift: the job note and `POST /route/build` on the
  server, which already exists.
- **`DYSTOPO_BUILD_MAX_SPAN_DEG` and `_MAX_TILES`**: unchanged; the operator's lift as before.

## Part 5. Hidden coupling

- `derived` becomes a second list the pack's identity ignores but phones depend on; its match rule is the
  PMTiles header tuple `fingerprint()` ("v1:" plus seven header fields), now a wire value. A change to
  "v2:" makes every older server's shipped graph unmatched and rebuilt (self-healing); record it.
- The graph is never a `PackRole`: the content hash, `PackNames`, `PackPrecedence`, the downloader's roles
  and the tile service all enumerate roles (objection 6). The ADR should say so as a rule, since E's
  temptation returns with every "third file".
- `tileZoom == 11` in every reader (objection 7), and `GraphTiler.ZOOM` as the overlay's cell grid (T77:
  z11 cells, z8 level 2): a change of the graph zoom moves the overlay too.
- The `/area` job's `done` now waits on the graph phase, so the phone's pack path waits with it; the
  size-first card's pack line and `RegionDownload.packFits` (3 degrees) bound what the server phase must
  build in one pass until C.
- The manifest stays at the lowest version that holds the rest (ADR 0041 §2, the T95 `versions`
  precedent): right here, because an old reader ignoring the fields builds its own graph, which is the
  correct behaviour, unlike ADR 0047's `demMaxZoom` where ignoring fetched the wrong bytes.

## Part 6. Pre-mortem

Ten years on, every phone still decodes and builds the trail network in z11 pieces chosen in 2026 from a
20-tile national park: the streaming build partitioned by them, the sub-partition fallback merged back
into z11 bodies "without a format change", the overlay's cells sat on the same grid, and every reader kept
`tileZoom == 11` as a hard check on a header field that could have carried any value, so when dense packs
needed z13 bodies the file was technically unchanged and every phone refused the new files and rebuilt them
anyway; meanwhile the `derived` list grew a search index, overlay cells and contour tiles, each with its own
match keys at the top level, and the "v1:" header tuple that matched a graph to its archive was never
versioned because nothing ever compared it on the wire before. The line that should have caught it is
"A further cut (z12 or z13 sub-partitions merged into one z11 body through the same border table) is
possible without a format change if the densest city tiles still exceed a phone's heap": the "without a
format change" was bought by fixing the read unit for good, decided before the densest tile was measured,
and the Constraints paragraph had already named the hard-coded check ("its tile zoom is 11") without asking
why a header field has one legal value.

## Recommendation

D is the right end state and the proposal is right that the file needs no version and old readers need no
change; I would promote it after the corrections. The order is not settled by the proposal's evidence:
the 1,127 s figure is a heap artifact and the server already has the heap, so B with today's builder is a
one-task fix for every server and catalog pack, while C is the only fix for the no-server path and the
largest piece of work with the one real correctness risk. Make the order conditional on the owner's
pending answer (objection 2), put the import and peer makers into B whichever comes first (objection 6),
and give C's task one the resume, the lift and the synthetic border tests (objections 3 and 4) and the
densest-tile measurement before the sub-partition's shape is fixed (objection 7). Restate the size
prediction against the pack as the user sees it and carry the statewide ratio the record already holds
(objection 1). Residual risks after that: the one-pass snap order and the partitioned order can differ on
degenerate chains within 1 m, which the "equal in routes" criterion tolerates but the ADR should admit; the
read-side bound at z11 for the densest cities is measured only on the T149 pack; and the bytes question is
the owner's, so B may wait on it whatever the order. Confidence: high that D with these corrections is the
right decision; moderate on C-first, which I would keep only if the owner's answer is "the no-server path".
