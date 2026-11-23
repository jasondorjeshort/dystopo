# 0066. One lookup for what the phone holds here, and the order every reader takes from it

Date: 2026-11-23
Status: under review (revised on the critic's 14 objections, `0066-critic-review.md`)

## Question

At a point and a zoom the phone may hold several offline maps (each a DEM and a vector archive, at its own
detail), downloaded areas (tiles kept in the online store, ADR 0060), tiles cached while browsing, the built-in
world overview, and an online source that could fetch more. Today about thirty readers each ask "what is here?" in
their own way (the table below), so the map, the sky views, the readout, search and the first-run card disagree;
the owner's reports of 2026-11-22 are that disagreement. This proposal decides: (1) the order of the maps, in
particular whether depth goes before the map the user chose last ("primary") in `PackPrecedence`, and what
"primary" then means; (2) the online source as a member of that order where it stores deeper data than the phone
holds, under the owner's setting (default on), with its data cost; (3) how a view of mixed detail joins at a map's
edge; (4) one pure lookup in `shared` that answers, for a point or a tile, what each holding stores, which serves,
and what the network would add, with the coverage panel under the status chip as its first visible use; (5) the
build tasks. In ten years every client (the phone, the web viewer, iOS) should ask one function this question, and
adding a source kind (a peer, a new detail level) should touch that function and nothing else.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later; the lookup is pure Kotlin in `shared` commonMain, inputs in, answer
  out, no `java.*`; offline first; every feature agent-drivable (a debug route); agents write all code; ADR 0025.
- The owner's terrain rule (2026-11-22): "look at the highest resolution available at the point and expect to step
  down to a coarser pack once it leaves the finer one". T340 built it for the terrain read only (`MergedTerrain`).
- The owner's Answer (`docs/ideas/2026-11-22-a-downloaded-map-must-never-make-the-view-worse-than-online.md`): a
  setting, default **on**: finer online tiles are fetched where the network gives more than the map; off saves data
  and accepts the lower detail. A setting by the owner's word; the survey below agrees practice is split (ADR 0044).
- No Wi-Fi gates (ADR 0023): the online member obeys the Mobile data switch and the session limit the phone's own
  sources enforce (`DirectSources`); a configured server has neither, as ADR 0023 says ("A configured server is
  unaffected by it").
- A downloaded map must never make the view blank or coarser: what the phone holds answers at once; the network can
  only add.
- Rendering conventions: coverage edges are feathered with `Composite.over`; every raster through `MercatorWindow`.
- Alpha (ADR 0033): changing the order needs no migration; the stored `packs/active` file keeps its meaning as
  "the map chosen last". A changed flow ("Show first", objection 6) is still flagged to the owner (CLAUDE.md).
- T342 (this wave) adds a per-map off switch: an off map is in no input of the lookup.
- User-visible text names no other mapping product.

## Every reader today (read at `bca04230`, 2026-11-23)

Paths under `androidApp/.../app/` unless `shared`. "Order" = `PackPrecedence.ORDER` (profile, **primary**, depth,
smaller box, name; first archive containing the tile, else first overlapping). 34 readers in seven groups (rows
33 and 34 added on the critic's objection 8).

| # | Reader | File | What it does today |
|---|---|---|---|
| **Tiles** | | | |
| 1 | Vector tile | `TileService.kt` `osmTile` | Online only where no map's vector archive answers (`packCoversOsm`: a map answers only inside its own zooms); else `osmCandidates` in Order; world overview last (z0 to z5). |
| 2 | DEM tile | `TileService.kt` `demTile` | Online only where no map's DEM answers; a map's DEM answers **every zoom from its first down** (deeper tiles synthesised), so inside a map the fetcher is never asked: neither the network nor an area's kept or cached deeper tiles. |
| 3 | Hillshade, slope, sun tiles | `TileService.kt` `shadeTile`, `options/ShadeService.kt` `tile`, `sunTile` via `pyramidFor` = `packs.demFor` | The map's pyramid whenever `packCovers`, else the online pyramid. |
| 4 | Contour tile | `TileService.kt` `contourTile` | Map path when the tile lies wholly inside one map (`demContains`); there and at an offline edge the tile reads **one** map's pyramid (`contourBytes` → `pyramid(pack)`; `demCandidates` feeds only the `contourNoDemOverPack` counter), so an edge tile is either the coarse map's whole or the fine map's clipped. Online, the online path, mixing per DEM tile in `OnlineTiles.demBytes`. |
| 5 | Online pyramid | `online/OnlineTiles.kt` `demBytes`, `pyramid` | A map's tile wherever any map answers the tile, else the fetcher (cache, then network). |
| 6 | Style ranges | `TileService.kt` `style` (line 534) | The DEM source's top zoom is the deepest map's whenever **any** map has a DEM (`demHi`), the online `DEM_MAX_ZOOM` only with no map; contour and shade tops follow it. One z13 map caps the DEM at z13 **everywhere on earth**. The vector top is widened to the online source's. |
| 33 | Cliff ticks in vector tiles | `TileService.kt` `cliffDir`, `cliffPyramid` | Oriented by the DEM map `demFor` picks, which may differ from the map that gave the vector tile. |
| **Terrain windows** | | | |
| 7 | Horizon view | `gnss/Gnss.kt` `sweep` | T340's `TerrainRead`: maps deepest first per cell, then online, then a map's whole-tile margin; window zoom from `pointZoom` (the deepest map holding the point, capped at z14). |
| 8 | Panorama | `panorama/PanoramaData.kt` | Same read as 7. |
| 9 | Viewshed terrain | `viewshed/ViewshedSession.kt` | Same read, online left out (100 km of tiles; T340 left the question). |
| **Point elevation** | | | |
| 10 | Long-press sun answer | `options/ShadeService.kt` `pointAnswer` → `pyramidAt` | The first map `demFor` gives at z14, 12, 10 or 8 (Order), else the online pyramid. |
| 11 | Long-press line of sight | `tracking/LongPressMenu.kt` (`pyramidAt`) | As 10. |
| 12 | Turn-back terrain day | `nav/NavShade.kt` (`pyramidAt`) | As 10. |
| 13 | Route's best start | `routing/RouteBestStart.kt` (`pyramidAt`) | As 10. |
| 14 | Readout elevation | `readout/LiveReadout.kt` `demAt` → `trips/Trips.kt` `demSampler` | **The primary map's DEM only**; NaN elsewhere, no online. |
| 15 | Pressure card ground height | `readout/PressureCard.kt` `PressureWatch` → `Trips.demSampler` | As 14; it calibrates the pressure reading. |
| 16 | Way-down warning | `readout/PressureCard.kt` `WayDownWatch.sampler` | First DEM map in Order whose box holds the point, else the primary map; no online. |
| 17 | Route crow line, route editor | `trips/Trips.kt` `demSampler`, `editor`; `routing/RoutePlanner.kt` (crow, line 459) | As 14. |
| 18 | Scale bar's elevation route | `controls/ScaleBarRoutes.kt` (`live.demAt`) | As 14. |
| 19 | Route exposure | `routing/RouteExposure.kt` `demFor` | First DEM map in Order whose box holds the route's box, else the first overlapping; z14; no online. |
| 34 | Terrain-aided position estimate | `tracking/TerrainSource.kt` (line 79) | `Trips.demSampler`, the primary's DEM; matches the walk against it. |
| **Vector features** | | | |
| 20 | Horizon peaks | `horizon/HorizonData.kt` | `osmCandidates`, the first archive with a tile (T352 steps past a tile with no peaks). |
| 21 | Known heights | `tracking/LongPressMenu.kt` `KnownHeights.read` | `osmCandidates` first tile. |
| 22 | Route water | `routing/RouteWater.kt` | `osmCandidates` first tile. |
| 23 | Viewshed places | `viewshed/ViewshedSession.kt` | `osmCandidates` first tile. |
| 24 | Peers' tiles | `share/PeerServe.kt` `/dem`, `/osm`, contours | `demCandidates` / `osmCandidates` first tile; contours via 4. |
| **Coverage questions** | | | |
| 25 | Status chip | `online/OnlineTiles.kt` `viewSource`, `online/OnlineView.kt` | `pack` if any map answers the DEM tile one zoom deeper than the view, else the fetcher's record of the tile, `world`, `none`; "on this phone" via `Areas.isRecorded`. |
| 26 | Download button | `online/OnlineOverlay.kt` `offerDownload` | Hidden when the chip's source is `pack`, whatever that map's detail. |
| 27 | Search and route coverage | `search/PlaceSearch.kt` `coverage`, `MapCoverage` | `VECTOR` if any map's vector box holds the point; else the chip's source, else any DEM box. |
| 28 | First-run and no-map messages | `firstrun/FirstRun.kt`, `firstrun/MapMessage.kt` (T332) | Any map's box or a held area's box overlaps the view. |
| **The whole set** | | | |
| 29 | Search indexes | `search/PlaceSearch.kt` | Every map's vector archive. |
| 30 | Routing graph | `routing/RoutePlanner.kt` | Every map with a vector archive, the primary first (by name); the maps near the ends. |
| 31 | Peers' catalog, attribution | `share/PeerServe.kt`, `MainActivity.kt` | Every map. |
| **The primary alone** | | | |
| 32 | Start view, unit seeding, contour warm-up, "Show first" | `MainActivity.kt`, `DystopoApp.kt`, `TileService.kt` `startWarmup`, `areas/OfflineMapList.kt` (161, 249) → `PackManager.activate` | The primary map's box; "Show first" sets the primary. These read `PackSet.primary`, chosen by name, not the sorted list, so A2 breaks none of them in code. |

Planned this wave: T342's off switch (filters every input), T343's Download-card function (finest first over the
visible box), T341's tile-source rule and setting for the overview's zooms. A `Packs.kt` KDoc says depth before
primary; the code says the reverse (T342 fixes the KDoc).

**How deep the online source really is** (objection 1). `OnlineTiles.DEM_MAX_ZOOM = 15` is the URL range only. The
default online source, the phone's own reading of the public sources, renders DEM to `DirectSources.maxZoom`,
default `DEFAULT_MAX_ZOOM = 12` ("26 m cells ... deeper tiles are synthesized from it. 15 renders every zoom as the
server does, at about four times the bytes per view"), set from `DirectPrefs.maxZoom`; deeper tiles are synthesised
from z12. A configured server declares its range in its info (`demMaxZoom`, `osmMaxZoom`; `OnlineService.kt:759`).
So with the defaults the network's DEM is **coarser** than a medium (z13) or full (z15) map; it is deeper only for a
server user, or a phone whose direct `maxZoom` was raised.

## Prior art

Fetched in this run (2026-11-23), each read by the author; product names allowed here, not in the app. The critic
re-fetched three (CalTopo, Locus, Mapbox) and found the quotes verbatim.

- **CalTopo** (training page "Offline Use", https://training.caltopo.com/all_users/mobile/offline): "If you have a
  data connection, the app will display map layers from our server." ... "If that fails, it will then display
  previously downloaded layers from your device." Its blog (https://blog.caltopo.com/2025/08/25/new-feature-offline-mode/)
  adds an Offline Mode that "tells your device to pull map layers ONLY from your downloaded storage". **Online
  first, downloads as fallback, a user switch to stay offline.**
- **Locus Map** (manual, https://docs.locusmap.app/doku.php/manual:user_guide:maps_settings): offline vector maps
  are auto-loaded for the displayed position for a "seamless map display across the whole screen" where two maps'
  coverage blends; an optional "Online LoMap as backup": "online LoMap loads when you go out of the area covered by
  your downloaded offline LoMaps"; for online maps a "Cached maps only" switch "to avoid downloading from the
  internet". Release notes 2.8.6 (https://www.locusmap.app/news-in-version-2-8-6/): "display all maps at once as
  one single map". Neither page says which map wins where two overlap. **Offline first, online outside the maps as
  a setting, several maps merged into one.**
- **OsmAnd** (https://osmand.net/docs/user/map/raster-maps/, https://docs.osmand.net/docs/user/plugins/online-map):
  vector maps "are available only in the Main layer"; online raster maps are layers the user picks (Main, Underlay,
  Overlay), cached as browsed. **Offline vector always; online only as a layer the user adds.**
- **Google Maps** help (https://support.google.com/maps/answer/6291838): "If your internet connection is slow or
  unavailable, your offline maps can guide you"; the page shows no switch for the choice. **Online normally,
  offline maps when the connection fails.**
- **Mapbox Maps SDK** (technical, https://docs.mapbox.com/android/maps/guides/offline/concepts/): the tile store is
  read-only by default and the SDK "only interacts with it to check for tiles it needs"; a missing tile is requested
  individually into the disk cache; "The tile store does not share tiles with the disk cache". Tile packs cover
  zoom bands (0 to 5, 6 to 10, 11 to 14, 15 to 16). **Stored tiles first per tile, the network for what is missing.**
- **MapLibre style spec** (https://maplibre.org/maplibre-style-spec/sources/): for vector and raster-dem sources
  "Data from tiles at the maxzoom are used when displaying the map at higher zoom levels"; with `bounds` "no tiles
  outside of the given bounds are requested". The renderer overzooms one source to its `maxzoom`, so a source's top
  zoom in the style is itself a cap (row 6); choosing between sources per tile is the app's job.

Not fetched (search summaries only, carry no weight): Gaia GPS's help (403 to the fetcher); a Locus forum reply
that users can choose "automatic", "online only" or "offline only"; Organic Maps being offline only.

**Reading.** Practice is split on the order of downloaded and online data: CalTopo and Google Maps go online first
when connected, Locus and OsmAnd stay offline first with online as an opt-in. None documented compares the
*detail* of the two at a point; all choose by source, not by depth. Three of the four that offer both document a
switch (Google's page shows none). Merging several offline maps into one view is Locus's documented behaviour.
The owner's rule (finest at the point) is a departure in kind: depth decides, not whether the data is downloaded;
the setting matches common practice.

## Options

### Q1. The order of the maps

**A1. Keep Order** (profile, primary, depth, box, name). Nothing to build. The owner's two-map reports stay: a
coarse map chosen last wins inside a fine one for tiles, shade, contours and every reader in rows 10 to 24. Exit
cost: none. Moving parts: none. Setting: none.

**A2. Depth before primary** (profile, depth, primary, box, name). The DEM's profile is always `unknown`, so for
elevation depth comes first outright; for vector the outdoors schema still beats a plain one at the same place
(it carries layers the style draws), then depth (a less-detail map stops at z13, the others at z14). "Primary"
becomes **the map chosen last**: where the map opens, unit seeding, the contour warm-up, the routing graph's build
order, and a tie-break between two maps of equal depth. The rows that read the primary alone (14 to 19, 34) move
to the lookup (Q4). Its user-visible face is the Offline maps action "Show first", which under A2 no longer shows
that map first wherever another is deeper: Question (b) below. Exit cost: one comparator line and its tests.
Moving parts: none new. Setting: none (the owner's rule; one order).

**A3. Depth before profile too** (depth, profile, primary, ...). Differs from A2 only for vector, where a plain
z14 map would beat an outdoors z13 map: more labels and paths at z14, but no cliffs, huts or wilderness, which the
style draws at every zoom. Exit cost and parts as A2.

### Q2. What the phone holds and what the network adds

The revision splits this in two (objection 5): tiles **on the phone** and tiles **fetched**.

**On the phone, always by depth (no setting).** Every holding on the phone is ranked by its stored depth at the
tile: a map's archive, a downloaded area's kept tiles (per cell, ADR 0060's ledger and `AreaPins`), and tiles
cached earlier. An area's or the cache's stored z15 DEM tile inside a z13 map is shown, since it costs nothing;
today `demTile` never asks the fetcher inside a map (row 2). Offline, nothing else changes.

**B1. The network as last resort (today).** Network only where nothing on the phone answers. Contradicts the
owner's Answer where the network stores deeper data.

**B2. The network as a member by stored depth, under the setting.** For each tile and role, the network goes ahead
of the deepest holding on the phone exactly when the **online source's stored maximum** (`DirectSources.maxZoom` for
direct reading, a server's declared `demMaxZoom` / `osmMaxZoom`) is deeper than that holding's stored maximum and
the tile's zoom is past the holding's stored maximum (the holding would synthesise or overzoom), or the holding is
the world overview (T341). The setting gates only this fetch; the fetcher's own rules (Mobile data, the session
limit) still apply. What it gains, per source kind:
- *Direct reading at defaults* (DEM to z12): the DEM gains nothing over a medium or full map; the gains are vector
  z14 over a less-detail map and the overview's zooms (T341). If the owner's report was on direct reading, its DEM
  half is row 6's cap and row 2's ignored areas, not this.
- *Direct reading with `maxZoom` raised to 15*: DEM z14 and z15 over a medium map.
- *A configured server* (DEM to its declared top, usually z15): DEM z14 and z15 over a medium map.

*Answering without a wait* (objection 4). One rule for both source kinds: a tile answers at once from the deepest
holding on the phone; only when nothing on the phone holds the tile (not even an ancestor) does the request wait for
the network, as today. Where the network is deeper, the fetch starts in the background; landed tiles are announced
by one refresh bump per settled batch, reusing the stall recovery's settle (`GET /online` `stalledDem`), not one bump
per tile. A bump of `demSourceRefreshes` reloads the raster-dem source and changes the contour and shade URLs
(`TileService.kt:540-559`, `DemSourceRefresh.kt`), so every visible contour and shade tile is regenerated once per
batch: task 2 measures it (source reloads and contour regenerations during a z14 pan inside a medium map) and, if a
batch per settle is too many, holds the bump until the camera rests. No 9 s `answerWithinMs` wait (set for direct
reading only today, `OnlineService.kt:216`) is ever put in front of a tile the phone holds.

*Data cost* (objection 1(c)), only at zooms past the holding's stored maximum and only where the source is deeper:
- *Server*: server tiles; about 24 DEM tiles of 256 px for a new z14 phone view (a 512-px map tile is four DEM tiles
  one zoom deeper, about six map tiles a screen), at most about 4 MB at `AreaManifest`'s 160 kB estimate, an upper
  figure, not a measurement.
- *Direct reading*: COG block reads, not tiles. ADR 0023 puts a cold z12 view at 66 to 103 MB before T85 cut it to
  about a third, and `DEFAULT_MAX_ZOOM`'s KDoc says z15 costs about four times the bytes of z12 per view. At the
  default z12 there is no DEM fetch inside maps at all; the cost is vector z14 tiles over less-detail maps only.
- Task 2 measures both kinds.
Exit cost: the network member removed from the candidate list; the key left unread. Moving parts: the setting
(key, row, help, `POST /settings`), the online state as a lookup input, the batched refresh.
Setting: **yes**, by the owner's Answer; default on.

**B3. Online first wherever connected** (CalTopo's and Google's practice). Every tile from the network when it can,
maps only offline. Costs data at every zoom inside maps the user paid storage for, makes the view depend on signal,
and at direct defaults replaces a z13 map with z12 data. Contradicts offline first. Setting: the same switch
inverted.

**The style's top zooms** (objection 2). Row 6 caps the DEM, contour and shade sources at the deepest map's zoom
whenever a map has a DEM, so one z13 map lowers a server user's online view everywhere. The rule: each source's top
zoom in the style is the deepest stored zoom among the holdings on the phone and, with the setting on, the online
source's stored maximum; with it off, the phone's holdings alone (an area's or the cache's z15 tiles included).

**The setting** (T341 builds it first for the overview's zooms; this fixes its key and meaning):
- key `onlineDetail`, boolean, default `true` (a named constant), **per device, not synced** (objection 11): it
  steers what this phone downloads, the reason `DirectPrefs` is unsynced ("what one phone may download is that
  phone's business"); placed in Settings › Sources beside the Mobile data and limit rows
  (`firstrun/DirectSourcesRows.kt`), and in the server's section when a server is set; a `POST /settings` key.
- label "More detail from online" (the coordinator's under ADR 0031; the owner adjusts it afterwards);
- help: "Where the network has more detail than what this phone holds, download it. With the phone's own sources
  this counts toward Mobile data and its limit. Off: the phone shows what it holds, and the network is asked only
  where nothing here covers the view."
- meaning: the network may answer a tile ahead of the phone's deepest holding when its stored maximum is deeper (or
  the holding is the overview). It never stops the network answering where nothing on the phone does (the Mobile
  data switch's job), and never ranks tiles already on the phone.
- *A server's limit* (objection 10): the member adds no new kind of download for a server, which already fetches
  without a limit outside maps (ADR 0023); its brake is this switch. The ADR says so and the owner is told in the
  merge report; a session figure for server traffic is a later option.

### Q3. The join at a map's edge

**C1. Tile by tile (today).** One source per tile; offline at an edge a contour tile is the coarse map's whole or
the fine map's clipped (row 4); a hillshade or contour step at tile seams. Exit cost: none.

**C2. Cell by cell, one position-based blend** (objection 7). For the DEM role (DEM, shade, contours, the online
pyramid, and the sky views' `MergedTerrain`) each cell takes the deepest holding at it, and near a finer holding's
edge the two are blended with a weight that is a **pure function of the cell's geographic position** relative to
that holding's box and data mask, over a band in metres (set by a spike golden). Any two tiles or windows compute
the same value at the same place, so no new seam at tile boundaries, and the map and the sky views agree at the edge
because `MergedTile` and `MergedTerrain` call the same weight. `Composite.over`, which feathers by distance to the
NaN edge inside the grid it is given, is not used as is: per tile it would put the seam on the tile grid. Vector
tiles stay whole-tile (two archives' features would duplicate). The network member is whole per tile; a patch of
coarser tiles where fetches failed stays until the refresh, and the panel names it. Exit cost: the weight, the tile
function and their golden. Moving parts: one composition path for edge tiles. Setting: none.

**C3. A seam band only.** Keep C1 and draw a soft band over the seam. Hides the step without the fine strip's detail.

### Q4. The lookup

**D1. One pure function in `shared`** (`packs/Holdings.kt`). Inputs: a list of holdings, each with a kind, a stored
maximum zoom per role and a coverage predicate: each map's DEM and vector coverage (today's `PackCoverage`), each
downloaded area's **held cells** (a `holds(z, x, y)` predicate over `AreaPins` and the tile ledger, not its box;
objection 5), the cache as a `holds` predicate, the world overview, and the online source with its **stored**
ranges (objection 1) and a state (allowed; refused with the reason; offline; setting off); off maps never passed in.
Asked with a point or a tile and a role, it answers every holding that covers it in the order of Q1 and Q2, each
with its stored maximum, whether it stores, synthesises or overzooms there, whether it serves, and what the network
would add (its deeper zoom, or nothing). `PackPrecedence.ORDER` and `MergedTerrain.ORDER` come from it (one order),
and `MergedTerrain.pointZoom` takes the deepest holding including the online source's stored maximum (objection
12). `GET /holdings?lat=&lon=&z=` returns the answer. Readers ask through it: rows 1 to 6 and 33 per tile; 7 to 19
and 34 per window or point (`TerrainRead` gains a point read that replaces `pyramidAt` and `Trips.demSampler`); 20 to
24 through `osmCandidates`, which follow the order; 25 to 28 for the view's centre or box. Rows 29 to 32 read the
whole set or the primary and stay.

*Convergence with this wave* (objection 9): T341 lands first with its pure rule for which source answers a tile and
the setting; it is built as `shared/.../packs/Holdings.kt` with the overview and online members only, and task 1
extends that file rather than adding a second rule (if T341 merges under another name, task 1 moves it). T343's
coverage function lands first too, with a signature the lookup can serve (a box, a detail, the holdings), and task 5
re-points it at the lookup.
Exit cost: the readers revert to their own reads. Moving parts: one function, one route.

**D2. Reader by reader.** Fix each reader as a task touches it. The readers keep disagreeing until the last is
fixed. The owner rejected this shape in the idea's Answer.

### Q5. The first visible use

The coverage panel (`docs/ideas/2026-11-22-a-coverage-and-source-panel-under-the-status-chip.md`, the owner's
yes): tapping the status chip shows, for the view's centre, one line per holding from the lookup, the serving one
first: for example "Fine map name · full detail · shown", "Wide map name · medium detail · under it", "Online ·
more detail from z14 · shown" or "· not used: More detail from online is off", "A map here is off" (T343's state),
and where the view steps down, "Coarser here: the finer map ends". Its words come from the lookup alone. Built in
alpha and shown to the owner (ADR 0031).

## Recommendation

**A2, the phone's holdings by depth always, B2 by stored depth, C2 with one position-based weight, D1, then the
panel.** Depth before primary; primary kept as the map chosen last and a tie-break; the outdoors profile kept ahead
for vector. Tiles on the phone (maps, areas' held cells, the cache) ranked by stored depth regardless of any
setting. The network ahead of them only where its stored maximum is deeper, under `onlineDetail` (per device,
default on), never delaying a tile the phone holds. The style's top zooms from the real stored ranges. One lookup in
`shared` whose order `PackPrecedence` and `MergedTerrain` share, `GET /holdings` from the first task, the panel as
its first visible use. The viewshed stays on offline maps only, as T340 left it (the coordinator's ruling); reading
it online under the setting with a size cap is recorded as a later option.

**Build tasks for a later wave** (disjoint Owns; where two touch one file the later one is sequenced):
1. **The lookup and the order** (after T341, T342, T343, T352). `shared/.../packs/Holdings.kt` (extending T341's)
   and test, `PackPrecedence.kt` (A2) and `PackPrecedenceTest`, `shared/.../dem/MergedTerrain.kt` (the shared order
   and `pointZoom`) and its test, `app/Packs.kt` (the map inputs), the area and cache inputs (an accessor in
   `app/areas/Areas.kt`, `app/online/TileIndex.kt` read only), the online state (an accessor in
   `app/online/OnlineService.kt`), `app/PacksRoutes.kt` (`GET /holdings`), the fixture part's assertion block (each
   map primary in turn: the fine one serves inside it). The network member stays out of the tile paths here.
2. **The holdings on the phone and the network member** (after task 1). `app/TileService.kt` (`demTile`, `osmTile`,
   `shadeTile`, `contourTile`'s branch, `style()`'s top zooms, the batched refresh), `app/online/OnlineTiles.kt`
   (`packCovers*`, `viewSource`), `app/options/ShadeService.kt` (`tile`, `sunTile`'s `packCovers`),
   `app/terrain/TerrainRead.kt` (line 50: the online `ElevationSource`'s `maxZoom` from the stored range, not
   `OnlineTiles.DEM_MAX_ZOOM`); the setting's lines in `AppSettings.kt`, `firstrun/DirectSourcesRows.kt`,
   `settings/SettingsRoutes.kt` (`POST /settings`), `shared/.../controls/AndroidControls.kt`,
   `tools/demo/controls.json`, a `Help.kt` block. Done when: frames at z15 outside every map with a z13 map installed
   (a server set), and inside it with an area's z15 tiles; the bytes of a z14 and a z15 view inside a medium map
   with a server and with direct reading at z12 and z15; the source reloads and contour regenerations in a z14 pan,
   frames before and after a bump.
3. **The edge join** (after task 2: same files, other blocks). New `shared/.../dem/MergedTile.kt` and the shared
   blend weight (used by `MergedTerrain` too) with a golden spanning a tile boundary across the fine map's edge at
   z12, z14 and z15; `TileService.kt` (`generateContourTile`, `contourBytes`, the DEM edge path, `cliffDir` and
   `cliffPyramid` for row 33, a block closed by `// end Tnn`), `ShadeService.kt` (edge tiles, a block closed by
   `// end Tnn`).
4. **Point and elevation readers** (after task 3). `app/terrain/TerrainRead.kt` (a point read),
   `ShadeService.pyramidAt` (a block closed by `// end Tnn`), `trips/Trips.kt` (`demSampler`, `editor`),
   `readout/PressureCard.kt` (`PressureWatch`, `WayDownWatch`), `readout/LiveReadout.kt`, `tracking/TerrainSource.kt`
   (holdings on the phone only, never a fetch), `routing/RouteExposure.kt`, `routing/RouteBestStart.kt`,
   `routing/RoutePlanner.kt` (the crow line only), `nav/NavShade.kt`, `tracking/LongPressMenu.kt`,
   `controls/ScaleBarRoutes.kt`; the point readers' rule by the owner's answer to Question (a).
5. **Coverage readers** (after task 1). `app/online/OnlineView.kt` (the chip's source from the lookup),
   `online/OnlineOverlay.kt` (`offerDownload` from the lookup's "network adds" and T343's function),
   `search/PlaceSearch.kt` (`coverage`), `firstrun/FirstRun.kt` (inputs only), T343's function re-pointed.
6. **The coverage panel** (after task 5). New `app/online/CoveragePanel.kt`, the chip's tap block in
   `OnlineOverlay.kt` closed by `// end Tnn`, a `Help.kt` block, the a11y tool's block (screen `coverage-panel`),
   docs; strings to the owner.
7. **"Show first"** by the owner's answer to Question (b): `areas/OfflineMapList.kt`, `areas/AreasScreen.kt`,
   `docs/reference/android-app.md` (its line).

## What would change this recommendation

- The owner's report was on direct reading at defaults: then its DEM half is row 6's cap and the ignored areas and
  cache (both fixed in task 2), and the network member's DEM reach is moot until direct `maxZoom` is raised.
- Task 2 measures a z14 view inside a medium map well over the figures above, or the batched refresh makes the map
  flicker: then the bump only when the camera rests, or the setting defaulting off with the owner's agreement.
- A user with a plain z14 map and an outdoors z13 map over one place reports missing paths at z14: A3 for vector.
- Task 3's golden shows the blend costing more than a frame budget on the phone: C3 for shade, C2 for contours.
- The owner answers that the map chosen last should win everywhere: A1, or A1 for that map only (Question (b)).

## Claims to verify

- Row 2: a map's DEM answers every zoom from its first down (`PackPrecedence.zoomOk`, `DEM -> z >= c.minZoom`), so
  `OnlineTiles.packCoversDem` is true at z14 and z15 inside a z13 map and the fetcher is not asked.
- Row 6: `TileService.kt:534` caps the DEM source at the deepest map's zoom whenever any map has a DEM.
- Rows 14, 15, 17, 18, 34 read only the primary map (`Trips.elevation()` = `dem.get(app.tiles.packs.dem)`).
- `MergedTerrain.ORDER` puts every map before the online source whatever its depth, and `pointZoom` takes the deepest
  map holding the point (`MergedTerrain.kt:45-77`); `TerrainRead.kt:50` gives online `DEM_MAX_ZOOM`.
- The default online DEM stores to z12 (`DirectSources.DEFAULT_MAX_ZOOM`, `DirectPrefs.maxZoom`); a server declares
  `demMaxZoom` / `osmMaxZoom` (`OnlineService.kt:759`); `OnlineTiles.DEM_MAX_ZOOM` 15 and `OSM_MAX_ZOOM` 14 are URL
  ranges. Medium detail is DEM to z13 with vector to z14, less detail z8 to z13 (ADR 0047).
- The fetcher answers its cache and the versions pinned areas hold before the network (`OnlineFetcher.get`); Mobile
  data and the session limit are `DirectSources`' (`onMobileData`, `sessionCapBytes`); a server has neither.
- A refresh bump reloads the raster-dem source and the contour and shade URLs (`TileService.kt:540-559`,
  `DemSourceRefresh.kt`); `answerWithinMs` is set for direct reading only (`OnlineService.kt:216`).
- 160 kB per DEM tile is `AreaManifest`'s estimate (`ROUTE_DEM -> 160_000L`); ADR 0023's 66 to 103 MB for a cold z12
  direct view, cut to about a third by T85.
- The sources quoted under Prior art say what is quoted (each URL fetched 2026-11-23).

## Questions for the owner

Question (a): the elevation the readout shows, the pressure card's ground height (which calibrates the pressure
reading) and the terrain-aided position estimate (which matches the walk against the DEM) read the map chosen last
today. After the lookup, where the network has a deeper tile than the phone holds at the fix, which should they
read? A source that changes as the signal comes and goes moves the calibration and shifts the estimate's matches.
- A. What the phone holds, by depth (maps, areas, the cache), never a fetch for these readers (recommended: steady
  as signal changes, and the finest the phone holds at the fix, which today it is not).
- B. The network too, under "More detail from online", for the readout and the pressure card; the position
  estimate stays on what the phone holds.
- C. As today: the map chosen last.

Question (b): "Show first" on the Offline maps list (`areas/OfflineMapList.kt:161`) made a map the one shown first.
After depth goes first, it sets only where the map opens and which map wins between two of equal detail.
- A. Rename it to what it now does, "Start here" (recommended).
- B. Remove the action.
- C. Keep "Show first" with its old meaning for that one map (the chosen map wins over a deeper one: A1 for it).

## Revision: the critic's objections and responses

The review is `0066-critic-review.md` (REVISE: 1 blocking, 8 major, 5 minor). The coordinator's rulings of
2026-11-23 are applied.

| # | Severity | Objection | Resolution |
|---|---|---|---|
| 1 | blocking | The default online DEM stores only to z12; B2 took the URL range as depth. | Accepted. Depth is the stored range (`DirectSources.maxZoom`, a server's declared range); B2 restated per source kind; the cost redone per kind; `TerrainRead.kt:50` fixed in task 2; a What-would-change line; Claims corrected. |
| 2 | major | The style caps the DEM at the deepest map's zoom; row 6 wrong. | Accepted. Row 6 corrected; the rule under Q2; task 2 owns `style()`, with a z15 frame outside every map. |
| 3 | major | Shade, sun and contour paths left out of task 2. | Accepted. Task 2 owns `shadeTile`, `contourTile`'s branch and `ShadeService`'s `packCovers`. |
| 4 | major | The fallback timing described a mechanism the code lacks; the server case open. | Accepted with change. One rule for both kinds: the phone's tile at once, the network in the background, bumps batched on the stall recovery's settle; the reload cost in task 2's Done when. |
| 5 | major | The setting must gate the network, not on-phone tiles; areas are not boxes. | Accepted. On-phone holdings rank by depth always; help reworded; areas enter D1 as held cells. |
| 6 | major | "Show first" changes without a flag. | Accepted. Question (b) and task 7. |
| 7 | major | A per-tile feather puts a seam at tile boundaries; `MergedTerrain` does not feather. | Accepted. One position-based weight shared by `MergedTile` and `MergedTerrain`; the golden spans a tile boundary. |
| 8 | major | Row 4 wrong; TerrainSource and cliff ticks missing. | Accepted. Row 4 corrected; rows 33 and 34 added, in tasks 3 and 4. |
| 9 | major | T341's rule and T343's function converge nowhere. | Accepted. D1's convergence paragraph; task 1's inputs widened; task 2 owns `SettingsRoutes.kt`. |
| 10 | minor | No visible limit for a server user. | Accepted as a stated reason: no new kind of download for a server (ADR 0023); in the setting's paragraph, told to the owner; a server session figure is a later option. |
| 11 | minor | Sync and place of the setting. | Accepted. Per device, in Sources beside Mobile data. |
| 12 | minor | Reordering `MergedTerrain` alone does not deepen the sky views (`pointZoom`). | Accepted. `pointZoom` in D1, task 1 and Claims to verify. |
| 13 | minor | The owner's questions. | Accepted. The label and the viewshed ruled by the coordinator; Question (a) lays out the calibration and the estimate; Question (b) added. |
| 14 | minor | "Every app ... gives a switch" overreaches. | Accepted. "Three of the four that offer both document a switch." |
