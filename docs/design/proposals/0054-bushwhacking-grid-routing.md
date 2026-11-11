# 0054. Bushwhacking: a grid A* over a corridor window, stitched to the trail graph

Date: 2026-11-02
Status: under review (revised on the critic's 22 objections, all taken; the owner decides the strings, the
glacier default and the two slope numbers before the ADR)

The owner, 2026-11-11: the label is **"bushwhack"**; **glaciers are impassable** (the coordinator reads this as the
pack's glacier polygons, as recommended; WorldCover's permanent-snow class without a pack glacier stays a question for
the ADR's author to put to the owner, since it covers snowfields too); the two slope numbers come from the avalanche
slope-angle research, as proposed; a phone without the mode: "no idea", so the coordinator takes the recommendation,
refused. Next: the ADR (`adr` skill).

## Question

The router plans along the trail graph only; a point more than `DEFAULT_SNAP_M` (300 m) from a line
fails with "no trail or road within 300 m of the start point", and the only way off the network is a
crow line (T108: straight, dashed, Naismith over the line, "not a path to walk"). The idea file
(`docs/ideas/2026-10-13-routing-modes-crow-bushwhack.md`) asks for a bushwhack mode: prefer trails,
leave them across the gentlest terrain when that is much shorter, use the DEM for slope and land cover
where known. The owner held it until ADR 0040 was decided (2026-10-23, decided the same day) and
bounded it on 2026-10-29: the cost grid is a window around the corridor, never the whole pack (finding
`docs/findings/2026-10-26-routing-graph-build-runs-out-of-memory-on-the-phone.md`); land cover comes
from a global 10 m raster under an open licence with a `sources.json` entry first; the off-trail time
model comes from the user's own tracks once fitted (T164, `PaceFit`). This proposal decides, together
because each shapes the others: (a) the search and its memory bound (what the grid covers, at what
cell size, how it meets the graph); (b) the cost model (slope, land-cover class, water, cliffs,
glacier, the price of leaving a trail); (c) the land-cover source and how it reaches the phone; (d)
how an off-trail stretch rides in the route format phones sync (ADR 0040: leg geometry, no version
step unless a new `LegKind`); (e) the off-trail time model; (f) the safety wording and the settings.
In ten years the answer should let a phone plan a walk to any point its DEM covers, offline, inside a
bounded heap, say plainly what it does not know, and let the user's own tracks replace every guessed
number in the model.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing
  (`docs/PLANNING.md` › Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL);
  offline first; every feature agent-drivable (devtool command, debug route); agents write all code;
  the design goals (ADR 0025: routing is tiled, not per pack; every cache bounded).
- **The heap.** The phone's whole-pack graph build ran a 512 MB heap out (the finding above); T149
  fenced every build behind `HeapGuard.admitBuild(name, weight)` (trim, wait up to 10 s in 250 ms
  steps, refuse with a sentence; the caller wraps `buildTurn` so one build runs at a time; the limit
  is `spareBytes`, the heap's max minus used minus a reserve of `max(24 MB, max / 8)`, 64 MB on a
  512 MB phone). The guard is only as honest as the weight it is asked for: T149 fitted its weight
  (48 MiB plus 300 B per vertex, measured, times 1.5). Anything the grid holds is admitted the same
  way, with a weight fitted the same way, and sized before it is allocated. Already ruled out: a grid
  over the whole pack.
- **What the packs already hold.** The DEM archive (Terrarium tiles; the Rainier DEM pack is z8 to
  14, 106 MB; `DemPyramid.window(z, x0, y0, w, h)` gives any Web Mercator pixel window as an
  `ElevationGrid`, overzooming from ancestors where the pack is shallower, NaN where nothing covers;
  `covers` is the cheap pre-test). The DEM is bare earth under 3DEP (the US) and a **surface model
  with canopy** under Copernicus (`sources.json`: "includes forest canopy and buildings"), which the
  slope rules below must respect. The vector archive's `landcover` layer (z7 to 14; class ice, rock,
  wood, grass, wetland, sand, farmland; subclass glacier, bare_rock, scree, scrub, heath, bog, ...;
  small polygons dropped and neighbours merged below z13), `water` polygons and `natural_line`
  cliffs (`tilebuild/PROFILE.md`), all decodable in `shared` (`MvtDecoder`; `CliffSides` already
  reads `natural_line`). The graph tiles at z11 (`TiledGraph`, 32 MB LRU) with the per-mode
  `CostModel`. The shared `PngDecoder` reads RGB and RGBA only (colour types 2 and 6).
- **The cost unit.** `CostModel` prices an edge in equivalent flat metres (`length x weights +
  slopePenalty`), never under its length, so the bidirectional A* heuristic (`minWeight x straight
  distance`, `minWeight` 1.0 for hike) stays admissible; with a `PaceFit` the slope penalty is the
  fit's extra time over flat as flat metres, clamped at zero for the same reason. `TileRouter.Search`
  holds 42 B per node (`SEARCH_BYTES_PER_NODE`: two distances, parent, via, closed, potential) plus
  two heaps of 12 B an entry with lazy deletion. Any grid cost must keep both properties; any grid
  state is counted at the search's real size.
- **The route format** (ADR 0040, Consequences): a mode is a string the T108 rule tolerates (an older
  build shows it as itself, follows it, refuses to edit it); off-trail stretches are leg geometry; a
  `Maneuver` an older build does not know makes `RouteDirections.decode` fail, so its saved directions
  count as absent there; `directions` is never required for correctness (absent or stale, every build
  regenerates it from the graph with `Directions.of`); a leg's mode inside a plan is invisible to an
  older build (the degrade rule applies, not the T108 refusal); `LegKind` code 4 (`OFF_TRAIL`) is
  reserved and would need a path version step; `RoutePath` versions 1 and 2 are the ones every build
  since T89 reads. An `Instruction` carries `alongM`, `lat`, `lon`, `bearingDeg`, `stretchM` and a
  text, not a point index; the phone (`TripOverlay`) and the browser (`trips.js`) draw dashes by
  `LegKind` today.
- **The pack format** (ADR 0049): `files` is the pack's identity and hash (`PackHash.combine` over
  `PackRole.entries`; `CatalogStatus.of` keys on `contentHash` over `files`); `derived` is a list of
  files **made from the archives**, matched by fingerprint keys, outside the hash; the graph is never
  a `PackRole`. The area manifest's `FileItem.parse` accepts any role string, `RegionDownload` skips a
  `pack` item whose role it does not know, and `ManifestItem.parse` throws on an unknown item kind
  (so a new kind forces a version bump and refuses the manifest on every build to date).
- **Format changes and what users see** (ADR 0031, ADR 0033): built in alpha as proposed, listed in
  the report, migrated; the safety wording is the owner's to sign off (the idea file). No other
  product's name in user-visible text. No Wi-Fi gate (ADR 0023).
- **Data sources** carry licence and attribution in `sources.json` before use (its header comment
  lists the allowed `kind` values); `provenance` on results names what contributed.
- **The time model** (T164): hike time is Naismith until the user's tracks fit a Tobler-shaped curve
  (`PaceFits.active`, keyed by `TravelMode`, fitted for hike and bike only: `PaceFits.FITTED`; a
  `CostModel(CROW)` gets no fit and shows the trap); `TrackMatch` drops points off the network
  (`offNetwork`) today, so no off-trail piece is measured yet.

## Prior art

Two read-only surveys of 2026-11-02 (ADR 0039 and ADR 0044), `out/wave27-research/bushwhack-prior-art.md`
(the apps) and `bushwhack-landcover-and-cost-models.md` (sources and cost functions; marked where a
row rests on the agent's memory rather than a fetched page, so those rows sit under Claims to verify;
the critic then confirmed the WorldCover row against the bytes of two COG headers).

**The apps.** No consumer app found computes a terrain-aware off-trail route. Every one offers a
snap-to-network drawing mode with a manual straight-line escape, per route or per segment, and leaves
terrain judgement to the user with slope-shading layers viewed afterwards:

