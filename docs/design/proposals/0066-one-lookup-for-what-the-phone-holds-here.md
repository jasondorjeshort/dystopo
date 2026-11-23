# 0066. One lookup for what the phone holds here, and the order every reader takes from it

Date: 2026-11-23
Status: under review

## Question

At a point and a zoom the phone may hold several offline maps (each a DEM and a vector archive, at its own
detail), downloaded areas (tiles kept in the online store, ADR 0060), the built-in world overview, and an online
source that could fetch more. Today about thirty readers each ask "what is here?" in their own way (the table
below), so the map, the sky views, the readout, search and the first-run card disagree; the owner's reports of
2026-11-22 are that disagreement. This proposal decides: (1) the order of the maps, in particular whether depth
goes before the map the user chose last ("primary") in `PackPrecedence`, and what "primary" then means; (2) the
online source as a member of that order where it is deeper than what the phone holds, under the owner's setting
(default on) and with its data cost; (3) how a view of mixed detail joins at a map's edge; (4) one pure lookup in
`shared` that answers, for a point or a tile, what each source holds, which serves, and what the online source
would add, with the coverage panel under the status chip as its first visible use; (5) the build tasks. In ten
years every client (the phone, the web viewer, iOS) should ask one function this question, and adding a source
kind (a peer, a new detail level) should touch that function and nothing else.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later; the lookup is pure Kotlin in `shared` commonMain, inputs in, answer
  out, no `java.*`; offline first; every feature agent-drivable (a debug route); agents write all code; ADR 0025.
- The owner's terrain rule (2026-11-22): "look at the highest resolution available at the point and expect to step
  down to a coarser pack once it leaves the finer one". T340 built it for the terrain read only (`MergedTerrain`).
- The owner's Answer (`docs/ideas/2026-11-22-a-downloaded-map-must-never-make-the-view-worse-than-online.md`): a
  setting, default **on**: finer online tiles are fetched where the network gives more than the map; off saves data
  and accepts the lower detail. A setting by the owner's word, so ADR 0044's "is practice split?" is answered by
  the owner; the survey below agrees it is split.
- No Wi-Fi gates (ADR 0023): the online member obeys the Mobile data switch and the session limit the phone's own
  sources already enforce (`DirectSources`), never a Wi-Fi condition.
- A downloaded map must never make the view blank: when the online member cannot answer (offline, backoff, the
  limit, an error), the map's own tile answers at once, as today.
- Rendering conventions: coverage edges are feathered with `Composite.over`; every raster through `MercatorWindow`.
- Alpha (ADR 0033): changing the order and what "primary" means needs no migration; the stored `packs/active`
  file keeps its meaning as "the map chosen last".
- T342 (this wave) adds a per-map off switch: an off map is in no input of the lookup.
- User-visible text names no other mapping product.

## Every reader today (read at `bca04230`, 2026-11-23)

Paths under `androidApp/.../app/` unless `shared`. "Order" = `PackPrecedence.ORDER` (profile, **primary**, depth,
smaller box, name; first archive containing the tile, else first overlapping). 32 readers in seven groups.

