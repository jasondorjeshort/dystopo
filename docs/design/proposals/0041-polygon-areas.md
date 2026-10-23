# 0041. Polygon areas in the manifest: a drawn region and a distance around a point first

Date: 2026-10-23
Status: promoted to ADR 0041

## Question

The area manifest (ADR 0027, amended by ADR 0030) knows two shapes: a box and a route corridor. The
owner's answer of 2026-10-23 to `docs/ideas/2026-10-13-download-by-region.md` asks for a polygon area
in the manifest, with a region the user draws and a distance around a point built first and the
boundary search (county, state, park) later. The manifest is a format phones read, record, publish
to peers and send to the server, so the shape goes through this proposal (ADR 0033 §4). In ten years
the polygon kind should carry any boundary the project ever offers (a county with islands, a park
with an enclave, a drawn ring, a disc) with no further format change, every reader of the manifest
should still refuse what it cannot read with a sentence instead of dropping it, and the size and the
budget check should come before every download, whatever the shape.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing
  (`docs/PLANNING.md` › Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL);
  offline first; every feature agent-drivable (devtool command, debug route); agents write all code;
  the design goals (ADR 0025, principle 4: an area of interest is a first-class object).
- The format is read in four places: the phone's own records (`files/areas/areas.json`, one
  `area` object per record, read by `AreaOfInterest.parse`), a peer's manifest (`GET /peer/areas/<id>`,
  `AreaManifest.parse`), the server's `POST /area` body (`BuildRoutes`), and the phone's own debug
  routes. Today an unknown `kind` throws in `AreaOfInterest.parse` ("kind must be box or corridor");
  `AreaManifest.parse` parses the `area` object **before** it checks `version`, so every shipped
  build says the kind sentence for a new kind whatever the version, and the version sentence
  ("manifest version N is newer than this app reads") only for a known kind; `Areas.load` drops a
  record it cannot parse with a log line, and the next save (a user action, or `adopt` on a source's
  version change with none) rewrites `areas.json` without it: the area and its pin are gone and its
  tiles become ordinary LRU entries.
- ADR 0033: built in alpha without asking, but user data (pinned areas) migrates, a mismatched version
  refuses with a reason the user can read, and nothing is misread or dropped silently; an upgrade test
  with a fixture in the old format.
- ADR 0030: pinned bytes have their own budget (`pinnedCacheMb`, 2 GB by default), checked before an
  area downloads; the size is shown first, with no Download when the budget would be passed. ADR 0023:
  no Wi-Fi gate; the steer is the visible budget.
