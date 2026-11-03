# 0054. Bushwhacking: a grid A* over a corridor window, stitched to the trail graph

Date: 2026-11-02
Status: under review

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
  fenced every build behind `HeapGuard.admitBuild(name, weight)` (trim, wait up to 10 s, refuse with a
  sentence; one build at a time). Anything the grid holds is admitted the same way and sized before it
  is allocated. Already ruled out: a grid over the whole pack.
- **What the packs already hold.** The DEM archive (Terrarium tiles; the Rainier DEM pack is z8 to
  14, 106 MB; `DemPyramid.window(z, x0, y0, w, h)` gives any Web Mercator pixel window as an
  `ElevationGrid`, overzooming from ancestors where the pack is shallower). The vector archive's
  `landcover` layer (z7 to 14; class ice, rock, wood, grass, wetland, sand, farmland; subclass glacier,
  bare_rock, scree, scrub, heath, bog, ...), `water` polygons and `natural_line` cliffs
  (`tilebuild/PROFILE.md`), all decodable in `shared` (`MvtDecoder`; `CliffSides` already reads
  `natural_line`). The graph tiles at z11 (`TiledGraph`, 32 MB LRU) with the per-mode `CostModel`.
- **The cost unit.** `CostModel` prices an edge in equivalent flat metres (`length x weights +
  slopePenalty`), never under its length, so the bidirectional A* heuristic (`minWeight x straight
  distance`) stays admissible; with a `PaceFit` the slope penalty is the fit's extra time over flat
  as flat metres, clamped at zero for the same reason. Any grid cost must keep both properties.
- **The route format** (ADR 0040, Consequences): a mode is a string the T108 rule tolerates (an older
  build shows it as itself, follows it, refuses to edit it); off-trail stretches are leg geometry; a
  `Maneuver` an older build does not know makes `RouteDirections.decode` fail, so its saved directions
  count as absent there; a leg's mode inside a plan is invisible to an older build (the degrade rule
  applies, not the T108 refusal); `LegKind` code 4 (`OFF_TRAIL`) is reserved and would need a path
  version step; `RoutePath` versions 1 and 2 are the ones every build since T89 reads.
- **Format changes and what users see** (ADR 0031, ADR 0033): built in alpha as proposed, listed in
  the report, migrated; the safety wording is the owner's to sign off (the idea file). No other
  product's name in user-visible text. No Wi-Fi gate (ADR 0023).
- **Data sources** carry licence and attribution in `sources.json` before use; `provenance` on
  results names what contributed.
- **The time model** (T164): hike time is Naismith until the user's tracks fit a Tobler-shaped curve
  (`PaceFits.active`, 5 tracks over 20 km); `TrackMatch` drops points off the network (`offNetwork`)
  today, so no off-trail piece is measured yet.

## Prior art