| App | Off trail | Default, drawing, wording | Source |
|---|---|---|---|
| Gaia GPS | Snap to Trail / Snap to Road / Straight Line; a click just off a trail makes a short straight connector | Straight line "in areas without trails"; no safety text | help.gaiagps.com articles 115003523607, 115003526167 |
| CalTopo | Snap to None / OSM / USFS; off the network the straight-line tool returns ("cross-country travel within the same route"); Terrain Statistics give slope angle and aspect; slope-angle shading (red 35 to 45 degrees) | Snapping shipped off (2017); no safety text | blog.caltopo.com/?p=703 |
| onX Backcountry | Snap To versus Point Draw, mixed per stretch; distance and elevation either way | Snap To usual; no terrain cost | onxmaps.com/backcountry/app/features/route-builder |
| Komoot | "Follow ways" off makes an "off-grid" segment, drawn dotted, no turn directions on it | Follow ways on by default | support.komoot.com article 360024733651 (via search) |
| Locus Map | "Draw manually" profile: a straight line ("off-road and off-path, e.g. crossing a stream without a bridge"); a beeline "Guide on" | Walking profile on first use | docs.locusmap.app route planning |
| OsmAnd | "Straight line" routing (the aircraft profile) and direct-to-point; the docs say it ignores mountains, rivers and forest | Off by default; recalculation distance 500 m | osmand.net/docs/.../straight-line-routing |
| AllTrails, Trailforks, PeakVisor, Footpath, HiiKER | A snap toggle; off is straight lines between clicks | Snap on | support.alltrails.com 37270479773204 and the apps' help pages |
| Mapy.com | Snaps only; via points as the workaround | Snap only | community.mapy.com/post/6655 |
| FATMAP (Strava) | Snap with a "direct" switch per section; gradient, aspect and avalanche layers after planning | Snap default | thegreatoutdoorsmag.com FATMAP review |
| Garmin (BaseCamp, Explore, handhelds) | A "Direct" profile: straight lines, trails ignored; handhelds cap direct routes near 250 points | Per route; forum sources only | forums.garmin.com threads 409740, 282178 |
| Suunto, Avenza, Outdooractive, Alpenvereinaktiv, Organic Maps, Ride with GPS, Strava | Not established from a public source; no claim | | |

Common practice: snap to the network with a manual straight-line escape, default on-network, the
router never deciding to leave a trail, no safety text anywhere, the only distinct drawing Komoot's
dotted off-grid segment; failure is a silent straight line (Gaia, Garmin). Outliers: OsmAnd and Garmin
make the straight line a whole-route profile. The survey recommends a per-route choice with the
on-network default, the router's own stretches drawn and labelled distinctly, figures (off-trail
distance and share, steepest slope, time marked an estimate), a window and a plain refusal, never a
silent straight line. This proposal takes all of that and departs in one place: the router, not the
user, picks the stretch (the point of the idea), so the wording and the figures carry more weight than
in any surveyed app.

**Cost functions and sources** (the GIS norm; no open-source OSM router does any of it: BRouter,
GraphHopper, Valhalla, OsmAnd and routino route on ways only):

| Source | What it gives | Where |
|---|---|---|
| Tobler 1993 | `v = 6 exp(-3.5 |tan θ + 0.05|)` km/h; **off-path speed x 0.6** (3/5) | Wikipedia, Tobler's hiking function (confirmed by the critic, cited to Tobler 1993); movecost's `mc_cost_functions` |
| Naismith, Langmuir, Aitken | 5 km/h plus 1 h per 600 m; Langmuir's descent terms; Aitken's 0.6 off-road factor | Wikipedia, Naismith's rule |
| GRASS `r.walk` | Langmuir coefficients 0.72, 6.0, 1.9998, -1.9998 on a raster, a friction map multiplies, knight's moves (16 neighbours); the known defect that a knight's move hops a one-cell barrier | grass.osgeo.org `r.walk` manual (GPL code; the formula is public) |
| ArcGIS Path Distance | Vertical factor tables (Tobler, Naismith forms) with a cut angle beyond which a cell is impassable; a horizontal friction raster; 8 neighbours | Esri help (proprietary tool) |
| Campbell, Cutler, Dennison 2024 (STRIDE) | Older off-path models use one blanket multiplier or a few land-cover multipliers; STRIDE predicts rate from lidar slope, vegetation density and roughness | par.nsf.gov 10542024 |
| Irmischer and Clarke 2018; USFS firefighter travel rates; SAR tables | Measured speed by slope and terrain type | Not retrieved; marked as memory |
| ESA WorldCover 2021 v200 | 10 m, 11 classes (10 tree cover, 20 shrubland, 30 grassland, 40 cropland, 50 built-up, 60 bare or sparse, 70 snow and ice, 80 permanent water, 90 herbaceous wetland, 95 mangroves, 100 moss and lichen), **CC BY 4.0**, overall accuracy 76.7 % (74.4 % for 2020), 3 x 3 degree COGs (2,651 tiles, about 117 GB; 36,000 x 36,000 px, 8-bit unsigned, Adobe Deflate, predictor 1, palette photometric, 1,024 px internal tiles, nodata 0, the legend and "CC-BY 4.0" with the attribution string in `GDAL_METADATA`; 51 to 58 MB a tile along 45° N), anonymous S3 `s3://esa-worldcover/v200/2021/map` (GET 206 and bucket listing 200 without credentials) | esa-worldcover.org/en/data-access (fetched); two COG headers read by the critic |
| Esri / Impact Observatory 10 m LULC | Annual since 2017, 9 classes (rangeland merges grass and scrub), CC BY 4.0, no direct download on the catalogue page (Earth Engine, a STAC) | gee-community-catalog.org (fetched) |
| Google Dynamic World | 10 m per scene, CC BY 4.0, an Earth Engine account; no static global product | memory |
| NLCD (US only), GLAD (30 m), CGLS-LC100 (100 m), CCI (300 m), CORINE and CLC+ (Europe, accounts) | Coarser, regional, or behind accounts | memory |
| OSM `landuse`, `natural`, `leisure` polygons | Already in the packs' `landcover`, `water`, `natural_line` layers; ODbL; patchy where unmapped; generalised polygons at map scale | `tilebuild/PROFILE.md` |

Common practice: Tobler or Naismith for slope times a land-cover friction raster, a slope cut for
cliffs, 10 or 30 m cells, 8 or 16 neighbours; WorldCover is the open 10 m baseline. **No published
table of land-cover multipliers was found**: the factors below are the project's own, to be
calibrated from tracks, and are named as such.

## Options

Six parts, each with its options; the recommendation picks one per part.

### Part 1. The search and its window

#### A. End caps: a grid from each end to the nearest trails, the graph between

A grid search runs only in a disc around each end (radius the snap distance, say 2 km) to a set of
trail portals; the graph A* joins the portals. Meets the heap bound trivially (two small discs);
reuses the router unchanged between the portals. Does not do what the idea asks: it never cuts across
between two trails mid-route, so a 12 km trail horseshoe with a 1 km saddle between its arms stays
12 km. Cost: small. Exit cost: the disc search is the grid search of C with a smaller window, so A is
C's first milestone (the first task's first PNG), not a dead end. Moving parts: the grid search, the
portals. Settings: none.

#### B. One grid, trails as cheap cells (no graph in the search)

Rasterise the trail network into the window: a cell a trail crosses costs the trail's class weight,
every other cell the off-trail cost. One search, one data structure, easy to picture (`r.walk`'s
form). Loses what the graph gives: trail names, the directions, bridges and tunnels (a cell cannot
hold two levels), exact trail geometry (a 13 m cell zigzags a switchback), and the route beyond the
window (the whole route must fit the window, so a 30 km trail walk with one short cut needs a 30 km
grid: the heap bound bites the common case). Exit cost: the whole search is rewritten to join the
graph. Moving parts: one. Settings: none.

#### C. A hybrid search over the graph and a grid window around the corridor (recommended)