- The server caps a tile manifest at `DYSTOPO_AREA_MAX_TILES` (200,000, HTTP 413 naming corridor
  remedies and the variable; the operator lifts it); the phone's own manifest
  (`AreaDownload.localManifest`, used with direct sources or an older server) has no cap and builds
  every `TileSet` in full before anything is counted, so a state-sized shape at z15 allocates millions
  of entries while a size line is computed (the territory of T127's out-of-memory finding).
- T130 builds the automatic route corridor this wave on the same manifest and owns `shared/.../areas/**`
  and `androidApp/.../areas/**`; this proposal assumes only the shapes that exist and lands its code
  in a later wave.
- The boundary step needs what the outdoors profile does not keep: `tilebuild/PROFILE.md` has admin
  lines at levels 2 and 4 only, and `park` polygons without names, merged and clipped per tile, so
  no named polygon can be read back from the tiles.
- No new dependency: a scanline fill and an even-odd point-in-ring test are under a page each
  (`UnitRegions.inside` is one already), so `dependency.md` does not apply. The wording and the
  flows are the owner's (ADR 0031); the proposal names them as positions.

## Prior art

From the benchmark survey of 2026-10-23 (ADR 0039; read-only, public sources; several official help
pages were unreachable, so forums and guides stand in where marked).

| App | Shape and how it is picked | Before the download | Source |
|---|---|---|---|
| Locus Map | "This screen"; "Select area" (a 25 x 25 km default rectangle; drag the corner points, drag a midpoint handle to add a vertex, so "almost any shape"); by country; by track with a buffer typed or on a slider; around a point folder (fixed radius, paid) | A "most detailed zoom" slider with plain labels, or a table of tile count and size per zoom; total tiles and approximate size; a name is required; 10,000 tiles a day for custom online maps; no time | https://docs.locusmap.app/doku.php/manual:user_guide:maps_download |
| Apple Maps | A crop box resized by dragging anchors and pinching, from a place, a pin or the current location | File size before downloading; no zoom choice; no time; no official area cap | https://support.apple.com/en-au/105084 |
| Google Maps | The current screen as a frame, adjusted by pan and zoom | Size; over the limit, "zoom in and save again"; maps expire after 30 days | https://www.expertreviews.co.uk/technology/phones/how-to-use-google-maps-offline ; https://bgr.com/?p=3513655 |
| Gaia GPS | A rectangle whose corners are moved | A zoom (quality) level is chosen; tile caps reported on forums (10,000; figures conflict); no size in the guides | https://andrewskurka.com/assignment-download-maps-in-gaia-gps-for-offline-use/ ; https://expeditionportal.com/forum/threads/gaia-pro-how-to-faq-questions.176013/ |
| onX Backcountry | A fixed-shape box; the map is zoomed to change what it holds (users complain the shape cannot change) | Acreage and file size shrink as you zoom; "the only limitation is the available storage" | https://rokslide.com/forums/threads/onx-downloaded-map-size.392301/latest ; https://www.onxmaps.com/backcountry/blog/how-to-use-offline-maps |
| AllTrails | The map moved under a selection box | A trail count, not bytes; 500 trails per area | https://support.alltrails.com/hc/en-us/articles/37758009767444-Download-custom-areas-for-offline-use |
| Komoot | Named regions; a route download is a fixed strip about 3 km wide; a recording keeps a 5.5 x 5.5 km square around the user | Not established (help page unreachable); Wi-Fi only recommended | https://www.dcrainmaker.com/2026/01/komoots-offline-routing.html |
| OsmAnd | Named regions (country, then sub-regions; tap a region on the map) | Size per region in the list | https://docs.osmand.net/docs/user/start-with/download-maps |
| Mapy.cz | Named countries and territorial units | Size after selecting; Wi-Fi recommended in the help text | https://napoveda.seznam.cz/en/offline-map-layers-mapy.cz-app |
| Garmin Explore | A bounding box over the map | Not established | https://forums.garmin.com/apps-software/mobile-apps-web/f/garmin-explore-ios/420958/downloading-regional-maps |
| CalTopo | Mobile polygon not established; desktop picks tiles by click | Not established | https://blog.caltopo.com/?p=676 |
| Organic Maps | Named regions (a country split into regions), sizes in the list | Size per region | https://organicmaps.app/ (critic's pass) |
| Guru Maps | Vector maps by country or by browsing the map, downloaded areas highlighted | Not established | https://gurumaps.app/ (critic's pass) |
| AlpineQuest | A "store area" tool: an area and chosen zoom levels | Warns about the provider's server limits | https://alpinequest.net/ (critic's pass) |
| Backcountry Navigator XE | Blocks picked on a grid (a third model: neither a box nor a drawing) | Not established | the app's public pages (critic's pass) |
| Avenza Maps | Whole maps bought or imported; no area selection | Not applicable | https://www.avenzamaps.com/ (critic's pass) |
| Maps.me, Topo GPS | Not established from a public source | | |

