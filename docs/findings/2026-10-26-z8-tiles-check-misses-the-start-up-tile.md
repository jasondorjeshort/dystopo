---
date: 2026-10-26
status: resolved (T154)
area: android / device tour / sweep
promoted_to: docs/reference/android-app.md
---

# The device tour's z8 `tiles` check fails on a narrow pack because the one in-pack OSM tile is fetched at app start, before the baseline

## Question

The wave 20 sweep on the second PC (`out/sweep-w20`) failed `device-tour` on region-z8 (`8/46.85/-121.72`)
both runs with `failed=tiles`: 0 DEM and 0 OSM tiles served by the phone's loopback over the view, while the
map was fully drawn (199 contour lines, 18 text layers). Every wave 20 implementer and verifier saw the same
on this PC, on `origin/main` before the wave too; the wave 19 sweep on the first PC passed the view 10 of 10.
Was it an app defect, the check, or this PC's packs?

## What was tried

- The sweep again with a wider DEM pack (`tiles --bbox=-121.95,46.68,-121.45,46.98 --min-zoom 8 --max-zoom 14`,
  106 MB, the outdoors pack's box; the earlier pack covered the `rainier` area's own box at z8 to 13, 8.7 MB):
  `out/sweep-w20b`, device tour 9 of 10 both runs, region-z8 now dem 1, osm 0, contours 5.
- One view on a fresh API 35 AVD with the debug build and both packs, through `tools/android-check.py`, with
  a server (`http://10.0.2.2:8765`) and with `--server none`, then `GET /stats/requests?limit=600` for every
  `/osm/8/` and `/dem/` line with its `pack` field (the agent's extracts are under the scratchpad, not kept).
- `git log 63a7d7dc..8fde29e` over `PackPrecedence.kt`, `TileService.kt`, `OnlineTiles.kt`, `Packs.kt` and
  `tools/android-check.py`.

## Result

- The z8 viewport needs three OSM tiles, `8/41/89`, `8/41/90` and `8/41/91`. Only `8/41/90` (lat 46.07 to
  47.04) overlaps the outdoors pack's box (maxLat 46.98); `PackPrecedence.overlaps` rejects the other two
  on the box test, so `TileService.osmTile` (`androidApp/.../app/TileService.kt:581`) sends them online, and
  the online path counts in `OnlineService`, not in `/health` `tiles.osm.served`.
- The one in-pack tile is requested at app start (about 84 s before the tour's baseline `/stats` with a server,
  7 s before it without) and sits in MapLibre's tile cache; the camera move to the z8 view does not request
  it again, so the view's delta of `tiles.osm.served` is 0. Same shape with and without a server. The check
  (`tools/android-check.py`, `expected_sources` about line 486 and `served > 0` about line 1101) expects a source
  when the view centre is inside the pack's bounds and zoom range; it does not model tiles cached before the
  baseline or a pack that answers only part of the viewport.
- DEM behaves the same way: with the wider pack one z9 DEM tile of the view is in the pack and counted; with
  the narrow pack none was.
- No commit since v0.1.12-alpha touches the OSM routing or the check's expectation (T144's `TileService.kt`
  changes are the watchdog and the reference-counted service; the tooling commit's `android-check.py`
  changes are the direct line and `bothunits`). Not an app defect: the map is drawn, and the behaviour
  (a tile outside every pack comes from the server or the public sources) is the T38 and T41 design.
- The first PC's 143 MB pack pair presumably covers the neighbouring z8 tiles, which is why the view passed
  there; its zoom ranges and box were never recorded (`docs/reference/machines.md` now records this PC's).
- T154 (2026-10-27) fixed the check, not the app. Of the two options above, a baseline taken before the
  first style load credits only the first view after a restart, credits every start-up tile to it whether
  or not it lies in that view, and does nothing under `--no-restart`. So the check now expects a source
  when a tile of the viewport itself overlaps the pack's bounds: the view's box from `GET /attribution`
  `viewBounds`, at the zooms MapLibre Native asks that source for (vector at floor(z), the 256 px DEM one
  zoom deeper, clamped to the source's maxzoom). A source whose delta is 0 then passes when the phone's
  request log (`GET /stats/requests`) shows a pack, not the online fallback, answered one of those
  overlapping tiles before the baseline (`servedBeforeBaseline`, `beforeBaseline`). Each view reports its
  `expectation` per source with the reason and the `packTiles`.
- With the outdoors pack and the wide DEM pack on a fresh API 35 AVD with no server, three runs of the z8
  view passed `tiles`, each with osm "1 of 3 viewport tiles at z8 overlap the pack's bounds", `8/41/90`,
  delta 0 and `servedBeforeBaseline` 1 (the old check fails on exactly that). Negative cases: with both packs
  moved aside and a fresh process, a z11 view with `|req=dem+osm+contours` fails on dem and osm. With the
  packs moved aside and `POST /reload` in the same process, the request log still held 12 pack-answered OSM
  lines and the view failed on all three sources: only tiles over a pack that `/health` lists now count.
  Offline cases are in `tools/test_android_check.py`.
- The first PC's Rainier pack recipe is still unrecorded (waiting on the owner, wave 21's deferred list).