Two passes. **Pass 1** plans the leg as a hike (today's bidirectional A*); when both ends snap and a
route exists, its line is the corridor; when an end does not snap within `DEFAULT_SNAP_M`, pass 1
snaps it at `FAR_SEARCH_M` (10 km) so a trail corridor still exists, and only when that fails too is
the straight line the corridor alone. **Pass 2** runs **one search, `TileRouter`'s bidirectional A***,
over a state space with two kinds of node: the graph's nodes (every graph tile the search reaches, as
today, through the 32 MB LRU) and the cells of one grid window, hosted in `TileRouter.Search` as one
more block of ids (a `GraphPiece`-like block with its own potential), so the optimality argument,
the twins, the levels and the stop rule are written once. The window is the union of the pass 1 line
and the straight line buffered by `W = clamp(0.3 x max(D, L1 / 2), 500 m, 3 km)` (`D` the straight
distance, `L1` the pass 1 length, so a long trail horseshoe around a short saddle widens the window
towards the saddle), clipped to the DEM's coverage. Transitions: graph edge to graph edge as today;
cell to one of 16 neighbours (8 plus the knight's moves, the GIS norm against the 8-neighbour angle
bias) at the off-trail cost (Part 2), a knight's move only when the two cells it crosses are passable
and a diagonal only when the barrier mask (Part 2) does not cut the corner; **portals** between the
two: every graph edge inside the window is sampled every cell length and each sample's cell gets a
portal `(edge, distance along)`; a cell relaxes onto the edge's two end nodes as `expandEnd` does for
a route's snapped start (`cost(edge, f, 0) + P` and `cost(edge, f, 1) + P`, a partial edge the
existing `Step(piece, e, f0, f1)` reconstructs) and a node relaxes back along each adjacent edge to
the portals on it. The heuristic stays the straight distance times `minWeight` (1.0: a trail cell is
never cheaper than its length and an off-trail step never under its length), so the result is
optimal for the model. The route stays on trails wherever the grid saves less than `2P` plus its own
extra cost, and a cut that lies outside the window is simply not found (the window is a bound, not a
promise). Pass 1 doubles as the fallback: when pass 2 is refused, pass 1's trail route is returned,
labelled "Planned along trails" with the reason code (Part 6).