Common practice: a rectangle or crop box over the current map, moved by pan and pinch or by dragging
corners; named regions second. Route corridors are the norm for route-bound downloads; only Locus
documents a typed or slider buffer. A radius around a point is rare (Locus, fixed, paid); no app
offers a plain "distance from here". A free-form polygon is rare (Locus, by midpoint handles); nobody
documents a lasso. A size in bytes before the download is standard; a time estimate was found
nowhere. The zoom range is exposed only by the technical apps (Locus's slider and table, Gaia's
quality level); consumer apps hide it. Hard limits are tile counts (Gaia, Locus), a trail count
(AllTrails) or an undocumented area (Google, Apple); Google's "zoom in and save again" is the only
documented over-limit wording. Outliers: onX's fixed shape is a known irritation; Locus is the
outlier in depth and gives no reason for it. Nothing here needs another product's name in the app's
text; the Wi-Fi recommendations of three apps are not copied (ADR 0023). Caveats: the AllTrails,
Gaia, Komoot and onX help pages answer 403 to automated fetches (bot-blocked, not down), so forums
and guides stand in for them; Apple's anchors-and-pinching detail is the Tom's Guide article's
(https://www.tomsguide.com/how-to/how-to-download-offline-apple-maps-on-iphone), not the support
page's, which says only "adjust the area"; Komoot's official offline page (support.komoot.com,
article 10356476920986) names routes or regional maps and recommends Wi-Fi, while the 3 km strip is
DC Rainmaker's measurement.

## Options

Three parts: how the polygon enters the format (A to D), what a polygon area downloads (E, F), and
how the distance around a point is represented (G, H). The flows are the owner's positions, below.

### A. A `polygon` kind, manifest version unchanged

`{"kind": "polygon", "rings": [[[lon, lat], ...], ...], "bufferM": 0, "minZoom": 8, "maxZoom": 15,
"name": "..."}`: rings in GeoJSON order, the first the outer ring, later ones holes or further parts,
inside decided by the even-odd rule (so a county with islands and a park with an enclave need no
multipolygon type; rings must not overlap, which the writer guarantees, since a reader cannot tell
a hole from an overlap); `bufferM` optional, default 0 (the boundary step wants "the park and 5 km
around it"). The vertex count is the writer's budget, not a reader's rule: the drawing flow and the
boundary step simplify to about 2,000 vertices over all rings with `Polyline.simplify` and add the
tolerance to `bufferM`, as corridors do, so the cover never cuts inside the true boundary; the reader
accepts any count and the server's 2 MiB body is the only hard limit. No ring crosses the
antimeridian (as for a box). The id canonical form is `polygon:<buffer>:<rings>:<zooms>`, rounded
as today. `TileCover.of` gains the case: a tile is in the cover when its square is within `bufferM` of
some ring edge (the corridor code on the closed ring; distance 0 when the edge crosses the square)
or its centre is inside by even-odd, filled by a scanline per tile row so the result comes out as the
manifest's `[y, x0, x1]` rows directly. `AreaPins.cover`, the eviction rule, the version pins and
the margin need no change. The server's `POST /area` defaults a polygon to tiles (`pack=0`) as for a
corridor; `pack=1` on a polygon is refused with "a pack is a box; download a box for the trail
graph" (a pack of the bounding box labelled polygon would be C moved to the server). The manifest's
`version` stays 1. Older builds: the server answers 400 "kind must be box or corridor", which
today's phone does not fall back from (only 404 and 405 do), so the plan fails; a peer's phone fails
the manifest with the same sentence (a toast on the Share screen); a phone downgraded to v0.1.10
drops the record at load and loses it at the next save, with only a log line. Cheapest; it is the
precedent `versions` set (an optional field older readers ignore), but a new kind is not an
optional field: ADR 0033 §3 is not met on the downgrade path.
Exit cost: the rings and the fill are self-contained (one `when` branch in three files); removing the
kind means migrating polygon records to their bounding boxes.
Moving parts: a point-in-ring and a scanline fill in `shared/.../areas/`; a `Polygon` outline in the
Offline areas sheet (a GeoJSON fill, simpler than `CorridorOutline`); the server's parse.

### B. A `polygon` kind, and a manifest that carries one is version 2

As A, plus three reader rules that buy nothing for the builds already shipped (they parse `area`
before `version`, so they say the kind sentence either way) and everything for the next change:

1. **Version first.** A writer writes the lowest `version` that holds the content (box and corridor
   manifests stay 1, a polygon manifest is 2), and the new build's `AreaManifest.parse` and
   `Areas.Record.parse` check `version`, then `kind`, then the fields, so from this build on a newer
   manifest is refused by its version. Both refusals (version and kind) reach the user in ADR 0033's
   wording where they show, the Share screen's toast and the area card: "this phone runs an older
   Dystopo; update it to use this area", with the raw sentence under it.
2. **Nothing readable is lost.** A record the new build cannot parse is kept opaque: its raw JSON is
   written back unchanged, its `bounds` and `zooms` (which every record carries) give a conservative
   box pin so its tiles stay kept, and its card says it needs a newer app.
3. **The downgrade from the first polygon build is covered too**, not recorded as a limit: records
   that a version-1 reader cannot parse go to a sibling file (`files/areas/areas2.json`) and
   `areas.json` keeps only what v0.1.10 reads whole, so an old build's save loses nothing and the
   upgrade reads both files. Ten lines in `Areas.load` and `changed`, retired once every build in
   the field has rule 2. Chosen over accepting the loss because `adopt` can trigger the losing save
   with no user action and ADR 0033 §3 forbids a silent drop; the cost is two files for one release.

`GET /area` on the server reports `manifestVersion` 2 and a `shapes` list; the phone treats a
missing `shapes` (every server today) as box and corridor, and a 400 whose message starts "kind
must be" falls back to the phone's own manifest as 404 and 405 do, so an older server costs nothing
but the outdoors pack it could not have built anyway.
Exit cost: as A, plus the version-by-content rule, which costs nothing to keep; the sibling file is
retired by folding it back.
Moving parts: as A, plus the parse order and the opaque-record path in `Areas.load`, the sibling
file, and their tests (a fixture from v0.1.10 read unchanged; a version-3 manifest getting the
version sentence; a record with an unknown kind kept, pinned by its bounds, and written back).

### C. A polygon that degrades to its box

`{"kind": "box", "bbox": [...], "ring": [[lon, lat], ...]}`: older builds read the box and ignore
the ring, so nothing is refused or dropped; a downgraded phone pins the box (more than was fetched,
nothing lost) and a peer fetches the explicit tile rows of the manifest, which were computed from
the ring. New builds read the ring when present. The price is a format that lies about its kind for
ten years, an id computed from the box on an old build and from the ring on a new one (the record's
id is read from the file, so no re-keying, but a re-download on the old build makes a second
record), a `ring` that cannot grow into holes or parts without the same trick again, and the
server's `POST /area`, which would build the pack of the box and call it the polygon.
Exit cost: low in code, high in meaning: every later reader must know the hint.
Moving parts: as A, minus the version handling.

### D. One shape: everything becomes a polygon

A box as a four-vertex ring, a corridor as its outline ring, the old kinds rewritten. Rejected:
every existing area's id changes (ids hash the geometry and its kind), so every pinned area on every
phone is re-keyed and peers that have not updated name different areas; a corridor's buffer is a
parameter of the line, not of an outline, and T130 gives every trip one this wave. For the record.

### E. A polygon area is pinned tiles

The corridor mechanism: the DEM and vector tiles of the cover (DEM from z6, one tile of margin)
fetched through the online fetcher and pinned, from a server, a peer or the public sources with no
server. The size is known before anything starts (`AreaDownload.plan` extends from corridors to
polygons; the server's `POST /area` with `pack=0` answers at once), the pinned budget is checked
first, a second run resumes, Update and version pins work unchanged. What it lacks is what every
tile area lacks: no trail graph and no search index (ADR 0027's consequence; the manifest says so),
until graph tiles become a manifest kind, ADR 0030's supersede trigger, which fixes corridors and
polygons at once.
Exit cost: none beyond A to C; it is the existing mechanism with one more shape.
Moving parts: a memory guard on the phone's own manifest, since a polygon over a state is now one
tap: before any `TileSet` is filled, the bounding box's tile count over the zoom range (O(1) per
zoom) is checked against a limit derived from the heap (a fraction of `Runtime.maxMemory` over the
set's bytes per entry), and a shape over it is refused with "This area is too large at this detail;
make it smaller or choose less detail" (less detail cuts the count sixteenfold and more). The pinned
budget stays the steer in bytes (a tile cap at 160 kB a tile is 25 GB, which the budget reaches
first for anyone below 1 TB); the bounding box is not used for the budget, since it overestimates a
disc by a quarter and a drawn region by half. The live size line while drawing uses the phone's own
manifest; the server is asked once at Download (the two differ in the DEM start zoom and the
server's cap). The server's 413 sentence gains the polygon remedy.

### F. A polygon area is the region pack of its bounding box

The box mechanism: `/build` on a server with tilebuild (or the phone's own pack build), the outdoors
layers, the trail graph and the search index. The size is known only after the build; the pack holds
the whole box (a disc fills 79% of its square, a drawn region often half); it needs a server that
builds (the phone's build has no outdoors layers) and holds the box within `build.maxSpanDegrees`;
the pinned budget check comes after the build. ADR 0027 rejected a pack for a corridor for the
resolver's bounding-box ranking; for a compact polygon that objection is weak, but the size-first
flow (ADR 0030) does not exist for packs today and would have to be built, and the polygon would be
no more than a drawing on top of a box.
Exit cost: none (it is the box path); the polygon's rings would be decoration.
Moving parts: a size estimate for packs before the build (new on the server), or a flow that shows
the size after it, which the survey's apps do not do.

### G. A distance around a point is a one-point corridor

The format has it already: `TileCover.corridor` treats a single point as a zero-length segment, so a
corridor `{"line": [[lon, lat]], "bufferM": 10000}` is the disc of that radius, its id is canonical,
every build since T75 reads it, the server plans it, and `CorridorOutline` draws it as a disc
(`CorridorOutlineTest.oneOrNoPoint`). The radius shares the corridor's cap, `MAX_BUFFER_M`, 20 km,
which T130's corridors share too, so it is not raised for the disc: the chooser says "Up to 20 km
around a point; draw an area for more", and once the polygon kind has shipped a typed radius above
20 km is written as a polygon circle (a 48-vertex ring, H's first half), which no reader caps and
which moves no corridor constant. The card and the sheet label it from the geometry (one point:
"Around <name>", the radius) rather than from the kind, in three places, a coupling the critic
judged acceptable.
Exit cost: none; nothing is added to the format.
Moving parts: the radius chooser and the entry points (the long-press menu, a search result); a
`POST /areas/plan` and `/areas/download` call with one point, which exist.

### H. A distance around a point is a polygon circle, or a `disc` kind

A 48-vertex ring, or `{"kind": "disc", "center": [lon, lat], "radiusM": ...}`. The ring loses the
exact disc and the shared id (two phones get the same ring only if they polygonise alike); a `disc`
kind is a third shape for what the corridor already expresses. Rejected for the first build.

## Recommendation

**B with E and G.** The polygon enters the manifest as a `polygon` kind with even-odd rings and an
optional buffer, written at manifest version 2 only when present, with the opaque-record rule so the
phone never again loses an area it cannot read; a polygon area downloads as pinned tiles with the
size and the budget shown first; the distance around a point is the one-point corridor the format
already has, so the owner's first two shapes cost one format change, not two, and nothing the
established apps do better than a disc is lost (no surveyed app offers a distance picker at all).

**Order of building** (one task after T130 has merged, since it owns the same files this wave): the
readers first (version before kind in `AreaManifest.parse` and `Areas.Record.parse`, the opaque
record pinned by its bounds, the sibling file, the ADR 0033 wording on the Share screen and the
card, with their fixtures); the shape, the fill and the id in `shared` with tests (a property test
that the even-odd centre test plus the edge band equals the exact cover for any buffer at or above
zero; the rows for a ring with a hole; the writer's simplification adding its tolerance to the
buffer); the server's `POST /area` and `GET /area` with the version rule, the `pack=1` refusal, the
polygon remedy in the 413 sentence and SERVER.md's field-by-field section (version 2); the phone's
plan and download for a polygon with the bounding-box memory guard and the "kind must be" fallback;
then the two flows and their debug routes (`POST /areas/draw?ring=lat,lon;...` sets the drawing so a
screenshot and `POST /areas/plan` can be scripted; `GET /areas/draw` reads it); the Offline areas
sheet's outline for a polygon. The size line: bytes always (from the manifest's estimate, 160 kB a
DEM tile, a mountain upper end), the tile count in a details line, and a time from the last
completed download on this phone (`Areas.Record.bytes` over `downloadMs`: "about 3 min at the last
download's speed"), omitted before the first; the survey found no app that shows a time, so this is
a departure the owner asked for, kept honest by a measured rate (T75's emulator run fetched 18.1 MB
in 19 s against a local server). The scale, measured by the critic over `AreaManifest.tileItems` at
latitude 46.85 with DEM z6 to z15, vector z8 to z14 and one tile of margin (what the phone and the
server list): a 2 km disc is 256 tiles and 33 MB; 5 km 554 tiles and 68 MB; 10 km 1,333 tiles and
166 MB; 20 km (the cap) 3,988 tiles and 505 MB; a national-park box of about 2,750 km² 5,610 tiles
and 716 MB at z8 to z15 but 903 tiles and 89 MB at z8 to z13, an eightfold cut, which is why the
detail choice is offered. The fill is cheap: that box fills in 137 ms on the JVM and a 2,000-vertex
ring's band in 21 ms.

**The owner's positions** (ADR 0031: built from this text, shown in the PR with screenshots and the
strings, adjusted afterwards):

1. Drawing: from the Offline areas sheet, "Draw an area" starts from the current view as a
   four-vertex polygon; a tap on the map adds a vertex, a vertex drags, a midpoint handle adds one
   between its neighbours, a long press on a vertex removes it, with Undo; the sheet shows the live
   size and budget line while the ring changes (the plan recomputed after a short pause). This is
   the survey's common model (the box flow extended, Locus's handles) and departs from a lasso,
   which no app documents and a finger hides.
2. Around a point: from the long-press menu and from a search result, "Keep offline around here"
   opens the sheet with a radius chooser, presets in the user's units (2, 5, 10, 20 km, or 1, 3, 5,
   10 mi), default 5 km or 3 mi, the circle drawn live, a typed value allowed; its sentence "Up to
   20 km around a point; draw an area for more" names the way past the cap in the first build, and
   the polygon circle (G) lifts it afterwards. No surveyed app has this picker, so the presets are a
   proposal, not practice.
3. The summary: "About 170 MB (1,300 tiles) at full detail" with a second choice "Less detail
   (about 30 MB)" at z8 to z13 when the area is large or over the budget; the time line from the
   last download's speed, as above. The two-level
   detail choice departs from the consumer apps, which hide the zoom range, and from Locus, which
   shows every zoom; the wording is the owner's.
4. Over the budget: the corridor sheet's rule, extended: "This area is about 450 MB; 1.6 GB of your
   2 GB offline budget is in use. Make the area smaller, choose less detail, or raise the budget in
   Settings › Storage." with no Download. The cap of 200,000 tiles on the phone and the server is a
   safety net with its own sentence, not the steer.
5. Names: the search result's name, else "Around here" with the date for a disc, "Drawn area" with
   the date for a ring; the user can rename.

**The boundary step, later**, needs: a boundary source with a licence and a registry entry in
`sources.json` (candidates to evaluate then: OSM boundary and protected-area relations assembled
from the extracts the server already holds, ODbL, registry id `osm`; US Census TIGER for counties
and states and USGS PAD-US for protected areas, both public domain; Natural Earth admin-1, public
domain, too coarse for a county); the polygons kept whole somewhere a name search can reach (a
server index or a per-region boundary pack; the outdoors profile's merged, unnamed `park` polygons
and its level 2 and 4 admin lines cannot serve it); simplification to the 2,000-vertex cap with a
tolerance under a z15 tile; and a buffer. The format above carries the result without change: rings
with holes and parts, a buffer, an id from the simplified geometry. Not designed here.

## What would change this recommendation

- The owner wants the trail graph and search inside a drawn region now: F for polygons, or graph
  tiles as a manifest kind first (ADR 0030's trigger).
- The sibling file proves harder than ten lines (two files to save atomically, peers reading
  `areas.json` directly): accept the one-time downgrade loss, stated in the PR, as B first proposed.
- The live size line for a 2,000-vertex ring takes over 300 ms on the emulator while dragging a
  vertex: coarser simplification while drawing, or the fill on a worker with the last value shown.
- The owner wants the radius chooser itself to go past 20 km in the first build: the polygon circle
  (H's first half) ships with it rather than after.
- T130 reshapes `TileCover` or the plan path this wave: a rebase of the build order, not a reversal.

## Claims to verify

Checked by the critic on 2026-10-23 (`0041-critic-review.md`, Part 1), with the corrections taken
into the body above; what the build must still show is marked.

- `AreaOfInterest.parse` throws on an unknown kind; `AreaManifest.parse` parses `area` before
  `version`, so shipped builds say the kind sentence for a polygon manifest at any version
  (confirmed, run).
- `Areas.load` drops an unparseable record and the next `changed(save = true)` rewrites the file
  without it; `adopt` can trigger that save (confirmed). The record-level `bounds` and `zooms` exist
  for the opaque pin (confirmed).
- A one-point corridor is a disc and `CorridorOutline` draws it (confirmed,
  `CorridorOutlineTest.oneOrNoPoint`).
- `AreaDownload.plan` requires a corridor; a box has no size-first flow (confirmed).
- `serverManifest` falls back only on 404 and 405; a 400 fails the plan (contradicted; the body now
  asks for the "kind must be" fallback).
- The server's cap, 200,000 and HTTP 413 with corridor remedies; the phone's `localManifest` has none
  and fills every set before counting (confirmed).
- The sizes in the Recommendation are the critic's run over `AreaManifest.tileItems`; the build
  repeats them as a unit test and with `POST /areas/plan` on an emulator (to show).
- A rate exists per completed download (`Areas.Record.bytes`, `downloadMs`); the fetcher's
  `fetchedBytes` has no clock (confirmed).
- `park` is unnamed merged polygons; `boundary` is admin 2 and 4 lines (confirmed).
- `pinnedCacheMb` defaults to 2,048 MB (confirmed).
- Even-odd centres plus the edge band equal the exact cover for buffer at or above 0 (argued sound;
  the build's property test shows it).
- A refused peer manifest shows as a toast on the Share screen with the raw sentence (confirmed; the
  body now maps it to ADR 0033's wording).
- To show in the build: the sibling file's save is atomic with `areas.json`; the bounding-box guard
  refuses before any allocation; the live size line stays under 300 ms on the emulator for a
  2,000-vertex ring.

## Critic's objections and responses

Review of 2026-10-23 (`0041-critic-review.md`): 0 blocking, 5 serious, 6 minor; the recommendation
upheld after corrections. Every objection is taken into the body above.

1. **Serious: B's reader benefit misstated** (shipped builds parse `area` before `version`, so they
   say the kind sentence either way). Accepted with change: B now says so; the new build checks
   `version`, then `kind`, then fields in `AreaManifest.parse` and `Areas.Record.parse`, with a test
   that a version-3 manifest gets the version sentence; both refusals reach the user in ADR 0033's
   wording on the Share screen and the card.
2. **Serious: the downgrade loss accepted when a sibling file avoids it.** Accepted with change: B's
   rule 3 adopts `files/areas/areas2.json` for records a version-1 reader cannot parse, retired once
   every build in the field keeps opaque records; chosen because `adopt` can trigger the losing save
   with no user action and ADR 0033 §3 forbids a silent drop. The fallback to accepting the loss is a
   What-would-change line.
3. **Serious: the phone's tile cap has no lift and the wrong reason.** Accepted with change: E
   replaces it with a memory guard from the bounding box's tile count before any fill, derived from
   the heap, with a polygon sentence; the pinned budget stays the steer in bytes; the bounding box
   is not used for the budget (it overestimates); the server's 413 sentence gains the polygon remedy.
4. **Serious: a reader-enforced vertex cap repeats the `MAX_BUFFER_M` trap.** Accepted: A makes the
   count the writer's budget (about 2,000, simplified with the tolerance added to `bufferM` as
   corridors do), the reader lenient, the server's 2 MiB body the only hard limit.
5. **Serious: the 20 km refusal names no way out.** Accepted with change: the chooser says "Up to
   20 km around a point; draw an area for more"; past 20 km a typed radius is written as a polygon
   circle once the kind has shipped (G, position 2), which moves no corridor constant; H stays
   rejected as a kind.
6. **Minor: the sizes low by 1.5 to 3.5x in tiles; the 2 s fill condition cannot fail.** Accepted:
   the Recommendation carries the critic's measured table (vector tiles and DEM z6 and z7 were
   missing) and the fill timings; the condition is now the live size line within 300 ms.
7. **Minor: the live size line would call the server on every edit.** Accepted: E says the live
   number is the phone's own manifest and the server is asked once at Download.
8. **Minor: an older server does not fall back; `pack=1` on a polygon is C on the server.** Accepted
   with change: a 400 starting "kind must be" falls back like 404 and 405; a missing `shapes` means
   box and corridor; `pack=1` on a polygon is refused with a pointer to a box (chosen over a noted
   bounding-box pack, since the box path is one tap away and the manifest should not say polygon
   over a box).
9. **Minor: even-odd cancels overlapping parts.** Accepted: A states that the writer guarantees
   non-overlapping rings (the drawing flow makes one ring; the boundary step unions first).
10. **Minor: the time line's source.** Accepted: the rate is the last completed download's
    (`bytes` over `downloadMs`), "at the last download's speed", omitted before the first; no
    session counter.
11. **Minor: prior-art gaps.** Accepted: five apps filled from the critic's pass (the grid-of-blocks
    model noted), the 403s named as bot-blocked, Apple's anchors attributed to the guide, Komoot's
    official page cited.

Hidden coupling the critic found and judged acceptable (the disc decided by "one point" in three
places; areas not synced today, so the polygon rides as an opaque `area` object; a third `AreaShape`
breaking every exhaustive `when` at compile time) is recorded in G and left as is.