Two read-only surveys of 2026-11-02 (ADR 0039 and ADR 0044), `out/wave27-research/bushwhack-prior-art.md`
(the apps) and `bushwhack-landcover-and-cost-models.md` (sources and cost functions; marked where a
row rests on the agent's memory rather than a fetched page, so those rows sit under Claims to verify).

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
| Tobler 1993 | `v = 6 exp(-3.5 |tan θ + 0.05|)` km/h; **off-path speed x 0.6** | Wikipedia, Tobler's hiking function; movecost's `mc_cost_functions` |
| Naismith, Langmuir, Aitken | 5 km/h plus 1 h per 600 m; Langmuir's descent terms; Aitken's 0.6 off-road factor | Wikipedia, Naismith's rule |
| GRASS `r.walk` | Langmuir coefficients 0.72, 6.0, 1.9998, -1.9998 on a raster, a friction map multiplies, knight's moves (16 neighbours) | grass.osgeo.org `r.walk` manual (GPL code; the formula is public) |
| ArcGIS Path Distance | Vertical factor tables (Tobler, Naismith forms) with a cut angle beyond which a cell is impassable; a horizontal friction raster; 8 neighbours | Esri help (proprietary tool) |
| Campbell, Cutler, Dennison 2024 (STRIDE) | Older off-path models use one blanket multiplier or a few land-cover multipliers; STRIDE predicts rate from lidar slope, vegetation density and roughness | par.nsf.gov 10542024 |
| Irmischer and Clarke 2018; USFS firefighter travel rates; SAR tables | Measured speed by slope and terrain type | Not retrieved; marked as memory |
| ESA WorldCover 2021 v200 | 10 m, 11 classes (tree cover, shrubland, grassland, cropland, built-up, bare or sparse, snow and ice, permanent water, herbaceous wetland, mangroves, moss and lichen), **CC BY 4.0**, overall accuracy 76.7 %, 3 x 3 degree COGs (2,651 tiles, about 117 GB), anonymous S3 `s3://esa-worldcover/v200/2021/map` | esa-worldcover.org/en/data-access (fetched) |
| Esri / Impact Observatory 10 m LULC | Annual since 2017, 9 classes (rangeland merges grass and scrub), CC BY 4.0, no direct download on the catalogue page (Earth Engine, a STAC) | gee-community-catalog.org (fetched) |
| Google Dynamic World | 10 m per scene, CC BY 4.0, an Earth Engine account; no static global product | memory |
| NLCD (US only), GLAD (30 m), CGLS-LC100 (100 m), CCI (300 m), CORINE and CLC+ (Europe, accounts) | Coarser, regional, or behind accounts | memory |
| OSM `landuse`, `natural`, `leisure` polygons | Already in the packs' `landcover`, `water`, `natural_line` layers; ODbL; patchy where unmapped | `tilebuild/PROFILE.md` |

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
C's first step, not a dead end. Moving parts: the grid search, the portals. Settings: none.

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
route exists, its line is the corridor; when an end does not snap, the straight line is. **Pass 2**
runs A* over a state space with two kinds of node: the graph's nodes (every graph tile the search
reaches, as today, through the 32 MB LRU) and the cells of one grid window, the union of the pass 1
line and the straight line buffered by `W = clamp(0.3 x D, 500 m, 3 km)` (`D` the straight distance),
clipped to the DEM's coverage. Transitions: graph edge to graph edge as today; cell to one of 16
neighbours (8 plus the knight's moves, the GIS norm against the 8-neighbour angle bias) at the
off-trail cost (Part 2); **portals** between the two: every graph edge inside the window is sampled
every cell length and each sample's cell gets a portal `(edge, distance along)`, so a cell steps onto
the trail mid-edge exactly as a route's start snaps today (`EdgeSnap`), paying the leave-or-rejoin
penalty `P`. The heuristic stays the straight distance times `minWeight` (1.0: a trail cell is never
cheaper than its length and an off-trail step never under its length times 1.0), so the result is
optimal for the model. The route stays on trails wherever the grid saves less than `2P` plus its own
extra cost, and a cut that lies outside the window is simply not found (the window is a bound, not a
promise). Pass 1 doubles as the fallback: when pass 2 is refused for size, pass 1's trail route is
returned with a notice.

Memory. The grid is read from the DEM archive at z13 (`DemPyramid.window`; 13.1 m a pixel at
Rainier's latitude, 19.1 m at the equator, 6.5 m at z14 where the pack has it) and holds per cell:
elevation (`Float`, 4 B), class (1 B), `g` (4 B), parent (4 B) and two flag bits: about 14 B, call it
16 B with the open heap. The window's cell count is known before anything is allocated, so
`HeapGuard.admitBuild("off-trail grid", cells x 16)` sizes it:

| Leg (straight D) | W | Window | Cells at z13 | Bytes |
|---|---|---|---|---|
| Paradise to Panorama Point, 2 km | 600 m | 3.2 x 1.2 km | 22 k | 0.4 MB |
| 5 km | 1.5 km | 8 x 3 km | 140 k | 2.2 MB |
| 10 km | 3 km | 16 x 6 km | 560 k | 9 MB |
| 20 km | 3 km | 26 x 6 km | 910 k | 15 MB |
| 30 km | 3 km | 36 x 6 km | 1.26 M | 20 MB |

(Rectangles here; the real window is a buffered line, usually smaller.) Cap: **1.5 million cells**
(24 MB), a constant; past it pass 2 coarsens once to z12 (four times fewer cells, 26 m at Rainier) and
past it again refuses with "Too far for an off-trail route (36 km): add a stop in between" and pass
1's trail route. The lift is the stop (ADR 0040's plan), from the card, while running; the sentence
says so. The search's own frontier is a binary heap of cell indexes (4 B each); 16 neighbours over a
million cells settle in a few seconds on a phone (claim; measured in the build task on the API 35
emulator with the 10 km row above). A grid lives for one plan and is dropped; nothing is cached
across plans (ADR 0025 principle 3 is moot: no cache).

Cost: the grid search (new, about the size of `TileRouter`'s A*), the portals, the window reader,
the pass-1 corridor. Risks: the 16-neighbour path is a polyline of 13 m steps (smoothed by a
string-pulling pass over cells of equal class, line of sight checked against the impassable mask);
a window clipped by the DEM's edge (the Rainier DEM pack ends at its box) gives up where the DEM
does, and the report says so. Who else: `r.walk` and ArcGIS Path Distance for the grid, no router for
the hybrid (the survey found none documented; C's portal design is this project's own). Exit cost:
dropping bushwhack leaves every synced route readable (the mode string, the geometry, directions
that then fail to decode and count as absent); the grid code goes, the graph router is untouched.
Moving parts: the grid search, the portal index, the window reader, the land-cover reader (Part 3),
one `HeapGuard` admission. Settings: Part 6.

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
pace and setting, so it is computed per plan).

### Part 2. The cost model

One off-trail step from cell `a` to neighbour `b`, length `len` (13 m or 13 x sqrt 2 or 13 x sqrt 5),
rise `dz` from the two cells' elevations:

```
cost = len x classFactor(b) x steepFactor(slope) + slopePenalty(len, max(dz, 0), max(-dz, 0))
```

- `slopePenalty` is **the existing `CostModel` hike penalty** (Naismith's 8 m per metre of ascent
  plus the over-25 % and over-15 % extras), or the user's `PaceFit` extra time as flat metres when
  there is one (T164). Not Tobler afresh: the fit already has Tobler's shape with the user's numbers,
  and the trail edges in the same search are priced by it, so a cut and the trail it replaces are
  compared in one currency. (The alternative, Tobler's formula for the grid and `CostModel` for the
  graph, prices the same hill two ways; rejected.)
- `classFactor`: the land-cover factor on the step, never under 1.0, from the table below. With no
  land-cover information for the cell (no archive, nothing mapped) the factor is **1.67**, Tobler's
  and Aitken's off-path 0.6 on speed, the one published number.
- `steepFactor`: 1.0 up to 30 degrees (`Slope.STEEP_DEG`), rising linearly to 4.0 at 40 degrees,
  **impassable from 40 degrees** (the slope between the two cells, and the Horn slope at `b`, both).
  The survey's GIS tools use a cut angle; 40 degrees is where sustained hiking becomes scrambling
  (the SAC T4 to T5 boundary in practice); 30 degrees is the exposure readout's own threshold, so the
  card's "over 30 degrees" and the cost's ramp agree. The owner may move both numbers (Part 6).
- **Impassable cells** (no transition in or out): slope 40 degrees or more; a cell a `natural_line`
  cliff crosses (the pack's vector tiles at z14); a cell inside a `water` polygon (the pack) or
  WorldCover permanent water; WorldCover mangroves; a cell inside `landcover` class `ice` (glacier)
  or WorldCover snow and ice, **by default**. Glaciers and permanent snow are a safety call the
  owner decides (Part 6): impassable keeps a novice off a crevassed glacier, and a mountaineer's
  glacier walk is still a crow line or the editor's straight leg.
- **`P`, the leave-or-rejoin penalty**: 200 equivalent flat metres at each portal step, so a cut is
  taken only when it saves more than 400 m-equivalent plus its own extra cost. It stops the zigzag
  the idea file names and is the one number the setting in Part 6 changes.
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
| Cropland (40), built-up (50), `farmland`, `orchard`, `vineyard` | 3.0 | Usually private; passable only when nothing else goes |
| Herbaceous wetland (90), `wetland`, `bog`, `marsh`, `swamp` | 3.0 | Wet ground; not impassable |
| Permanent water (80), `water` polygons, mangroves (95) | impassable | |
| Snow and ice (70), `ice`, `glacier` | impassable by default | The owner's call |

The pack's vector classes win where both say something (they are mapped by a person; WorldCover is a
classifier), WorldCover fills where the pack has nothing, the DEM alone where neither has anything.
Every plan's report lists the metres per class on each off-trail stretch, so a wrong factor shows.

### Part 3. The land-cover source

#### L1. ESA WorldCover 2021 v200 as a new pack archive (recommended)

CC BY 4.0, 10 m, 11 classes, anonymous S3, 3 x 3 degree COGs. Registry entry first (`kind:
"landcover"`, licence `CC-BY-4.0`, attribution "© ESA WorldCover project 2021 / Contains modified
Copernicus Sentinel data (2021) processed by ESA WorldCover consortium", the S3 URL pattern, the
tile list). Built by the devtool into a third archive role, `PackRole.LANDCOVER("landcover")`:
`<name>-landcover.pmtiles`, PNG tiles of 256 x 256 class bytes (paletted, one byte a pixel, 0 for no
data) **at z13 only** (near the raster's native 10 m at mid latitudes; z14 would quadruple the bytes
to upsample), read through the existing `CachedRangeSource` and `GeoTiff` reader (ranged reads of the
COG's internal tiles, as the Copernicus DEM is read today, so a pack's build fetches only its window
of the 3 x 3 degree cell). The reader today accepts 32-bit samples only and none or LZW compression
with deflate through `extraDecompressors` on the JVM; WorldCover is 8-bit deflate, so the reader
grows an 8-bit sample path: no new dependency. Size: a paletted class tile is a few KB (a 256 x 256
tile of flat patches 1 to 6 KB, a forest and shrub mosaic up to 15 KB, estimates); the Rainier box
(0.5 x 0.3 degree) at z13 is about 84 tiles, under 1 MB (measured in the build task). The phone
samples the archive through the same tile reader as the DEM; a window of the grid takes the tiles
under it, decoded once each.

How older builds see it: the v0.1.14 catalog parser skips a whole entry whose `files` has an unknown
role (ADR 0049, claims), so the archive must not join `files`; it rides in the catalog's optional list
beside `files` (ADR 0049's `derived` list, generalised to "optional files" with the same reader rule:
ignored by builds that do not know it, outside the pack's hash and identity) and as an item of the
area manifest, which bumps the manifest version when present (ADR 0041's rule: version before kind,
an older build refuses the manifest plainly). A pack without the archive still bushwhacks on the DEM
and the vector layers alone (the unknown factor 1.67); the report's `provenance` says which sources
contributed. Dependency checklist (`dependency.md`) for the source: (1) a global classified raster
nobody can copy in a page; (2) CC BY 4.0, compatible; (3) released 2022, static, the consortium
answers through the ESA forum; (4) ESA with VITO and partners; (5) no code closure, data only; (6) not
applicable; (7) a class byte per pixel, nothing to parse beyond the TIFF the reader already guards;
(8) the registry's `updateCadence` ("static; v200 is the last release") and the devtool's build
notes; (9) replace by rebuilding the archive from another raster with the same class map (a day);
(10) the grid falls back to the unknown factor, the report says `landcover: none`.

#### L2. Esri / Impact Observatory annual 10 m LULC

CC BY 4.0, annual, but 9 classes with grass and scrub merged into "rangeland" (the distinction this
model leans on most) and no anonymous download found on the catalogue page. Second choice if a
current-year map ever matters. Exit cost from L1: one class map and a rebuild.

#### L3. The pack's own OSM layers only (no raster)

`landcover`, `water` and `natural_line` from the vector archive: already shipped, already attributed
(ODbL; the cost grid is computed on the phone and never shipped, so no derived-database question
arises), decoded in `shared`. Patchy: scrub, scree and open forest are mapped where someone mapped
them, and nothing marks density. This is C's floor and its first build step (Part 7); L1 is the
improvement over it, not its prerequisite.

#### L4. NLCD (US), GLAD, CGLS-LC100, CCI, CORINE, CLC+

Regional or coarser or behind accounts; the US `NLCD` (public domain, 30 m, a better forest and
shrub split) is a possible US refinement later, not first.

### Part 4. The route format

#### F1. The mode string plus directions-carried stretches (recommended)

The route is one trail-kind leg (`LegKind.TRAIL`) whose geometry is the whole line, trail and
off-trail points alike, as ADR 0040 foresaw; the path's mode is `bushwhack` (`TravelMode.BUSHWHACK`;
the user-visible label is the owner's, Part 6). The off-trail stretches ride in `directions`: two new
maneuvers, `LEAVE_TRAIL("leave-trail")` at the point where the line leaves the network and
`REJOIN_TRAIL("rejoin-trail")` where it returns, each instruction carrying its stretch's metres,
steepest slope, classes and bearing in its text and fields. The phone and the browser draw the points
between a leave and its rejoin dashed in the off-trail style (distinct from the crow dash; drawn
from the instructions' point indexes), and the card lists the stretches from the same instructions.
Older builds: the mode shows as "bushwhack (unknown mode)", followed, not edited (T108); their
`RouteDirections.decode` meets an unknown maneuver and the directions count as absent (ADR 0040), so
they draw the line solid with no off-trail mark. Inside a plan (ADR 0040 `stops`), a bushwhack leg's
mode is invisible to an older build and the degrade rule applies: the older build shows a frozen
route whose line it may edit; the edit makes the key stale on every replica and the plan becomes a
frozen route, geometry kept. That loss is ADR 0040's accepted one; the safety wording cannot lean on
the T108 refusal for legs inside plans, and does not (it sits on the route card of the build that
planned it). No path version step; `LegKind` 4 stays reserved, unused. Exit cost: none for the
format (dropping the maneuvers leaves every path valid).

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
regenerated by the same writer). Rejected as redundant.

### Part 5. The off-trail time model

The time of an off-trail stretch is its pieces' time on the walker's pace (the `PaceFit` when there
is one, Naismith otherwise, piece by piece as `pathTimeS` does) times the stretch's class factors:
the same factors as the cost, so the time the card shows and the choice the router made agree.

From the user's tracks: `TrackMatch` today drops points off the network (`offNetwork`) and keeps only
matched pieces for the fit. The follow-on task keeps the off-network runs as **off-trail pieces**
(200 m pieces as the fit's, grade from the DEM, the land-cover class under the piece from the same
readers) and fits an **off-trail factor**: the piece's measured time over the fit's time at its grade,
the median over the pieces weighted by length and recency as the fit is. One factor overall from
5 km of off-trail pieces replaces the unknown factor 1.67; a per-class factor from 2 km in that class
replaces the class's row. The factors are a synced setting value beside the pace fit (version 1, the
same lifecycle, `GET /pace` shows them, `devtool pace-fit` fits them from a GPX directory); the card
marks the time "your pace off trail" or "an estimate" (`fitted`, as T164's `pace` does). The range
(T184, `PaceRange`) applies unchanged. Until fitted, every off-trail time is labelled an estimate.

### Part 6. The safety wording and the settings

**The mode, not a setting inside hike** (a departure from the survey's recommendation, with its
reasons). The survey asks for a per-route choice with the on-network default; a mode chip is exactly
that (the default chip is hike), and it buys what a setting cannot: the choice rides in the synced
path, so every reader knows which routes carry off-trail stretches (the T108 rule protects them on
older builds; a hike-mode route planned under a setting would be edited on an older build and
re-routed along trails without a word), the safety line sits under the mode picker where the crow
notice already sits, the trip card names the mode, and ADR 0040's coupling analysis already assumes
it. The split the survey found (users who want the router to leave trails and users who never do) is
answered by the chip, not by a toggle hidden in settings.

**One setting** (ADR 0044: the apps and users split on how far to let the router go, and no
practice is clear): **"Off-trail detours"** in Settings › Routes, choices "Short cuts only" (default;
`P` = 600 m-equivalent, class factors x 1.5: the router leaves a trail only for a large saving) and
"Wherever it is quicker" (`P` = 200, the table's factors). Synced key, a row, help text, `POST
/settings`. Why a setting and not a slider over `P`: two named behaviours a user can predict; the
numbers are the project's and move with the calibration.

**Not settings** (ADR 0044 excludes safety rules): the 40 degree cut and the 30 degree ramp, water
and cliffs impassable, the window cap. **The owner decides** (listed again in the Recommendation):
whether glaciers and permanent snow stay impassable or become a third choice of the setting
("... including glaciers"), the two slope numbers, and every string below.

**Strings for the owner's sign-off** (ADR 0031; built as proposed, shown in the report with
screenshots, adjusted afterwards):

- The mode's label on the chips and cards: "bushwhack" (the idea's word) or "off-trail" (plainer; the
  directions say "Leave the trail" either way).
- Under the mode picker (as `CROW_NOTICE`): "Off-trail stretches are estimated from the terrain
  data, not from a known way. Check them against the map and the ground, and turn back when they do
  not match."
- The card, one line per stretch: "Off trail 1.2 km through shrubland and forest, steepest 28°";
  with steep ground: "Off trail 1.2 km, crosses ground over 30° (steepest 34°)".
- The time line: "2:40, of which 0:50 off trail (an estimate)" or "... (your pace off trail)".
- Directions: "Leave the trail here: 1.2 km off trail, bearing 040°, through shrubland" and "Rejoin
  the trail".
- Refusals: "Too far for an off-trail route (36 km): add a stop in between"; "No way across: water,
  cliffs or glacier block every off-trail line here"; "No terrain data here for an off-trail route"
  (no DEM under the window). Each names its lift; none falls back to a straight line in silence
  (pass 1's trail route is returned and named: "Planned along trails: ...").
- The setting's row "Off-trail detours", its two choices and help text: "How far the off-trail
  mode may leave a trail. Short cuts only: a stretch is taken only when it saves a lot. Wherever it
  is quicker: the fastest line over passable ground. Water, cliffs and ground over 40° are never
  crossed."

**Agent surface.** `devtool route --mode bushwhack` (the JSON gains `offTrail`: the stretches with
metres, time, steepest slope, metres per class, cells; `grid`: zoom, window, cells, bytes, settled,
portals, `landcover: worldcover | osm | none`; the PNG draws the window outline and the dashed
stretches); `devtool bushwhack-grid --bbox --from --to` renders the cost grid (impassable in red,
cost shades, the trail portals) as a PNG with a class histogram: the picture the implementer and
the verifier look at; `devtool tiles --landcover --bbox` builds the archive and prints the class
histogram and bytes; on the phone `POST /route?mode=bushwhack`, `GET /route` with `offTrail` and the
card's lines, `GET /route/status` with the grid's memory and the admission; the server's `/route`
the same. Tests: a synthetic valley (a trail around a ridge, a saddle across it) where the cut is
taken under "Wherever it is quicker" and not under "Short cuts only"; a cliff line that blocks the
cut; a lake; a window cap refusal with its sentence; a round trip of a bushwhack route through
`sync-sim` to a reader without the maneuvers (directions absent, line intact); the Rainier goldens
for the grid PNG.

## Recommendation

**C with F1, L3 then L1, the Part 2 model and Part 5's fitted factors, as a mode with one setting.**
Why, by the constraints: the heap is bounded by a cell count known before allocation and admitted by
`HeapGuard`, so the finding's failure cannot recur; the search joins the graph rather than replacing
it, so names, directions, levels and the route beyond the window keep working and the cost is one
currency; nothing ships that is not source data (the cost grid is per plan); the format change is two
maneuvers an older build turns into "no directions", no version step; the land-cover raster is CC BY
4.0, anonymous, read by the reader the project has, and optional at every point; the mode keeps the
risk visible on every replica; the time model's guessed numbers have a path to the user's own.

**The first implementation step** (one task, no visible change until its last part): the registry
entry for WorldCover; `PackRole.LANDCOVER`, the archive builder and `devtool tiles --landcover` on
the Rainier box; the grid window reader (DEM at z13, the pack's vector layers, the archive when
present), the cost model with its table, the impassable mask, and `devtool bushwhack-grid` with its
PNG and the Rainier golden; the hybrid search behind `TravelMode.BUSHWHACK` in `devtool route` and the
server, with the synthetic tests and the 10 km memory and time measurement on the emulator. The
second task: the phone (the chip, the notice, the card, the dashed stretches, the setting, the
routes, the corridor manifest item), the strings shown in its report. The third: the off-trail pieces
and the fitted factors. Each task's report carries the measurements the Claims below ask for.

## What would change this recommendation

- The 10 km row measures over 10 s or over 40 MB on the API 35 emulator: the window shrinks (`W`
  to 2 km), z13 gives way to z12 for legs over 10 km, or A (end caps) ships first.
- The Rainier PNGs show the 16-neighbour line zigzagging after string pulling: Theta* or a finer
  post-pass.
- WorldCover's deflate 8-bit COGs do not read through the existing reader with an 8-bit path (a
  predictor or a tiling the reader lacks): L3 stays the floor and L1 waits for the reader.
- The owner wants the off-trail choice inside hike mode rather than a chip: F1 still holds (the
  stretches ride in the directions); the mode string is then `hike` and the T108 protection is lost,
  which the ADR records.
- Ten users' tracks show the class factors off by more than two: the table is replaced by the fitted
  factors' population medians, not tuned by hand.
- A published, licensed land-cover multiplier table turns up: it replaces the project's rows and is
  cited.

## Claims to verify

- `HeapGuard.admitBuild(name, weight, force, testLimit)` admits any sized build, one at a time, with
  trim and a bounded wait: `androidApp/.../online/HeapGuard.kt` line 330.
- `DemPyramid.window(z, x0, y0, width, height)` returns an `ElevationGrid` (metres, `Float`, NaN
  for no data) at any zoom, overzooming ancestors: `shared/.../tiles/DemPyramid.kt` line 295.
- The Rainier DEM pack is z8 to 14 and 106 MB; z13 is 13.07 m a pixel and z14 6.53 m at 46.85° N
  (`docs/reference/machines.md` line 71; `40075016.686 x cos(lat) / (256 x 2^z)`).
- The vector archive carries `landcover` (class, subclass as listed), `water` and `natural_line`
  cliffs at z13 and 14, decodable in `shared`: `tilebuild/PROFILE.md` lines 60 to 90 and 182;
  `shared/.../mvt/MvtDecoder.kt`; `tiles/CliffSides.kt` (`LAYER = "natural_line"`).
- `CostModel.slopePenalty` is never negative and `minWeight` is 1.0 for hike, so a straight-distance
  heuristic is admissible when every grid step costs at least its length: `routing/Router.kt` lines
  186 to 200; the grid's `classFactor x steepFactor >= 1`.
- `RouteDirections.decode` fails on an unknown maneuver and a failed decode counts as absent;
  `LegKind.of` throws on code 4; `TravelMode.labelOf` shows an unknown mode as itself: ADR 0040
  Consequences; `model/RoutePlan.kt` lines 39 and 303; `routing/Router.kt` line 34. Whether an
  `Instruction` carries the point index the renderer needs: `routing/Directions.kt`.
- The v0.1.14 catalog parser skips an entry with an unknown file role; the manifest parser throws on
  an unknown item kind; `RegionDownload.run` skips an unknown pack role: ADR 0049's confirmed claims;
  `packs/PackCatalog.kt` (`derived`, line 28), `areas/AreaManifest.kt`, `app/online/RegionDownload.kt`.
- The `GeoTiff` reader accepts 32-bit float or signed integer samples only, compression none and LZW
  built in, others through `extraDecompressors`: `shared/.../tiff/GeoTiff.kt` lines 17 and 191 to 193.
  WorldCover v200 map COGs are 8-bit unsigned, deflate-compressed, internally tiled: the product's
  user manual (esa-worldcover.org) and `gdalinfo` on one tile (the critic can fetch the header by a
  ranged read; no account).
- ESA WorldCover 2021 v200: CC BY 4.0, the attribution text, 11 classes and their codes (10 tree
  cover, 20 shrubland, 30 grassland, 40 cropland, 50 built-up, 60 bare or sparse, 70 snow and ice,
  80 permanent water, 90 herbaceous wetland, 95 mangroves, 100 moss and lichen), 76.7 % overall
  accuracy, 3 x 3 degree tiles, `s3://esa-worldcover/v200/2021/map` anonymous:
  esa-worldcover.org/en/data-access (fetched by the survey) and the product user manual (class codes
  from the survey's memory: verify).
- Tobler's off-path factor 0.6 and Aitken's 0.6: Wikipedia (fetched), movecost's documentation.
- The survey rows marked memory (Esri's attribution text and download; Dynamic World, GLAD, NLCD,
  CGLS, CORINE, CLC+ licences; `r.walk`'s constants; Irmischer and Clarke; the SAR and USFS tables):
  none is load-bearing for the recommendation; L1's row is the fetched one.
- The survey's apps rows: the cited help pages (Gaia, CalTopo, OsmAnd, AllTrails fetched; onX and
  Komoot via search summaries, their support pages returned 403).
- `TrackMatch` drops off-network points today (`offNetwork` counted, not kept as pieces):
  `routing/TrackMatch.kt` lines 26 and 71.
- Memory per cell and the table: arithmetic (`(D + 2W) x 2W / 13.07²`); the time to settle a
  million cells with 16 neighbours on the emulator: **not established**, measured in the first task
  (the "What would change" line).
- The paletted class tile sizes and the Rainier archive under 1 MB: estimates, measured in the first
  task.
- No consumer app computes a terrain-aware off-trail route; no OSM router does raster routing: the
  survey (the apps from their help pages; the routers from memory).

## Critic's objections and responses

(none yet)