| # | Reader | File | What it does today |
|---|---|---|---|
| **Tiles** | | | |
| 1 | Vector tile | `TileService.kt` `osmTile` | Online only where no map's vector archive answers (`packCoversOsm`: a map answers only inside its own zooms); else `osmCandidates` in Order; world overview last (z0 to z5). |
| 2 | DEM tile | `TileService.kt` `demTile` | Online only where no map's DEM answers; a map's DEM answers **every zoom from its first down** (deeper tiles synthesised), so inside a map the online source is never asked, even at z14 and z15 over a z13 map. |
| 3 | Hillshade, slope, sun tiles | `options/ShadeService.kt` `tile` via `pyramidFor` = `packs.demFor` | The one map Order picks per tile, else the online pyramid. |
| 4 | Contour tile | `TileService.kt` `contourTile`, `generateContourTile` | Map path when the tile lies wholly inside one map (`demContains`), cell windows merged from `demCandidates` at edges; else the online path, which reads map tiles where a map's box overlaps (`OnlineTiles.pyramid`, T98's floor). |
| 5 | Online pyramid | `online/OnlineTiles.kt` `demBytes`, `pyramid` | A map's tile wherever any map answers the tile, else the fetcher (cache, then network). |
| 6 | Style ranges | `TileService.kt` `style` | DEM and vector max zooms: the deepest map's, widened to the online source's when one is set. |
| **Terrain windows** | | | |
| 7 | Horizon view | `gnss/Gnss.kt` `sweep` | T340's `TerrainRead`: maps deepest first per cell, then online, then a map's whole-tile margin. |
| 8 | Panorama | `panorama/PanoramaData.kt` | Same read as 7. |
| 9 | Viewshed terrain | `viewshed/ViewshedSession.kt` | Same read, online left out (100 km of tiles; T340 left the question). |
| **Point elevation** | | | |
| 10 | Long-press sun answer | `options/ShadeService.kt` `pointAnswer` → `pyramidAt` | The first map `demFor` gives at z14, 12, 10 or 8 (Order), else the online pyramid. |
| 11 | Long-press line of sight | `tracking/LongPressMenu.kt` (`pyramidAt`) | As 10. |
| 12 | Turn-back terrain day | `nav/NavShade.kt` (`pyramidAt`) | As 10. |
| 13 | Route's best start | `routing/RouteBestStart.kt` (`pyramidAt`) | As 10. |
| 14 | Readout elevation | `readout/LiveReadout.kt` `demAt` → `trips/Trips.kt` `demSampler` | **The primary map's DEM only**; NaN elsewhere, no online. |
| 15 | Pressure card ground height | `readout/PressureCard.kt` `PressureWatch` → `Trips.demSampler` | As 14. |
| 16 | Way-down warning | `readout/PressureCard.kt` `WayDownWatch.sampler` | First DEM map in Order whose box holds the point, else the primary map; no online. |
| 17 | Route crow line, route editor | `trips/Trips.kt` `demSampler`, `editor` | As 14. |
| 18 | Scale bar's elevation route | `controls/ScaleBarRoutes.kt` (`live.demAt`) | As 14. |
| 19 | Route exposure | `routing/RouteExposure.kt` `demFor` | First DEM map in Order whose box holds the route's box, else the first overlapping; z14; no online. |
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
| 30 | Routing graph | `routing/RoutePlanner.kt` | Every map with a vector archive, the primary first; the maps near the ends. |
| 31 | Peers' catalog, attribution | `share/PeerServe.kt`, `MainActivity.kt` | Every map. |
| **The primary alone** | | | |
| 32 | Start view, unit seeding, contour warm-up | `MainActivity.kt`, `DystopoApp.kt`, `TileService.kt` `startWarmup` | The primary map's box. |

Planned this wave: T342's off switch (filters every input), T343's Download-card function (finest first over the
visible box), T341's tile-source rule and setting for the overview's zooms. A `Packs.kt` KDoc says depth before
primary; the code says the reverse (T342 fixes the KDoc).

## Prior art

Fetched in this run (2026-11-23), each read by the author; product names allowed here, not in the app.

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
  outside of the given bounds are requested". The renderer overzooms a single source; choosing between sources per
  tile is the app's job, as `TileService` does.

Not fetched (search summaries only, carry no weight): Gaia GPS's help (403 to the fetcher; the summary says its
downloads cap at a source-specific zoom and recommends testing in airplane mode); a Locus forum reply that users
can choose "automatic", "online only" or "offline only"; Organic Maps being offline only.

**Reading.** Practice is split on the order of downloaded and online data: CalTopo and Google Maps go online first
when connected, Locus and OsmAnd stay offline first with online as an opt-in. None documented compares the
*detail* of the two at a point; all choose by source, not by depth. Every app that offers both gives the user a
switch. Merging several offline maps into one view is Locus's documented behaviour. The owner's rule (finest at the
point) is therefore a departure in kind: depth decides, not whether the data is downloaded; the setting matches
common practice.

## Options

### Q1. The order of the maps

**A1. Keep Order** (profile, primary, depth, box, name). Nothing to build. The owner's two-map reports stay: a
coarse map chosen last wins inside a fine one for tiles, shade, contours and every reader in rows 10 to 24. Exit
cost: none. Moving parts: none. Setting: none.

