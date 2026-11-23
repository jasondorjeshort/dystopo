# Critic's review of proposal 0066 (T350, branch `task/T350` at 58f92c8f)

Reviewer: critic (read-only). Read: the proposal, `CLAUDE.md`, the `proposal` skill, the wave 47 paragraph and the
T340 to T352 entries in `docs/TASKS.md`, the three ideas, ADRs 0023, 0041, 0044, 0047 (by its use), 0056 (by its
use), 0060, T340's merge body (`git log -1 0c345ba1`), and the code at the branch head. Paths below are under
`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/` unless they start with `shared/`.

## What holds

- **Spot-checked rows (17 of 32): correct as written.** 1 (`TileService.osmTile` 595-605), 3 (`ShadeService` via
  `pyramidFor` = `packs.demFor`, `TileService.kt:352`), 5 (`OnlineTiles.demBytes` 239-251), 7 to 9 (`TerrainRead`,
  `Gnss.kt:395-398`), 10 (`ShadeService.pyramidAt` 291-293, `POINT_ZOOMS` then `online.pyramid()`), 14 and 15
  (`Trips.kt:139` `dem.get(app.tiles.packs.dem)`; `LiveReadout.kt:371`, `PressureCard.kt:194`), 16
  (`PressureCard.kt:692`), 19 (`RouteExposure.kt:78-82`), 25 (`OnlineTiles.viewSource` 397-409), 27
  (`PlaceSearch.kt:292-299`), 28 (`FirstRun.kt:118-125`, `MapMessage.kt:31-40`), 30 (`RoutePlanner.kt:202-204`), 32
  (`DystopoApp.kt:183`, `MainActivity.kt:166`, `TileService.kt:772`).
- **Row 2's mechanism** is right: `PackPrecedence.zoomOk` DEM is `z >= c.minZoom` (`PackPrecedence.kt:86-87`), so
  `packCoversDem` is true at every zoom below a map's first and `TileService.demTile` never reaches online inside a
  map (`TileService.kt:570`). See objection 2 for what the claim leaves out.
- **`MergedTerrain` puts every map ahead of online whatever its depth**: `ORDER` starts with
  `{ if (it.online) 1 else 0 }` (`shared/.../dem/MergedTerrain.kt:45-50`) and `read` fills every map in its box before
  the online source (lines 104-107). True; objection 12 adds a second cause.
- **Depth before primary breaks none of the primary's readers.** Units seeding, the start view, the warm-up,
  `contourCacheVersion`, `PackManager.jumpTo` and `OnlineView.kt:52` read `PackSet.primary` / `packs.dem`, which is
  chosen by name (`Packs.kt:87-91`), not from the sorted list; `RoutePlanner.regions` sorts by name too;
  `MapMessage` reads boxes only; search indexes read every map. Only the *user-visible* meaning of "primary" breaks
  (objection 6).
- **The Mobile data and session-limit correction is right**: both live in `DirectSources` (`DirectSources.kt:77-99`,
  `sessionCapBytes` 127) and ADR 0023 says "A configured server is unaffected by it".
- **Prior art was fetched.** I re-fetched three: CalTopo's training page (both quoted sentences verbatim, and the
  Offline Mode toggle), Locus's maps settings (the "seamless map display", "Online LoMap as backup ... loads when you
  go out of the area covered by your downloaded offline LoMaps", "Cached maps only" all verbatim; no winner for
  overlaps, as the proposal says) and Mapbox's offline concepts (read-only tile store, "only interacts with it to
  check for tiles it needs", missing tiles into the disk cache, "does not share tiles with the disk cache", bands
  0-5 / 6-10 / 11-14 / 15-16, all verbatim). The unfetched claims are fenced off and carry no weight in the reading.
- Every file the six tasks name exists (or is marked new); the Owns are disjoint once the stated sequencing is kept.

## Objections

### 1. BLOCKING: the default online source stores DEM only to z12, so B2 as written would replace a map's tile with a coarser one and pay for it