Memory. The grid is read from the DEM archive at z13 (`DemPyramid.window`; 13.1 m a pixel at
Rainier's latitude, 19.1 m at the equator; z14 would be 6.5 m at four times the cells, and the
land-cover archive's zoom (Part 3) is z13, so **the grid's zoom is the archive's zoom or coarser**)
and holds per cell its elevation (`Float`), its class byte and the search's own state at the
search's real size, 42 B a node plus the heap entries: **about 50 B a cell**, the figure the first
task fits the way T149 did (the peak under `HeapGuard.watch` over the cell count, times a margin) and
the ADR records. The window's cell count is known before anything is allocated, so
`HeapGuard.admitBuild("off-trail grid", fittedBytes(cells) + graphBytes)` sizes it, the graph tiles
the search touches (today's `memory.graphBytes`) counted in the same admission:

| Leg (straight D) | W | Window | Cells at z13 | Bytes at 50 B |
|---|---|---|---|---|
| Paradise to Panorama Point, 2 km | 600 m | 3.2 x 1.2 km | 22 k | 1.1 MB |
| 5 km | 1.5 km | 8 x 3 km | 140 k | 7 MB |
| 10 km | 3 km | 16 x 6 km | 560 k | 28 MB (rectangle; the buffered lines are smaller) |
| 20 km | 3 km | 26 x 6 km | 910 k | 46 MB, so z12: 230 k, 11 MB |
| 30 km | 3 km | 36 x 6 km | 1.26 M | 63 MB, so z12: 315 k, 16 MB |

**The cap is in bytes, 24 MB** (a constant; about 500 k cells at z13 at the fitted size), not in
cells. Past it pass 2 coarsens once to z12 (four times fewer cells, 26 m at Rainier) and the card's
stretch lines say "(coarse terrain grid)"; past it again it refuses with "Too far for an off-trail
route (36 km): add a stop in between" and pass 1's trail route. The lift is the stop (ADR 0040's
plan), from the card, while running; the sentence says so. A heap refusal (the guard saying no to a
window under the cap because the app is heavy now) is a different failure with its own sentence and
lift (Part 6). The debug lifts: `POST /route/grid?maxBytes=` lowers the cap on a debug build (as
`POST /route/guard?maxBytes=` does for the guard) and `devtool route --grid-bytes` does the same, so
the z12 and refusal paths are tested on the Paradise leg, not on a 36 km one. A window with more than
half its cells NaN (nothing covers them) is refused with the no-DEM sentence; a NaN cell is
impassable; a window clipped at a pack's edge says so on the card with the lift ("download a larger
area"). A grid lives for one plan and is dropped; nothing is cached across plans.

Cost: the grid block in `Search`, the portals, the window reader, the pass-1 corridor, the barrier
mask. Risks: the 16-neighbour path is a polyline of 13 m steps (smoothed by a string-pulling pass
over cells of equal class, line of sight checked against the impassable mask); the pass 1 plus pass
2 cost on a 30 km leg is unmeasured and may push the common long case to z12 always, acceptable only
because the card says so. Who else: `r.walk` and ArcGIS Path Distance for the grid, no router for the
hybrid (the survey found none documented; C's portal design is this project's own). Exit cost:
dropping bushwhack leaves every synced route readable (the mode string, the geometry, directions
that then fail to decode and count as absent); the grid block goes, the graph router is untouched.
Moving parts: the grid block in `Search`, the portal index, the window reader, the barrier mask,
the land-cover reader (Part 3), one `HeapGuard` admission with a fitted weight, two debug routes.
Settings: Part 6.

#### D. Bushwhack on the server only

The phone asks the server (`RemoteRouter` behind `FallbackRouter`) for every bushwhack leg. Lifts the
heap bound off the phone entirely and keeps WorldCover on the server. Fails the offline-first
constraint for exactly the walk that needs it (no trail, often no signal), so it is only ever a
fallback: with C the server runs the same code (it has the DEM and vector archives in `out/`), and
`FallbackRouter` already asks it when an end lies beyond every pack. Kept as C's fallback, not as the
design.

#### E. Manual: the editor's straight legs plus terrain readouts

Build nothing in the router; let the user place via points and get a straight leg (`LegKind.STRAIGHT`)
with its profile and the T195 exposure readout on it. This is exactly the surveyed apps' state.
Cheapest; honest. It is what the app has today minus a label, and the owner asked for the router's
own stretch. Rejected as the design, kept as the floor every failure falls to: a refused pass 2
leaves the user with the trail route and the editor's straight legs, as today.

#### F. Rejected for the record

A grid over the whole pack (the finding; the owner's word). Theta* or any-angle search (not needed
with 16 neighbours and string pulling; reconsider if the zigzag shows in the PNGs). A permanent
cost raster shipped in the pack (a derived cache the heap would hold; the cost depends on the user's
pace and setting, so it is computed per plan). A fresh unidirectional grid search beside
`TileRouter` (the first draft's memory paragraph assumed one at 16 B a cell: two searches, two
optimality arguments, and a weak heuristic against inflated costs; the critic's objection 1).

### Part 2. The cost model

One off-trail step from cell `a` to neighbour `b`, length `len` (13 m, 13 x sqrt 2 or 13 x sqrt 5 at
z13), rise `dz` from the two cells' elevations:

```
cost = len x classFactor(b) x steepFactor(slope) + slopePenalty(len, max(dz, 0), max(-dz, 0))
```

- `slopePenalty` is **the existing `CostModel` hike penalty** (Naismith's 8 m per metre of ascent
  plus the over-25 % and over-15 % extras), or the user's `PaceFit` extra time as flat metres when
  there is one (T164): `CostModel(BUSHWHACK)` **uses the hike fit** (`PaceFits.active[HIKE]`; an
  alias the constructor states, so the CROW trap does not repeat). Not Tobler afresh: the fit already
  has Tobler's shape with the user's numbers, and the trail edges in the same search are priced by
  it, so a cut and the trail it replaces are compared in one currency. (The alternative, Tobler's
  formula for the grid and `CostModel` for the graph, prices the same hill two ways; rejected.)
- `classFactor`: the land-cover factor on the step, never under 1.0, from the table below. With no
  land-cover information for the cell (no archive, nothing mapped) the factor is **1.67**, Tobler's
  and Aitken's off-path 0.6 on speed, the one published number.
- `steepFactor`: 1.0 up to 30 degrees (`Slope.STEEP_DEG`), rising linearly to 4.0 at 40 degrees,
  **impassable from 40 degrees** (the slope between the two cells, and the Horn slope at `b`, both).
  `steepFactor` is a **preference against steep ground beyond what it costs in time**: the time
  (Part 5) does not include it, and the card says so. The two numbers are the project's own choices
  with no source to cite (the survey's SAC remark rests on memory): 30 is the exposure readout's own
  threshold, so the card's "over 30 degrees" and the cost's ramp agree; 35 is the readout's "very
  steep" band and stays a readout figure; 40 is the cut. The owner decides the two (Part 6).
- **The DSM rule.** Under a surface model (Copernicus: canopy in the heights), a 25 m tree line at a
  clearing's edge is a 13 m step of 20 m or more, over 40 degrees, and the cut would wall every forest
  edge outside 3DEP. So the cut is not trusted on a surface model until the first task has measured
  the impassable fraction and the impassable boundary length of a window on a Copernicus-only area
  from `areas.json` (`mont-blanc`) at the same zoom, and the ADR picks a rule from these candidates: the
  cut only where the pack's `natural_line` or `rock`/`bare_rock` agrees under tree cover; a 3-cell
  slope baseline under WorldCover class 10; a per-source `demKind` the registry already implies
  (`notes` on `copernicus`). The report names the DEM source under the window in `grid.dem`.
- **Impassable cells and the barrier mask.** Impassable: slope 40 degrees or more; a cell a
  `natural_line` cliff crosses (the pack's vector tiles at z14); a cell inside a `water` polygon (the
  pack) or WorldCover permanent water; WorldCover mangroves; a cell inside the pack's `landcover`
  class `ice` (OSM `natural=glacier`), **by default**; a NaN cell. Lines and polygon edges are
  rasterised **8-connected** (dilated by one cell across the step direction) so a diagonal cannot
  slip between two corner cells, and a knight's move is allowed only when the two cells it crosses
  are passable (the `r.walk` defect, closed). The synthetic cliff test is a diagonal one-cell line,
  so it can fail.
- **Glaciers and permanent snow** (the owner's call, Part 6; the recommendation with its reasons):
  pack `glacier` polygons impassable by default (crevasses are the safety case; mappers draw them
  where the ice is); WorldCover class 70 **without** a pack glacier passable at factor 2.0 with the
  card line "crosses permanent snow" and the exposure figures, because class 70 is one 2021 pixel for
  a glacier, a permanent snowfield and late-lying snow alike and the high ground above Panorama Point
  is class 70 for kilometres: impassable there would refuse the mode where it is most wanted. The
  third setting level ("... including glaciers") is **built in the first phone task**, so the default
  has its lift from day one. The first task measures the class 70 share of the Rainier box.
- **`P`, the leave-or-rejoin penalty**: at each portal step, so a cut is taken only when it saves
  more than `2P` plus its own extra cost. It stops the zigzag the idea file names and is the one
  number the setting in Part 6 changes (600 m-equivalent under "Short cuts only", 200 under
  "Wherever it is quicker"); the class factors never move with the setting.
- Trail edges in the same search keep today's weights (class, surface, SAC, flags) and penalty.

The class table (the project's own numbers; **no published table exists**; every row is a factor on
time, replaced by the user's own ratio once fitted, Part 5):

| Land cover (WorldCover class; the pack's `landcover` where it says) | Factor | Why |
|---|---|---|
| Unknown (no information) | 1.67 | Tobler's off-path 0.6 |
| Grassland (30), moss and lichen (100), `grass`, `meadow`, `fell`, `tundra` | 1.3 | Open ground, uneven |
| Bare or sparse vegetation (60), `bare_rock`, `sand` | 1.5 | Rock and sand underfoot |
| `scree`, `shingle` | 2.5 | Talus |
| Tree cover (10), `wood`, `forest` | 1.7 | Forest with understorey; the pack cannot tell density |
| Shrubland (20), `scrub`, `heath` | 2.5 | Brush is the slowest common ground |
| Cropland (40), built-up (50), `farmland`, `orchard`, `vineyard`, `landuse` built-up | 3.0 | Usually private; passable only when nothing else goes |
| Herbaceous wetland (90), `wetland`, `bog`, `marsh`, `swamp` | 3.0 | Wet ground; not impassable |
| Snow and ice (70) with no pack glacier | 2.0 | Permanent snow; "crosses permanent snow" on the card |
| Permanent water (80), `water` polygons, mangroves (95) | impassable | |
| `ice`, `glacier` (the pack) | impassable by default | The owner's call; the third setting level lifts it |

**Precedence by specificity**, not by provenance: the pack's `landcover` is OSM polygons generalised
at map scale (a `wood` polygon drawn over a valley covers its clearings and talus), so a 1:50,000
polygon is not finer than a 10 m pixel. Where the pack says what WorldCover cannot (`scree`,
`shingle`, `bare_rock`, `glacier` against snow, `bog`, `marsh`, `swamp`), and for `water`,
`natural_line` and `landuse` built-up, the pack wins; for tree, shrub, grass and bare ground
WorldCover wins where present; the DEM alone where neither has anything. The first task reports the
disagreement rate between the two on the Rainier box as a number for the ADR. Every plan's report
lists the metres per class on each off-trail stretch, so a wrong factor shows.

### Part 3. The land-cover source

#### L1. ESA WorldCover 2021 v200 as an optional third archive (recommended)

CC BY 4.0, 10 m, 11 classes, anonymous S3, 3 x 3 degree COGs (confirmed against the bytes). Registry
entry first (`kind: "landcover"`, added to the header comment's list; licence `CC-BY-4.0`;
attribution "© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021)
processed by ESA WorldCover consortium", the string the COG's own metadata carries; the S3 URL
pattern `.../v200/2021/map/ESA_WorldCover_10m_2021_v200_{cell}_Map.tif` and the bucket's anonymous
listing endpoint, not 2,651 names; `updateCadence` "static; v200 is the last release").

Built by the devtool into `<name>-landcover.pmtiles`: PNG tiles of 256 x 256 class bytes **at z13
only** (near the raster's native 10 m at mid latitudes; z14 would quadruple the bytes to upsample),
each cell the **mode of the WorldCover pixels under it** (not nearest, so a thin class does not
flicker), written as **8-bit greyscale PNG (colour type 0)** with the class code as the value; the
shared `PngDecoder` gains colour types 0 and 3 at bit depth 8 (pure Kotlin, a few lines, with a
test) so the phone, the server and the devtool read the tile through one code path, never the
platform decoder. The COGs are read through the existing `CachedRangeSource` and `GeoTiff` reader
(ranged reads of the 1,024 px internal tiles, as the Copernicus DEM is read today; deflate is
already wired in commonMain, `RemoteTiff.kt`), which today accepts 32-bit samples only, so it gains an
8-bit sample path: a parser change, answered under the checklist below, not a dependency. Size: a
greyscale class tile of flat patches is 1 to 6 KB, a forest and shrub mosaic up to about 15 KB
(estimates); the Rainier box (0.5 x 0.3 degree) at z13 is about 84 tiles, under 1 MB (measured in the
first task). The phone samples the archive through the DEM's tile reader pattern; a window of the
grid takes the tiles under it, decoded once each.

**Where it lives in the pack format** (the critic's objection 5; one coherent shape chosen):
`PackRole` stays two entries, and the archive is **not** in `files` (which is the pack's identity
and hash) and **not** in `derived` (defined as files made from the archives and matched by
fingerprint keys; this one derives from nothing in the pack). The catalog entry gains an **`optional`
list** beside `files` and `derived`: file name, bytes, SHA-256, bounds and zoom, with its own match
rule (the file's bounds cover the pack's and its zoom is the grid's) and a fetch rule: `ensureRegion`
and the pack's install fetch a listed optional file the phone lacks, so a pack that gains land cover
after a phone installed it is **not** `INSTALLED` for ever (the hole ADR 0049's review found for
`derived`). Older builds read the entry (they read only named keys) and ignore the list. Moving
parts named: `PackNames` (a third name pattern through its own parser), `PackManager.delete`, the
downloader's job type, the peer catalog and the import flow, the tile service's reader. The area
manifest item is **`kind: pack` with `role: landcover`**: `FileItem.parse` accepts any role and
`RegionDownload` skips a role it does not know, so every shipped build parses the manifest and the
**version stays at 3**; the first draft's new item kind, which would have forced a version bump and
refused every land-cover area on every build to date, is withdrawn. A pack without the archive still
bushwhacks on the DEM and the vector layers alone (the unknown factor 1.67); the report's
`provenance` says which sources contributed.

Dependency checklist (`dependency.md`) for the source and the parser path: (1) a global classified
raster nobody can copy in a page; (2) CC BY 4.0, compatible; (3) released 2022, static, the
consortium answers through the ESA forum; (4) ESA with VITO and partners; (5) no code closure, data
only; (6) not applicable; (7) **untrusted input**: the new 8-bit path bounds every `TileByteCounts`
against the tile's pixel count, fails a truncated tile and a wrong `BitsPerSample` or `SampleFormat`
plainly, ignores the palette tag, applies predictor 2 on bytes only when the tag says so, and gets
`GeoTiffTest` cases for each; (8) the registry's `updateCadence` and the devtool's build notes; (9)
replace by rebuilding the archive from another raster with the same class map (a day); (10) the grid
falls back to the unknown factor, the report says `landcover: none`.

#### L2. Esri / Impact Observatory annual 10 m LULC

CC BY 4.0, annual, but 9 classes with grass and scrub merged into "rangeland" (the distinction this
model leans on most) and no anonymous download found on the catalogue page. Second choice if a
current-year map ever matters. Exit cost from L1: one class map and a rebuild.

#### L3. The pack's own OSM layers only (no raster)

`landcover`, `water` and `natural_line` from the vector archive: already shipped, already attributed
(ODbL; the cost grid is computed on the phone and never shipped, so no derived-database question
arises), decoded in `shared`. Patchy: scrub, scree and open forest are mapped where someone mapped
them, and nothing marks density. This is C's floor and its first build step (Recommendation); L1 is
the improvement over it, not its prerequisite.

#### L4. NLCD (US), GLAD, CGLS-LC100, CCI, CORINE, CLC+

Regional or coarser or behind accounts; the US `NLCD` (public domain, 30 m, a better forest and
shrub split) is a possible US refinement later, not first.

### Part 4. The route format

#### F1. The mode string plus directions-carried stretches, regenerable from the geometry (recommended)

The route is one trail-kind leg (`LegKind.TRAIL`) whose geometry is the whole line, trail and
off-trail points alike, as ADR 0040 foresaw; the path's mode is `bushwhack` (`TravelMode.BUSHWHACK`;
the user-visible label is the owner's, Part 6). The off-trail stretches ride in `directions`: two new
maneuvers, `LEAVE_TRAIL("leave-trail")` at the point where the line leaves the network and
`REJOIN_TRAIL("rejoin-trail")` where it returns, each instruction carrying its stretch's metres
(`stretchM`), steepest slope, classes and bearing in its text and fields. The phone and the browser
draw the points between a leave and its rejoin dashed in the off-trail style (distinct from the crow
dash), located by **projecting the instructions' `alongM` onto the line** (the T108 quantisation
tolerates decimetres); that is new drawing on both renderers, which draw dashes by `LegKind` today:
`TripOverlay` gains a second dashed layer filtered on an `offtrail` feature property the phone
computes, `trips.js` the same from the route JSON (moving parts).

**The marks must not depend on the field.** `directions` is never required for correctness: absent
(a value over `MAX_CHARS`, a writer that writes no directions, a leg spliced at a via point) or stale,
every build regenerates it with `Directions.of`, which today asks the graph for a junction at each
point and says nothing elsewhere, so a lost field would draw a bushwhack route solid with ordinary
directions on the planning build too, in silence. So `Directions.of` for a `bushwhack` path
**classifies each point by its distance to the nearest graph edge** (`Router.nearest`, a few metres)
and emits `LEAVE_TRAIL` and `REJOIN_TRAIL` at the transitions, the stretch figures recomputed from the
DEM and the land-cover readers: a lost field is rebuilt the way directions are today, and the card's
stretch lines come from the regenerated list when the saved one is absent. Test: a bushwhack fixture
decoded with its `directions` removed draws the same stretches.

Older builds: the mode shows as "bushwhack (unknown mode)", followed, not edited (T108); their
`RouteDirections.decode` meets an unknown maneuver and the directions count as absent (ADR 0040), so
they draw the line solid with no off-trail mark: accepted, and the first `Maneuver` pair since T102
that an older build refuses, so the "one release behind" case is a real fixture (the `sync-sim` round
trip below). Inside a plan (ADR 0040 `stops`), a bushwhack leg's mode is invisible to an older build
and the degrade rule applies: the older build shows a frozen route whose line it may edit; the edit
makes the key stale on every replica and the plan becomes a frozen route, geometry kept. That loss is
ADR 0040's accepted one; the safety wording sits on the planning build's card alone, for routes and
for legs inside plans alike, and the ADR says so. No path version step; `LegKind` 4 stays reserved,
unused. Exit cost: none for the format (dropping the maneuvers leaves every path valid).

#### F2. `LegKind.OFF_TRAIL` legs, the route split at every leave and rejoin

Each off-trail stretch becomes its own leg of kind 4 between new via points. Older builds' `LegKind.of`
throws, the path fails to decode and the route shows as the put's points in one fixed leg: a path
version step (ADR 0040), and the leave and rejoin points become via points the user did not place,
which the editor then treats as theirs. Rejected: F1 marks the same stretches without either cost.
Kept open: if the editor ever needs to pin a stretch (drag the rejoin point), F2's kind is the
reserved code 4 and rides with the next version step.

#### F3. A keyed side field (`offtrail`) beside the path

ADR 0040's `stops` pattern: a field keyed to the path value listing the stretches. Works, but
`directions` is already that field for exactly this content (keyed, dropped alike with the path,
regenerated by the same writer, and now regenerable from the geometry). Rejected as redundant.

### Part 5. The off-trail time model

The time of an off-trail stretch is its pieces' time on the walker's pace (the hike `PaceFit` when
there is one, Naismith otherwise, piece by piece as `pathTimeS` does) times the stretch's class
factors: the same factors as the cost under **both** settings (the setting moves `P`, never a factor,
so the same kilometre never shows two times), without `steepFactor` (a preference, not a time). The
card says which the time rests on.

From the user's tracks: `TrackMatch` today drops points off the network (`offNetwork`) and keeps only
matched pieces for the fit. The follow-on task keeps the off-network runs as **off-trail pieces**
(200 m pieces as the fit's, grade from the DEM, the land-cover class under the piece from the same
readers) and fits an **off-trail factor**: the piece's measured time over the fit's time at its grade,
the median over the pieces weighted by length and recency as the fit is. One factor overall from
5 km of off-trail pieces replaces the unknown factor 1.67; a per-class factor from 2 km in that class
replaces the class's row. The factors are **their own synced setting value**, `offTrailFactors`
version 1, beside the pace fit (not appended to the hike fit's value, so each has its own lifecycle;
older decoders ignore the key; one test that an older-format fit value still decodes beside it);
`GET /pace` shows them, `devtool pace-fit` fits them from a GPX directory; the card marks the time
"your pace off trail" or "an estimate" (`fitted`, as T164's `pace` does). The range (T184,
`PaceRange`) applies unchanged. Until fitted, every off-trail time is labelled an estimate.

### Part 6. The safety wording and the settings

**The mode, not a setting inside hike** (a departure from the survey's recommendation, with its
reasons and its costs). The survey asks for a per-route choice with the on-network default; a mode
chip is exactly that (the default chip is hike), and it buys what a setting cannot: the choice rides
in the synced path, so every reader knows which routes carry off-trail stretches (the T108 rule
protects them on older builds; a hike-mode route planned under a setting would be edited on an older
build and re-routed along trails without a word), the safety line sits under the mode picker where
the crow notice already sits, the trip card names the mode, and ADR 0040's coupling analysis already
assumes it. What it costs and does not buy: a chip plus a two-level setting is the survey's
three-way setting split over two surfaces; the T108 protection covers routes and not legs inside
plans (ADR 0040); a fifth chip at font scale 2.0 is the layout T212 is fixing now; every `when
(mode)` grows an arm. The one question for the owner that decides between them: should a bushwhack
route edited on a phone that does not know the mode be **refused** (the mode) or **re-routed along
trails** (the setting)? This proposal says refused.

**One setting** (ADR 0044: the apps and users split on how far to let the router go, and no
practice is clear): **"Off-trail detours"** in Settings › Routes, choices "Short cuts only" (default;
`P` = 600 m-equivalent: the router leaves a trail only for a large saving), "Wherever it is quicker"
(`P` = 200) and, if the owner takes the glacier recommendation, "Wherever it is quicker, including
glaciers" (`P` = 200, pack glaciers at factor 3.0 instead of impassable). The setting moves `P` and
the glacier rule only; **never the class factors**, so the displayed time is the same under every
level. Synced key, a row, help text, `POST /settings`. Why a setting and not a slider over `P`: named
behaviours a user can predict; the numbers are the project's and move with the calibration.

**Not settings** (ADR 0044 excludes safety rules): the 40 degree cut and the 30 degree ramp, water
and cliffs impassable, the window's `W`. The cap (24 MB) and `W` are memory constants, not safety
rules, and have the debug lifts of Part 1. **The owner decides** (listed again in the Recommendation):
the glacier default and its third level, the two slope numbers, the refused-or-re-routed question
above, and every string below.

**Strings for the owner's sign-off** (ADR 0031; built as proposed, shown in the report with
screenshots, adjusted afterwards):

- The mode's label on the chips and cards: "bushwhack" (the idea's word) or "off-trail" (plainer; the
  directions say "Leave the trail" either way).
- Under the mode picker (as `CROW_NOTICE`): "Off-trail stretches are estimated from the terrain
  data, not from a known way. Check them against the map and the ground, and turn back when they do
  not match."
- The card, one line per stretch: "Off trail 1.2 km through shrubland and forest, steepest 28°";
  with steep ground: "Off trail 1.2 km, crosses ground over 30° (steepest 34°)"; over permanent
  snow: "Off trail 1.2 km, crosses permanent snow, steepest 22°"; on a coarse grid the suffix
  "(coarse terrain grid)".
- The time line: "2:40, of which 0:50 off trail (an estimate)" or "... (your pace off trail)".
- Directions: "Leave the trail here: 1.2 km off trail, bearing 040°, through shrubland" and "Rejoin
  the trail".
- Refusals, each with its own code and lift, none falling back to a straight line in silence (pass
  1's trail route is returned and named: "Planned along trails: <the reason>"): the cap, "Too far
  for an off-trail route (36 km): add a stop in between"; the heap, "Not enough memory for an
  off-trail route right now (needs 28 MB, 12 MB free): try again in a moment" (the admission in `GET
  /route/status`; `POST /route?mode=bushwhack&force=1` on a debug build, documented in
  `android-debug-routes.md`); the mask, "No way across: water, cliffs or glacier block every off-trail
  line here; a straight leg in the editor is the way to draw one"; no DEM, "No terrain data here for
  an off-trail route: download an area that covers both points"; a clipped window, "The terrain data
  ends inside this route's window: download a larger area".
- The setting's row "Off-trail detours", its choices and help text: "How far the off-trail mode may
  leave a trail. Short cuts only: a stretch is taken only when it saves a lot. Wherever it is
  quicker: the fastest line over passable ground. Water, cliffs and ground over 40° are never
  crossed; glaciers only with the third choice. The time shown is the same whichever you pick."

**Agent surface.** `devtool route --mode bushwhack` (the JSON gains `offTrail`: the stretches with
metres, time, steepest slope, metres per class, cells; `grid`: zoom, window, cells, bytes asked and
admitted, settled, portals, `dem: 3dep | copernicus | ...`, `landcover: worldcover | osm | none`;
`--grid-bytes` lowers the cap; the PNG draws the window outline and the dashed stretches);
`devtool bushwhack-grid --bbox --from --to` renders the cost grid (impassable in red, the barrier
mask, cost shades, the trail portals) as a PNG with a class histogram: the picture the implementer
and the verifier look at; `devtool tiles --landcover --bbox` builds the archive and prints the class
histogram, the pack-versus-WorldCover disagreement rate and bytes; on the phone `POST
/route?mode=bushwhack[&force=1]`, `GET /route` with `offTrail` and the card's lines, `GET
/route/status` with the grid's memory and the admission, `POST /route/grid?maxBytes=`; the server's
`/route` the same. Tests: a synthetic valley (a trail around a ridge, a saddle across it) where the
cut is taken under "Wherever it is quicker" and not under "Short cuts only", and its horseshoe
variant whose saddle lies outside `0.3 x D` of both lines; a diagonal one-cell cliff line that blocks
the cut (and a knight's move over it); a lake; a window cap refusal with its sentence at a lowered
cap; a heap refusal with its sentence (`testLimit`); a bushwhack fixture decoded without its
directions drawing the same stretches; a round trip of a bushwhack route through `sync-sim` to a
reader without the maneuvers (directions absent, line intact); the Rainier goldens for the grid PNG.

## Recommendation

**C with F1, L3 then L1, the Part 2 model and Part 5's fitted factors, as a mode with one setting.**
Why, by the constraints: the heap is bounded by a byte cap on a cell count known before allocation,
admitted by `HeapGuard` with a weight fitted on the emulator the way T149's was, the graph tiles
counted in the same admission, so the finding's failure cannot recur with the guard saying
`admitted`; the search is `TileRouter`'s own, so names, directions, levels, twins and the route
beyond the window keep working and the cost is one currency with one optimality argument; the
barrier mask is closed against the moves that hop it; nothing ships that is not source data (the
cost grid is per plan); the format change is two maneuvers an older build turns into "no
directions", regenerable from the geometry on the builds that know them, no version step; the
land-cover raster is CC BY 4.0, anonymous, confirmed against its bytes, read by the reader the
project has plus an 8-bit path, optional at every point, and lives in an `optional` list the pack
format can reuse; the mode keeps the risk visible on every replica; the time model's guessed numbers
have a path to the user's own and never move with a preference.

**The first implementation step** (one task, no visible change until its last part): the registry
entry for WorldCover; the `optional` list, the archive builder and `devtool tiles --landcover` on
the Rainier box, with the 8-bit TIFF path and the greyscale PNG path and their tests; the grid window
reader (DEM at z13, the pack's vector layers, the archive when present), the cost model with its
table and precedence rule, the barrier mask, and `devtool bushwhack-grid` with its PNG and the Rainier
golden (A's end caps as the first PNG); the hybrid search in `TileRouter.Search` behind
`TravelMode.BUSHWHACK` in `devtool route` and the server, with the synthetic tests, the fitted weight
and the 10 km measurement on the emulator (bytes, seconds, the settled count), the Copernicus-only
measurement on `mont-blanc` for the DSM rule, the Rainier box's disagreement rate and class 70 share.
The second task: the phone (the chip, the notice, the card, the dashed stretches on both renderers,
the setting with its three levels, the routes, the manifest item and the plan's window download),
the strings shown in its report. The third: the off-trail pieces and the fitted factors. Each task's
report carries the measurements the Claims below ask for.

**Hidden coupling the ADR must state** (the critic's Part 5): the plan's offline download becomes
per leg mode: a plan with a bushwhack leg downloads the leg's window (the pass 1 line and the
straight line buffered by `W`) at the DEM's z13, the vector z14 and the land-cover z13, size shown
first (ADR 0023), through the corridor manifest (ADR 0030) gaining the leg's `W` or its own corridor
item; until that lands, an offline replan of a bushwhack leg (ADR 0040) falls to pass 1 with the
sentence naming why, never a window silently clipped to the 1 km corridor. The `optional` list is the
pattern the next optional archive (a search index, contours) will copy. `TravelMode` as the key of
`PaceFits.active` means a mode that borrows another's fit states the alias; a later snowshoe or ski
mode meets the same question. `directions` carries safety marks for the first time; the
regeneration rule is what keeps ADR 0040's "never required" true. The grid's zoom is the archive's
or coarser.

**Predictions for the ADR** (dated, failable): (1) at the first task's merge, the 10 km Rainier row
peaks under 40 MB and plans in under 10 s on the API 35 emulator with the fitted weight within 1.5x
of the measured peak; (2) at the same merge, the Rainier box's pack-versus-WorldCover class
disagreement is under 25 % of cells where both say something, and class 70 is over 10 % of the box;
(3) within two waves of the third task, one real off-trail track matched into pieces fits an overall
factor that differs from 1.67 by more than 0.2.

## What would change this recommendation

- The 10 km row measures over 10 s or over 40 MB on the API 35 emulator: the window shrinks (`W`
  to 2 km), z13 gives way to z12 for legs over 5 km, or A (end caps) ships first.
- The fitted bytes per cell come out far from 50 B: the cap's cell count moves with it, the table is
  rewritten, and over 80 B the grid block is split from `Search` after all.
- The Rainier PNGs show the 16-neighbour line zigzagging after string pulling: Theta* or a finer
  post-pass.
- The Copernicus-only measurement shows no usable DSM rule (every candidate leaves forest edges as
  walls or lets real cliffs through): the 40 degree cut is dropped outside 3DEP and the card says
  "slope from a surface model: cliffs not detected".
- WorldCover's 8-bit deflate COGs do not read through the existing reader with an 8-bit path: L3
  stays the floor and L1 waits for the reader.
- The owner answers "re-routed along trails": the off-trail choice becomes a setting inside hike
  mode; F1 still holds (the stretches ride in the directions and regenerate from the geometry); the
  mode string is then `hike` and the T108 protection is lost, which the ADR records.
- The card at font scale 2.0 cannot hold a fifth chip and two stretch lines: the chips scroll or the
  stretches fold, decided in the phone task against T212's layout figures.
- Ten users' tracks show the class factors off by more than two: the table is replaced by the fitted
  factors' population medians, not tuned by hand.
- A published, licensed land-cover multiplier table turns up: it replaces the project's rows and is
  cited.

## Claims to verify

Verified by the critic (`0054-critic-review.md`, Part 1) unless marked otherwise.

- `HeapGuard.admitBuild(name, weight, force, testLimit)` admits a sized build with trim and a bounded
  wait; `buildTurn` is the caller's semaphore; `spareBytes` reserves `max(24 MB, max / 8)`:
  `androidApp/.../online/HeapGuard.kt` lines 76 to 79, 288 to 336. (Confirmed, with the caveat that
  the guard admits whatever weight it is asked: the weight is fitted in the first task.)
- `DemPyramid.window(z, x0, y0, width, height)` returns an `ElevationGrid` (metres, `Float`, NaN
  where nothing covers) at any zoom, overzooming ancestors; `covers` is the pre-test:
  `shared/.../tiles/DemPyramid.kt` lines 295 to 318, 424. (Confirmed.)
- The Rainier DEM pack is z8 to 14 and 106 MB; z13 is 13.07 m a pixel and z14 6.53 m at 46.85° N,
  19.11 m at the equator (`docs/reference/machines.md` line 71; `40075016.686 x cos(lat) / (256 x 2^z)`).
  (Confirmed.)
- The vector archive carries `landcover` (class, subclass as listed, merged below z13), `water` and
  `natural_line` cliffs at z13 and 14, decodable in `shared`: `tilebuild/PROFILE.md` lines 60 to 90
  and 182; `shared/.../mvt/MvtDecoder.kt`; `tiles/CliffSides.kt`. (Confirmed, every subclass.)
- `CostModel.slopePenalty` is never negative and `minWeight` is 1.0 for hike, so a straight-distance
  heuristic is admissible when every grid step costs at least its length: `routing/Router.kt` lines
  186 to 200; the grid's `classFactor x steepFactor >= 1`. (Confirmed.) `TileRouter.Search` holds
  42 B a node plus two heaps: `Router.kt` lines 701 to 986 and 1125. (Confirmed; the first draft's
  16 B figure withdrawn.)
- `RouteDirections.decode` fails on an unknown maneuver and a failed decode counts as absent;
  `LegKind.of` throws on code 4; `TravelMode.labelOf` shows an unknown mode as itself: ADR 0040;
  `model/RoutePlan.kt` lines 39 and 303; `routing/Router.kt` line 34. (Confirmed.) `Instruction`
  carries `alongM`, `lat`, `lon`, `stretchM`, no point index: `routing/Directions.kt` lines 69 to 84;
  `Directions.of` emits nothing away from graph junctions (lines 400, 459); `TripOverlay.kt` line 257
  and `trips.js` lines 191 to 194 draw dashes by `LegKind`. (Corrected by the critic; F1 revised.)
- The v0.1.14 catalog parser reads named keys and skips an entry with an unknown file role;
  `CatalogStatus.of` keys on `contentHash` over `files`; `DerivedFile` is a file made from the
  archives with fingerprint keys; `FileItem.parse` accepts any role string; `ManifestItem.parse`
  throws on an unknown kind; `RegionDownload.run` skips an unknown pack role: `packs/PackCatalog.kt`
  lines 28 and 274 to 286, `packs/PackDerived.kt` lines 6 to 12, `areas/AreaManifest.kt` lines 29 to
  39 and 284, `app/online/RegionDownload.kt`. (Confirmed; L1's home revised to the `optional` list
  and a `pack` manifest item.)
- The `GeoTiff` reader accepts 32-bit float or signed integer samples only, compression none and LZW
  built in, deflate wired in commonMain through `extraDecompressors` (`RemoteTiff.kt` line 92):
  `shared/.../tiff/GeoTiff.kt` lines 17 and 191 to 193. (Confirmed; deflate better than the draft
  said.) The shared `PngDecoder` reads colour types 2 and 6 only (line 57). (Corrected by the
  critic; the greyscale path added.)
- ESA WorldCover 2021 v200: CC BY 4.0, the attribution text, the 11 class codes, 76.7 % and 74.4 %,
  3 x 3 degree tiles, 2,651 of them, anonymous S3; the COGs 36,000 x 36,000 px, 8-bit unsigned,
  Adobe Deflate (code 8), predictor 1, palette photometric, 1,024 px internal tiles, nodata 0,
  51 to 58 MB a tile along 45° N: esa-worldcover.org/en/data-access and two COG headers
  (`N45W123`, `N45W120`) read by ranged GET without credentials. (Confirmed by the critic.)
- Tobler's off-path factor 3/5: Wikipedia, cited to Tobler 1993. (Confirmed.) Aitken's 0.6: the
  survey (memory).
- The Copernicus DEM is a surface model with canopy: `sources.json`, `copernicus.notes`. (Confirmed.)
- `PaceFits.FITTED` is hike and bike; `PaceFits.active` is keyed by `TravelMode`: `routing/PaceFit.kt`.
  (Confirmed; the alias added.)
- `TrackMatch` drops off-network points today (`offNetwork` counted, not kept as pieces):
  `routing/TrackMatch.kt` lines 26 and 71. (Confirmed.)
- The survey rows marked memory (Esri's attribution text and download; Dynamic World, GLAD, NLCD,
  CGLS, CORINE, CLC+ licences; `r.walk`'s constants; Irmischer and Clarke; the SAR and USFS tables;
  the SAC remark behind the 40 degree figure): none is load-bearing; the 40 and 30 degree numbers
  are the project's own and the owner's to move.
- The survey's apps rows: the cited help pages (Gaia, CalTopo, OsmAnd, AllTrails fetched; onX and
  Komoot via search summaries, their support pages returned 403).
- Memory per cell (50 B) and the time to settle half a million cells with 16 neighbours on the
  emulator: **not established**, measured and fitted in the first task (prediction 1).
- The greyscale class tile sizes, the Rainier archive under 1 MB, the disagreement rate and the
  class 70 share: estimates or unknown, measured in the first task (prediction 2).
- No consumer app computes a terrain-aware off-trail route; no OSM router does raster routing: the
  survey (the apps from their help pages; the routers from memory).

## Critic's objections and responses

From `0054-critic-review.md` (Fable 5.1, 2026-11-02; 0 blocking, 10 serious, 12 minor; the
recommendation's shape upheld, "not promotable as written"). Every objection taken; the body is
corrected in place above.

1. **Serious, two searches at once and a memory bound that fits neither.** Response: one search,
   `TileRouter`'s bidirectional A* hosting the grid as an id block with its own potential, portals as
   partial-edge relaxations to both ends plus `P`; the memory paragraph rewritten at 50 B a cell, the
   cap moved from cells to bytes (24 MB), the weight fitted in the first task as T149's was, the
   graph tiles counted in the same admission; the fresh unidirectional search listed under Rejected.
   Resolution: accepted with change (the critic's preferred design taken whole).
2. **Serious, knight's moves and diagonals hop one-cell barriers.** Response: the barrier mask is
   rasterised 8-connected, a knight's move is allowed only when the two cells it crosses are
   passable, a diagonal only when the mask does not cut the corner; the cliff test is a diagonal
   one-cell line. Resolution: accepted.
3. **Serious, the setting's second level changes the displayed time.** Response: the setting moves
   `P` (and the glacier rule) only; the class factors and the time are the same under every level;
   the help text says so. Resolution: accepted.
4. **Serious, "pack wins" is the wrong rule for generic polygons.** Response: precedence by
   specificity (the pack for `scree`, `shingle`, `bare_rock`, `glacier`, the wetland subclasses,
   `water`, `natural_line`, built-up; WorldCover for tree, shrub, grass and bare where present); the
   disagreement rate measured on the Rainier box for the ADR. Resolution: accepted.
5. **Serious, `derived` the wrong home, a `PackRole` outside `files` incoherent, the manifest bump
   avoidable.** Response: shape (ii): an `optional` list beside `files` with its own match and fetch
   rules (closing ADR 0049's `INSTALLED`-for-ever hole), `PackRole` unchanged, the file named by its
   own parser; the manifest item `kind: pack, role: landcover` at version 3; the moving parts named.
   Resolution: accepted with change.
6. **Serious, the off-trail marks in a field designed to be dropped, unrecoverable.** Response:
   `Directions.of` for a bushwhack path classifies points by distance to the nearest graph edge and
   regenerates the two maneuvers and the stretch figures; a fixture without its directions is the
   test; the ADR states what older builds lose. Resolution: accepted.
7. **Serious, the 40 degree cut on a surface model walls every forest edge outside 3DEP.** Response:
   the DSM rule paragraph: the cut is not trusted on a surface model until the first task measures a
   Copernicus-only window (`mont-blanc`) and the ADR picks a rule from the three candidates;
   `grid.dem` in the report; a "What would change" line if no rule works. Resolution: accepted.
8. **Serious, a heap refusal routed to the cap's sentence with no lift.** Response: two codes and two
   sentences; the heap's names the bytes and "try again in a moment", with the admission in
   `GET /route/status` and `force=1` on debug builds; "Planned along trails" carries the reason.
   Resolution: accepted.
9. **Serious, the plan's offline download is narrower than the window.** Response: the hidden
   coupling paragraph in the Recommendation: a plan with a bushwhack leg downloads the leg's window
   at the three zooms, size first; until then an offline replan falls to pass 1 with the sentence.
   Resolution: accepted.
10. **Serious, glaciers and class 70 impassable by default block the mode where it is most wanted.**
    Response: the critic's split taken as the recommendation to the owner: pack glaciers impassable
    by default, class 70 without a pack glacier passable at 2.0 with "crosses permanent snow", the
    third setting level built in the first phone task, the class 70 share measured. The default stays
    the owner's decision. Resolution: accepted with change (as a recommendation, not a decision).
11. **Minor, no point index; drawing from directions is new on both renderers.** Response: `alongM`
    projected onto the line; `TripOverlay` and `trips.js` named as moving parts. Resolution: accepted.
12. **Minor, the window sized from `D` alone misses the horseshoe.** Response: `W` from
    `max(D, L1 / 2)`; pass 1 snaps at `FAR_SEARCH_M` before falling to the straight line; the
    horseshoe test variant. Resolution: accepted.
13. **Minor, the 40 degree cut rests on memory; three thresholds.** Response: the numbers marked as
    the project's own with nothing cited, the three explained (30 the ramp and the readout, 35 the
    readout's band, 40 the cut), both on the owner's list. Resolution: accepted.
14. **Minor, paletted tiles unreadable by the shared PNG decoder.** Response: 8-bit greyscale tiles
    and `PngDecoder` extended to colour types 0 and 3 with a test; one code path on every platform,
    never the platform decoder. Resolution: accepted.
15. **Minor, NaN cells and the DEM's edge.** Response: a NaN cell is impassable; over half NaN
    refuses with the no-DEM sentence; a clipped window has its own sentence and lift. Resolution:
    accepted.
16. **Minor, cost and time disagree by `steepFactor`.** Response: `steepFactor` is a preference
    against steep ground that the time does not include, said so in Part 2 and Part 5. Resolution:
    accepted (the second of the critic's two readings).
17. **Minor, silent z12 coarsening, no test lift for the cap.** Response: "(coarse terrain grid)" on
    the card; `POST /route/grid?maxBytes=` and `devtool route --grid-bytes`. Resolution: accepted.
18. **Minor, the resampling rule, the registry `kind`, the tile list.** Response: the mode of the
    pixels under a cell; `landcover` added to the header comment's kinds; the URL pattern and the
    listing endpoint. Resolution: accepted.
19. **Minor, the bushwhack model must alias the hike fit; the factors' home.** Response:
    `CostModel(BUSHWHACK)` uses the hike fit, stated; the factors as their own synced key
    `offTrailFactors` version 1 with a decode test beside an older fit value. Resolution: accepted.
20. **Minor, the mode's costs unstated; the owner's one question.** Response: the costs listed in
    Part 6 and in "What would change"; the refused-or-re-routed question put to the owner.
    Resolution: accepted.
21. **Minor, no dated predictions.** Response: three predictions with their check points in the
    Recommendation. Resolution: accepted.
22. **Minor, checklist item 7 under-answers the parser change.** Response: item 7 answered for the
    8-bit path (byte counts bounded, truncation, wrong tags, predictor 2, the palette ignored,
    `GeoTiffTest` cases); the deflate correction recorded in the proposal's favour. Resolution:
    accepted.

Residual risks the critic names and this proposal accepts: the class factors are guesses until
tracks replace them (said plainly on the card); the card at font scale 2.0 with a fifth chip and
stretch lines (the phone task, against T212's figures); the 30 km case may live on z12 (the card says
so). The critic's confidence: high on C with the corrections; moderate on the glacier default (the
owner's); low on any cell size under 50 B.