**A2. Depth before primary** (profile, depth, primary, box, name). The DEM's profile is always `unknown`, so for
elevation depth comes first outright; for vector the outdoors schema still beats a plain one at the same place
(it carries layers the style draws), then depth (a less-detail map stops at z13, the others at z14). "Primary"
becomes **the map chosen last**: where the map opens, unit seeding, the contour warm-up, the routing graph's build
order, and a tie-break between two maps of equal depth. Rows 14 to 19, which read the primary alone, move to the
lookup (Q4), so the readout and the pressure card read the finest map at the fix, not the chosen one. Exit cost:
one comparator line and its tests. Moving parts: none new. Setting: none (the owner's rule; one order).

**A3. Depth before profile too** (depth, profile, primary, ...). Differs from A2 only for vector, where a plain
z14 map would beat an outdoors z13 map: more labels and paths at z14, but no cliffs, huts or wilderness. The
style is written for the outdoors schema, so the plain map loses features the style draws at every zoom. Exit
cost and parts as A2.

### Q2. The online source in the order

**B1. Last resort (today).** Online only where no map answers. Inside a medium map (DEM to z13) the view at z14 and
deeper is synthesised from z13 while the network could give z14 and z15: the owner's "downgraded by downloading".
Contradicts the owner's Answer.

**B2. A member by depth, under the setting.** For each tile and role, the lookup's candidates are the maps in A2
order and the online source with its zoom range (DEM to z15, vector to z14, `OnlineTiles`). The online source
goes **ahead of a map exactly when the map would synthesise or overzoom the tile** (z deeper than the map's stored
maximum, or the map is the world overview, T341) **and the online source stores that zoom**, the setting is on,
and the fetcher may fetch (network, Mobile data, the session limit). Otherwise it stays behind every map, as
today. The fetcher's cache answers first, so an area's kept tiles and tiles seen earlier cost nothing. When the
online tile is not in the cache: wait for it within the existing `answerWithinMs`; past that, answer with the
map's synthesised tile at once and keep fetching; when the tile lands, bump the source's refresh counter (the
`demSourceRefreshes` / `osmSourceRefreshes` mechanism) so the map asks again. Offline or refused: the map's tile,
no wait (the fetcher's backoff already says so).
*Data cost:* only zooms past a map's stored maximum. For a medium map that is the DEM at z14 and z15 (the map's
vector already reaches the online source's z14); for a less-detail map also vector z14. A fresh phone view at z14
needs about 24 DEM tiles of 256 px (a 512-px map tile is four DEM tiles one zoom deeper, about six map tiles on a
phone screen); at the estimate `AreaManifest` uses (160 kB per DEM tile, an upper figure) that is up to about 4 MB
per new view, nothing for a view seen before. A full-detail map (DEM to z15) costs nothing. To measure in the build.
Who uses it: CalTopo and Google go further (online first everywhere). Exit cost: the online member removed from
the candidate list; the setting's key left unread. Moving parts: the setting (key, row, help, sync, `POST
/settings`), one more input to the lookup, the refresh bump on an upgraded tile.
Setting: **yes**, by the owner's Answer; default on.

**B3. Online first wherever connected** (CalTopo's and Google's practice). Every tile from the network when it can,
maps only offline. Costs data at every zoom inside maps the user paid storage for, makes the view depend on signal
in the backcountry, and leaves maps unused while their bytes count; it also changes what peers and the chip report
when online. Contradicts offline first. Exit cost: as B2. Setting: would need the same switch inverted.

The setting, for B2 (T341 builds it first for the overview's zooms; this gives its key and meaning):
- key `onlineDetail` (synced, boolean, default `true`, a named constant), in Map options after the Mobile data rows;
- label "More detail from online", help: "Where the network has more detail than your offline maps, use it.
  This uses mobile data when Mobile data is on, within its limit. Off: your offline maps are shown alone where
  they cover the view, and the world overview at the widest zooms.";
- meaning: the online source may answer a tile ahead of a map or the overview when it stores a deeper zoom than
  the answering map (or the map is the overview); it never stops the online source answering where no map does
  (that is the Mobile data switch's job).

### Q3. The join at a map's edge

**C1. Tile by tile (today).** One source per tile; the seam runs on tile boundaries, and inside a tile that a fine
map's box crosses, `contains` makes the coarse map answer the whole tile. A hillshade or contour step at the seam,
or a fine map's edge strip drawn coarse. Exit cost: none.

**C2. Cell by cell inside a DEM tile, feathered.** For the DEM role (DEM, shade, contours, the online pyramid) a
tile is composed like `MergedTerrain.read`: each cell from the deepest source that holds it, the next one where it
ends, the two blended over a few cells at the finer map's box with `Composite.over` (the project's convention for
coverage edges). Vector tiles stay whole-tile (C1): merging features from two archives would duplicate roads and
labels, and both maps carry the same OpenStreetMap data at z14. Contours then join, since one window gives one
field. Cost: one pure function in `shared` beside `MergedTerrain` and its golden; DEM tiles across an edge are
composed instead of copied (only edge tiles; inside a map the fast path stays). The online member (B2) is whole
per tile: where some online tiles failed, a patch of coarser tiles shows until the refresh; the panel (Q5) names
it. Exit cost: the function and its golden. Moving parts: one composition path for edge tiles. Setting: none.

**C3. A seam band only.** Keep C1 and draw a soft band over the seam. Hides the step without giving the fine map's
strip its detail. Cheaper, and wrong where the band is widest.

### Q4. The lookup

**D1. One pure function in `shared`** (`packs/Holdings.kt`): inputs are a list of `Holding`s (each map's DEM and
vector coverage as today's `PackCoverage`, each downloaded area's box and zoom ranges with `demMaxZoom` (ADR 0047),
the world overview, the online source with its zoom ranges and a state: allowed, refused with the reason, or
offline; off maps never passed in) and a point or a tile with a role. The answer: every holding that covers it, in
the order Q1 and Q2 give, each with its stored maximum zoom, whether it stores, synthesises or overzooms that
zoom, whether it serves, and what the online source would add (its deeper zoom, or nothing). `PackPrecedence`
stays the comparator and the tile tests the lookup uses; `MergedTerrain.ORDER` becomes the lookup's order. The app
side builds the inputs once per `PackSet` (and when a setting or the network changes); readers ask through it:
rows 1 to 6 per tile, 7 to 19 per window or point (`TerrainRead` gains a point read that replaces `pyramidAt` and
`Trips.demSampler`), 20 to 24 through `osmCandidates` (which follow the order unchanged), 25 to 28 for the view's
centre or box. Rows 29 to 32 read the whole set and stay as they are. `GET /holdings?lat=&lon=&z=` returns the
answer as JSON. Exit cost: the readers revert to their own reads (as today). Moving parts: one function, one route.

**D2. Reader by reader.** Fix each reader as a task touches it. No shared answer; the readers keep disagreeing
until the last is fixed, and the chip, the panel and the first-run card still each derive their own. The owner
rejected this shape in the idea's Answer.

### Q5. The first visible use

The coverage panel (`docs/ideas/2026-11-22-a-coverage-and-source-panel-under-the-status-chip.md`, the owner's
yes): tapping the status chip shows, for the view's centre, one line per holding from the lookup, the serving one
first, for example "Fine map name · full detail · shown", "Wide map name · medium detail · under it",
"Online · more detail from z14 · shown" or "· not used: More detail from online is off", "A map here is off"
(T343's state), and where the view steps down, "Coarser here: the finer map ends" (the coarse-map hint folded in
at triage). Its words come from the lookup; the panel reads nothing else. Built in alpha and shown to the owner
(ADR 0031). Alternative: a debug route only, no panel; cheaper, but the owner asked for the panel.

## Recommendation

**A2, B2, C2, D1, then the panel.** Depth before primary, primary kept as the map chosen last and a tie-break;
the outdoors profile kept ahead for vector because the style draws its layers. The online source joins the order
by depth under the setting `onlineDetail` (default on, the owner's Answer), only where a map would synthesise or
overzoom, never blanking a tile, its cost limited to zooms past a map's stored maximum. DEM tiles across a map's
edge composed cell by cell and feathered; vector tiles whole. One lookup in `shared` that every reader in rows 1
to 28 asks, `GET /holdings` from the first task, and the panel as its first visible use. First step: the lookup
and the order change with their tests (task 1 below), which moves rows 1 to 6 and 20 to 24 at once because they
already go through `PackSet`.

**Build tasks for a later wave** (disjoint Owns; where two touch one file the later one is sequenced, as noted):
1. **The lookup and the order.** `shared/.../packs/PackPrecedence.kt` (A2) and `PackPrecedenceTest`, new
   `shared/.../packs/Holdings.kt` and test, `shared/.../dem/MergedTerrain.kt` (takes the lookup's order) and its
   test, `app/Packs.kt` (builds the inputs; after T342 and T352), `app/PacksRoutes.kt` (`GET /holdings`), the
   fixture part's assertion block (both maps primary in turn: the fine one serves inside it). Docs: the routes.
2. **The online member and the setting** (after T341 and task 1). `app/TileService.kt` (`demTile`, `osmTile`, the
   refresh bump), `app/online/OnlineTiles.kt` (`packCovers*`, `viewSource`), the setting's lines in
   `AppSettings.kt`, `settings/SettingsPages.kt`, `settings/SettingsLayout.kt`, `sync/SettingsSync.kt`,
   `shared/.../controls/AndroidControls.kt`, `tools/demo/controls.json`, a `Help.kt` block; the bytes a z14 and a
   z15 view inside a medium map fetch, measured.
3. **The edge join** (after task 2: same files' other blocks). New `shared/.../dem/MergedTile.kt` and its golden,
   `app/options/ShadeService.kt` (tiles), `TileService.kt` (`generateContourTile`, the DEM edge path only, a block
   closed by `// end Tnn`); frames across the fixture's fine edge at z12, z14 and z15.
4. **Point and elevation readers.** `app/terrain/TerrainRead.kt` (a point read), `ShadeService.pyramidAt` (a block
   closed by `// end Tnn`, after task 3), `trips/Trips.kt` (`demSampler`, `editor`), `readout/PressureCard.kt`
   (`PressureWatch`, `WayDownWatch`), `readout/LiveReadout.kt`, `routing/RouteExposure.kt`,
   `routing/RouteBestStart.kt`, `nav/NavShade.kt`, `tracking/LongPressMenu.kt`, `controls/ScaleBarRoutes.kt`;
   whether the readout reads online is the owner's question 2.
5. **Coverage readers.** `app/online/OnlineView.kt` (the chip's source from the lookup), `online/OnlineOverlay.kt`
   (`offerDownload` from the lookup's "online adds" and T343's function), `search/PlaceSearch.kt` (`coverage`),
   `firstrun/FirstRun.kt` (inputs only); T343's function rebased on the lookup.
6. **The coverage panel** (after task 5). A new `app/online/CoveragePanel.kt`, the chip's tap block in
   `OnlineOverlay.kt` closed by `// end Tnn`, a `Help.kt` block, the a11y tool's block (screen `coverage-panel`),
   docs; strings to the owner.

## What would change this recommendation

- The task-2 measurement shows a z14 view inside a medium map fetching well over the 4 MB estimate, or the
  refresh bump making the map flicker: then B2 with a per-session cap of its own, or the setting defaulting off
  with the owner's agreement.
- A user with a plain z14 map and an outdoors z13 map over one place reports missing paths at z14: A3 for vector.
- The edge golden shows C2's feather costing more than a frame budget on the phone: C3 for shade, C2 for contours.
- An owner's answer that the map chosen last should win everywhere (A1) for a reason not in the reports.

## Claims to verify

- Row 2: a map's DEM answers every zoom from its first down (`PackPrecedence.zoomOk`, `DEM -> z >= c.minZoom`), so
  `OnlineTiles.packCoversDem` is true at z14 and z15 inside a z13 map and the online source is not asked.
- Rows 14, 15, 17, 18 read only the primary map (`Trips.elevation()` = `dem.get(app.tiles.packs.dem)`).
- `MergedTerrain.ORDER` puts every map holding a cell before the online source whatever its depth (`{ if
  (it.online) 1 else 0 }` first), so T340's read also downgrades inside a medium map.
- Online DEM to z15 and vector to z14 (`OnlineTiles.DEM_MAX_ZOOM`, `OSM_MAX_ZOOM`); medium detail is DEM to z13
  with vector to z14 and less detail z8 to z13 (ADR 0047).
- The fetcher answers its cache (and the versions pinned areas hold) before the network
  (`online/OnlineFetcher.kt` `get`), so an area's kept tiles are free under B2. Mobile data and the session limit
  are the phone's own sources' rules (`online/DirectSources.kt` `onMobileData`, `DirectPrefs` `directSessionMb`);
  a configured server has neither, so under B2 a server user's only brake is this setting.
- A tile answered with the map's synthesised version is asked again after `demSourceRefreshes` is bumped
  (`OnlineService.kt` near line 557, the style URL's `-d` suffix in `TileService.style`).
- 160 kB per DEM tile is `AreaManifest`'s estimate (`ROUTE_DEM -> 160_000L`), not a measurement.
- The sources quoted under Prior art say what is quoted (each URL fetched 2026-11-23).

## Critic's objections and responses

(none yet)

## Questions for the owner (multiple choice, after the critic)

1. The setting's label: (a) "More detail from online" (recommended); (b) "Use online data for more detail";
   (c) "Prefer the finest map, online included".
2. The readout's and pressure card's elevation when the network has a deeper tile than the map at the fix:
   (a) read online under the same setting (one tile, a few kB a fix area; recommended); (b) offline maps only.
3. The viewshed (100 km of terrain) online: (a) offline maps only, as T340 left it (recommended); (b) online
   under the setting with a size cap; (c) online only when the view has no map at all.