**Evidence.** The phone's own sources (`direct://sources`, the default online source) render DEM to
`DirectSources.maxZoom`, default `DEFAULT_MAX_ZOOM = 12` (`online/DirectSources.kt:901-905`: "26 m cells ... deeper
tiles are synthesized from it. 15 renders every zoom as the server does, at about four times the bytes per view"),
set from `DirectPrefs.maxZoom` (`online/OnlineService.kt:71`, `DirectPrefs.kt:37`); a tile deeper than that is
`synthesize(t)` from the z12 ancestor (`DirectSources.kt:333`, `474-482`). `OnlineTiles.DEM_MAX_ZOOM = 15` is only
the URL range (`OnlineTiles.kt:497-499`). The proposal's B2 ("the online source stores that zoom", the online source
"DEM to z15"), Claims to verify ("Online DEM to z15 ... `OnlineTiles.DEM_MAX_ZOOM`") and the cost figure all take
the URL range as the stored depth. With defaults, inside a medium map (DEM z13) the online DEM is *coarser* than the
map at every zoom: B2 would put a z12-synthesised tile ahead of a z13-synthesised one at z14 and z15, render COG
blocks for it, and call it more detail. The same error sits in T340's read: `TerrainRead.kt:50` gives the online
`ElevationSource` `maxZoom = OnlineTiles.DEM_MAX_ZOOM`, so the moment `MergedTerrain.ORDER` goes by depth the sky
views would prefer a z12 source over a z13 map.

The cost estimate is off for the same reason: the direct source fetches COG blocks, not 160 kB tiles; ADR 0023 puts a
cold z12 view at 66 to 103 MB before T85 cut it to about a third, and `DEFAULT_MAX_ZOOM`'s KDoc says z15 costs about
four times that. "Up to about 4 MB per new view" holds, if at all, only for a configured server.

**Edit.** (a) The online holding's stored maximum comes from the source, not the constant: `DirectSources.maxZoom`
for direct reading, the server's advertised range (`/live/info` or the area help's zoom caps) for a configured
server; the lookup's `Holding` for online carries it, and `TerrainRead` takes the same figure. (b) Restate B2's gain
per source kind: with the defaults the DEM gains nothing over a medium or full map (vector z14 over a less-detail
map, and the overview's zooms, T341, are the gains); a server user gains DEM z14 and z15. (c) Redo the data cost per
kind (server tiles; direct COG reads, citing ADR 0023's figures) and make task 2 measure both. (d) Add a "What
would change" line: if the owner's report was on direct reading at defaults, the DEM half of the report is not this
cause (objection 2's cap is the better suspect) and the setting's DEM reach is moot until `maxZoom` is raised. (e)
Correct the Claims to verify line.

### 2. MAJOR: the style caps the DEM source at the deepest map's zoom whenever any map has a DEM; row 6 is wrong and the downgrade is wider than the proposal says

**Evidence.** `TileService.kt:534`: `val demHi = if (o == null) demMaxZoom else if (packs.demPacks.isNotEmpty())
demMaxZoom else OnlineTiles.DEM_MAX_ZOOM` (T41, c2298e78), with `demMaxZoom` the deepest map's max (`:378`), and the
contour and shade sources' tops derived from it (`:551`, `:561`). Row 6 says the ranges are "widened to the online
source's when one is set"; for DEM they are not. With one z13 map on the phone MapLibre never asks for a z14 or z15
DEM tile *anywhere on earth*, so a server user browsing far outside the map sees z13 overzoomed: downloading one
medium map lowers the online view everywhere. That is the owner's sentence more literally than row 2, and no task
owns the fix.

**Edit.** Correct row 6. Task 2 owns `TileService.style()` (the DEM, contour and shade top zooms) and states the
rule: the request range is the union of the maps' and the online source's real stored range (objection 1) when the
setting is on, the maps' alone when off; add a Done-when frame at z15 outside every map with a z13 map installed.

### 3. MAJOR: B2's visible gain needs the shade and contour paths, which task 2 does not touch

**Evidence.** Shade and sun tiles take the map's pyramid whenever `packCovers` (`TileService.kt:593`;
`ShadeService.kt:212-217`); contour tiles take the map's pyramid whenever `packCoversContour` = `demContains`
(`TileService.kt:647`, `655-656`; `OnlineTiles.kt:84`). Changing `demTile` and `osmTile` alone deepens only the
raster-dem source; the custom shade, the sun layer and the contours stay synthesised from the map. Task 3 touches
those files only for edge tiles.

**Edit.** Express the online member once, as the tile loader of the pyramid that `demTile`, `ShadeService` and
`contourTile` read (one per-tile choice from the lookup), and give task 2 the three switch points
(`TileService.shadeTile`, `contourTile`'s branch, `ShadeService.tile/sunTile`'s `packCovers`), or move them into task
1's "rows 1 to 6 at once" with the setting off by default until task 2.

### 4. MAJOR: the fallback timing describes a mechanism the code does not have and leaves the server case open

**Evidence.** `answerWithinMs` is set only for direct reading (`OnlineService.kt:216`: `if (it.server ==
DirectSources.SERVER) DirectSources.ANSWER_WITHIN_MS else null`, 9 000 ms, `DirectSources.kt:937`); for a configured
server a DEM request waits the fetcher's own timeout. On timeout today the answer is a 503 that MapLibre retries
(`OnlineTiles.kt:105-116`), not the map's tile. "Answer with the map's synthesised tile at once and keep fetching;
when the tile lands, bump the refresh counter" is a new path; a bump of `demSourceRefreshes` swaps the raster-dem
source and changes the contour and shade URLs too (`TileService.kt:540`, `544`, `547`, `559`;
`DemSourceRefresh.kt:17-24`), so each upgrade reloads every visible contour and shade tile. Nine seconds of a blank
tile inside a map that has the tile would itself be a regression of the "never blank" constraint.

**Edit.** Specify one budget for both source kinds and make it short (answer from the map at once unless the online
tile is already on the phone; fetch in the background); coalesce bumps (the stall recovery's settle, `GET /online`
`stalledDem`), one bump per settled batch; task 2's Done when: the number of source reloads and contour regenerations
during a z14 pan inside a medium map, and frames before and after a bump.

### 5. MAJOR: the setting should gate the network, never tiles already on the phone; and areas are not boxes

**Evidence.** Inside a map `demTile` never reaches the fetcher (`TileService.kt:570`), so an area's kept z15 DEM tiles
(in the online store, ADR 0060) or tiles fetched earlier are never shown inside a z13 map today: a downgrade with no
data cost at all. B2 lets them in only under the setting ("the fetcher's cache answers first"), and the help text's
"Off: your offline maps are shown alone" keeps that downgrade for users who turn it off to save data. Separately, D1
models a downloaded area as "box and zoom ranges with `demMaxZoom`"; areas are polygons and corridors (ADR 0041) whose
holdings are per-cell masks (ADR 0060 "The cell"; `AreaPins.holds`), so a box answers "held" over tiles never fetched,
which is the Download card's and the panel's exact question.

**Edit.** State as part of the decision: tiles on the phone (maps, areas' kept tiles, cached tiles) are ranked by
depth whatever the setting; the setting gates only network fetches. Reword the help's "Off" sentence accordingly. In
D1, an area's input is its held cells (or a `holds(z, x, y)` predicate over `TileIndex`/the ledger), not its box; the
panel and T343's function take the same input.

### 6. MAJOR: "Show first" is the user-visible face of "primary"; A2 changes a flow without flagging it

**Evidence.** The Offline maps list offers the action "Show first" (`areas/OfflineMapList.kt:161`, `249`;
`areas/AreasScreen.kt:313` calls `manager.activate`), documented as "Show first makes a usable pack the one shown
first" (`docs/reference/android-app.md:78`, ADR 0056's proposal line 497). Under A2 it no longer shows that map first
anywhere another map is deeper; it becomes the start view and a tie-break. The proposal never mentions it. CLAUDE.md
(Decisions): "A removed flow ... still flagged, not guessed."

**Edit.** Add an owner question (multiple choice): (a) rename it to what it now does, e.g. "Open the map here" /
"Start here" (recommended), (b) remove it, (c) keep "Show first" meaning the old order (A1 for that map only). Note
the alternative that the primary keeps winning among maps of equal depth only, which A2 already gives.

### 7. MAJOR: C2's feather, computed per tile, would put a seam at tile boundaries

**Evidence.** `Composite.over` feathers by each cell's distance to the top grid's NaN edge *inside the grid it is given*
(`shared/.../dem/Composite.kt:6-14`). Composing each 256-px DEM tile on its own gives two neighbouring tiles different
distance fields near their shared edge, so elevations disagree at the tile boundary: a contour break or shade step,
the defect C2 is meant to remove, moved onto the tile grid. Also `MergedTerrain.read` does not feather (a hard switch
per cell, lines 104-107), so "like `MergedTerrain.read` ... blended" is two different behaviours, and the sky views
and the map would then disagree at the same edge.

**Edit.** Define the blend weight as a pure function of a cell's geographic position relative to the finer source's
box (and its data mask), with a band in metres, so any two tiles or windows compute the same value; `MergedTile` and
`MergedTerrain` share it. Task 3's golden spans a tile boundary across the fine map's edge at z12, z14 and z15.

### 8. MAJOR: the reader table has a wrong row and misses readers that matter

**Evidence.** Row 4: an offline contour tile at an edge reads **one** map's pyramid (`TileService.kt:655-656`,
`contourBytes` → `pyramid(pack)` 689); `demCandidates` there feeds only the `contourNoDemOverPack` counter
(`:737-739`). Online, the mixing is per DEM tile in `OnlineTiles.demBytes`, not per cell. Missed readers:
`tracking/TerrainSource.kt:79` (the terrain-aided position estimate reads `Trips.demSampler`, the primary's DEM; it
must never fetch, and a source switch mid-walk moves its matches); cliff ticks in vector tiles read the DEM map
`demFor` picks (`TileService.kt:629-641`, `cliffDir`), so under B2 a vector tile from one map is oriented by another
map's DEM; `RoutePlanner.kt:459` (crow) is in row 17 by name but not by file.

**Edit.** Correct row 4 (C1's offline edge is worse than stated: the coarse map's whole tile or the fine map's clipped
one). Add TerrainSource (task 4, on-phone tiles only, no fetch) and the cliff reader (task 1 or 3) to the table and
the tasks.

### 9. MAJOR: T341 and T343 build overlapping rules this wave; the proposal does not say how they converge

**Evidence.** T341's Owns: "a pure rule for which source answers a tile and its test [new]" plus the setting's key
"as T350's decision gives them" (`docs/TASKS.md:44-45`); T343 builds "one coverage function over the visible box"
(`:60`). Task 1's `Holdings` is the same question. Task 5 rebases T343's function; nothing rebases T341's rule.

**Edit.** In the decision: T341's rule is the lookup's first slice (it lands as `shared/.../packs/Holdings.kt` with
the overview and online members only, and task 1 extends it), or T341 builds no rule and asks task 1's; T343's
function keeps a signature the lookup can serve. Also give task 1 the inputs it needs beyond `Packs.kt` (the area
holdings, objection 5, and the online state from `OnlineService`), and task 2 `SettingsRoutes.kt` (`POST /settings`
keys, `SettingsRoutes.kt:293`).

### 10. MINOR: ADR 0023 wants a visible limit on a new download path; a server user gets only a switch

**Evidence.** ADR 0023, Consequences: "A new feature that downloads must come with a visible limit or budget". The
proposal concedes "a server user's only brake is this setting". **Edit.** Either a session figure for server traffic
on the same row as the direct one, or a sentence in the ADR that the online member adds no new kind of download for a
server (it already fetches outside maps unlimited) and the owner is told; one line in the owner's questions if the
coordinator does not settle it.

### 11. MINOR: the setting's sync and place

**Evidence.** `DirectPrefs` is deliberately not synced: "what one phone may download is that phone's business"
(`DirectPrefs.kt:7`). The proposal makes `onlineDetail` synced and places it "in Map options after the Mobile data
rows", but those rows are in Settings › Sources (`firstrun/DirectSourcesRows.kt:37-43`). **Edit.** Per device, beside
the Mobile data and limit rows in Sources, or say why it differs (ADR 0044 asks the report to say).

### 12. MINOR: reordering `MergedTerrain` alone does not make the sky views read deeper data

**Evidence.** The window's zoom is `pointZoom`: the deepest map holding the point, capped at `POINT_ZOOM = 14`
(`MergedTerrain.kt:71-77`, `Gnss.kt:397`, `ShadeTiles.kt:178`); online is consulted for the zoom only where no map
reaches. Inside a z13 map the window is read at z13 whatever the order. **Edit.** Claims to verify names `pointZoom`
too; task 1 (or 4) changes it with the order, using objection 1's real online depth.

### 13. MINOR: the owner's questions

- Q1 (the label) is the coordinator's under ADR 0031: built in alpha, shown in the report, adjusted afterwards. Drop
  it or keep it only as "shown for adjustment".
- Q2 (readout online) misses the consequences that decide it: the readout's ground height calibrates the pressure
  card, and `TerrainSource` matches the walk against the DEM; a source switching with signal moves both. Recommended
  answer: tiles on the phone (cache and areas included, objection 5) but no fetch for the point readers; the option
  "read online under the setting" second.
- Q3 (viewshed) can be the coordinator's if the setting's meaning is "fetch where deeper, within the visible limit";
  keep it only if a size cap is a new user-facing figure.
- Missing: "Show first" (objection 6); the server limit if the coordinator does not settle objection 10.

### 14. MINOR: one sentence of the Prior art reading overreaches

"Every app that offers both gives the user a switch" contradicts the proposal's own Google Maps line ("the page shows
no switch"). **Edit.** "Three of the four that offer both document a switch."

## What to decide in the ADR now, and what to defer

Decide now: A2 (depth before the map chosen last; profile first for vector); D1's shape and that the order lives in
the lookup (`MergedTerrain.ORDER` and `PackPrecedence.ORDER` from one source); that tiles on the phone are ranked by
depth regardless of the setting and the setting gates only network fetches (objection 5); that the online member's
depth is the source's real stored range (objection 1); the setting's key, default and scope (per device or synced,
objection 11); the fate of "Show first" once the owner answers. Defer to the build tasks with measurements: C2's band
width and the frame budget (after a spike golden, objection 7); the upgrade path's wait and refresh coalescing
(objection 4); the data figures per source kind; the panel's strings (ADR 0031).

## Verdict: REVISE

The proposal is careful, its code read is mostly right (seventeen rows confirmed, the two headline claims about
`zoomOk` and `MergedTerrain.ORDER` true), its sources were genuinely fetched and quoted accurately, and A2 with a
single lookup is the right direction: depth before the chosen map breaks no reader of the primary. But its central
new behaviour, B2, rests on a false premise: the default online source stores DEM only to z12 (`DirectSources
.DEFAULT_MAX_ZOOM`), so with defaults the network has no deeper elevation than a medium map and B2 would swap a map's
tile for a coarser one at a direct-reading cost the estimate understates by an order of magnitude. It also misses the
style's DEM cap that lowers the online view everywhere once any map is installed (the likelier cause of the owner's
report for a server user), leaves the shade and contour paths out of the task that would deliver the gain, describes
a fallback the code does not have, would feather per tile into new seams, models polygon areas as boxes, keeps
on-phone finer tiles behind a data setting, and changes the "Show first" flow without flagging it. These are
fixable in one revision without changing the recommendation's shape; after it, ACCEPT AFTER EDITS is likely.

Counts: BLOCKING 1, MAJOR 8, MINOR 5.
