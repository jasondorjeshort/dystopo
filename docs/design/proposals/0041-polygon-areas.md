# 0041. Polygon areas in the manifest: a drawn region and a distance around a point first

Date: 2026-10-23
Status: under review

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
  routes. Today an unknown `kind` throws in `AreaOfInterest.parse`; a manifest whose `version` is
  above 1 is refused by `AreaManifest.parse` with "manifest version N is newer than this app reads";
  and `Areas.load` drops a record it cannot parse with a log line, after which the next save rewrites
  `areas.json` without it (the area and its pin are gone; its tiles become ordinary LRU entries).
- ADR 0033: built in alpha without asking, but user data (pinned areas) migrates, a mismatched version
  refuses with a reason the user can read, and nothing is misread or dropped silently; an upgrade test
  with a fixture in the old format.
- ADR 0030: pinned bytes have their own budget (`pinnedCacheMb`, 2 GB by default), checked before an
  area downloads; the size is shown first, with no Download when the budget would be passed. ADR 0023:
  no Wi-Fi gate; the steer is the visible budget.
- The server caps a tile manifest at `DYSTOPO_AREA_MAX_TILES` (200,000, HTTP 413 with what to change);
  the phone's own manifest (`AreaDownload.localManifest`, used with direct sources or an older server)
  has no cap.
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
| Organic Maps, Maps.me, Avenza, Topo GPS, Backcountry Navigator, Guru Maps, Alpine Quest | Not established from a public source in this pass | | |

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
text; the Wi-Fi recommendations of three apps are not copied (ADR 0023).

## Options

Three parts: how the polygon enters the format (A to D), what a polygon area downloads (E, F), and
how the distance around a point is represented (G, H). The flows are the owner's positions, below.

### A. A `polygon` kind, manifest version unchanged

`{"kind": "polygon", "rings": [[[lon, lat], ...], ...], "bufferM": 0, "minZoom": 8, "maxZoom": 15,
"name": "..."}`: rings in GeoJSON order, the first the outer ring, later ones holes or further parts,
inside decided by the even-odd rule (so a county with islands and a park with an enclave need no
multipolygon type); `bufferM` optional, default 0 (the boundary step wants "the park and 5 km
around it"); at most 2,000 vertices over all rings after `Polyline.simplify`; no ring crosses the
antimeridian (as for a box). The id canonical form is `polygon:<buffer>:<rings>:<zooms>`, rounded as
today. `TileCover.of` gains the case: a tile is in the cover when its square is within `bufferM` of
some ring edge (the corridor code on the closed ring; distance 0 when the edge crosses the square)
or its centre is inside by even-odd, filled by a scanline per tile row so the result comes out as the
manifest's `[y, x0, x1]` rows directly. `AreaPins.cover`, the eviction rule, the version pins and
the margin need no change. The server's `POST /area` defaults a polygon to tiles (`pack=0`) as for a
corridor; `pack=1` builds the bounding box's pack as for a box. The manifest's `version` stays 1.
Older builds: the server answers 400 "kind must be box or corridor"; a peer's phone fails the
manifest with the same sentence; a phone downgraded to v0.1.10 drops the record at load and loses it
at the next save, with only a log line. Cheapest; it is the precedent `versions` set (an optional
field older readers ignore), but a new kind is not an optional field: ADR 0033 §3 is not met on the
downgrade path.
Exit cost: the rings and the fill are self-contained (one `when` branch in three files); removing the
kind means migrating polygon records to their bounding boxes.
Moving parts: a point-in-ring and a scanline fill in `shared/.../areas/`; a `Polygon` outline in the
Offline areas sheet (a GeoJSON fill, simpler than `CorridorOutline`); the server's parse.

### B. A `polygon` kind, and a manifest that carries one is version 2

As A, with one rule: a writer writes the lowest `version` that holds the content, so a box or
corridor manifest stays version 1 (an older peer still reads it) and a polygon manifest is version
2. Older builds then refuse a peer's polygon manifest with "manifest version 2 is newer than this app
reads (1)", which is the plain refusal ADR 0033 asks for, and the server's 400 stays as in A. The
phone's own records are not helped: `Areas.load` parses the `area` object before any version, and
the builds already shipped cannot be changed. So B adds the reader rule for the future: the new
build keeps a record it cannot parse as an opaque entry (its raw JSON written back unchanged, its
`bounds` and `zooms`, which every record carries, used as a conservative box pin so its tiles stay
kept, its card saying it needs a newer version of the app), so the next format change (holes, a
larger buffer, a new kind) is safe on a downgrade. The one-time loss on a downgrade from the build
that introduces polygons to v0.1.10 is recorded in the PR and in SERVER.md as a known limit of the
alpha. `GET /area` on the server reports `manifestVersion` 2 and a `shapes` list, so a phone knows
before asking whether the server reads polygons and makes the manifest itself otherwise (the path
it already takes for a server without `/area`).
Exit cost: as A, plus the version-by-content rule, which costs nothing to keep.
Moving parts: as A, plus the opaque-record path in `Areas.load` and its upgrade and downgrade tests
(a fixture from v0.1.10, and a fixture with an unknown kind).

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
Moving parts: a tile cap on the phone's own manifest (the server's 200,000 as the default, with the
same sentence: shrink the area or lower the detail), since a polygon over a state is now one tap.

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
every build since T75 reads it, the server plans it, and `CorridorOutline` draws its outline (to
verify for a single point). The radius is bounded by `MAX_BUFFER_M`, 20 km; raising that cap would
make older builds drop records above it, so the first build stays within it. The card and the sheet
label it from the geometry (one point: "Around <name>", the radius) rather than from the kind.
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
shape, the fill and the id in `shared` with tests (a property test that the even-odd centre test plus
the edge band equals the exact cover for any buffer at or above zero; the rows for a ring with a hole;
a fixture `areas.json` from v0.1.10 read unchanged; a record with an unknown kind kept opaque and
pinned by its bounds); the server's `POST /area` and `GET /area` with the version rule and SERVER.md's
field-by-field section (version 2); the phone's plan and download for a polygon with the tile cap;
then the two flows and their debug routes (`POST /areas/draw?ring=lat,lon;...` sets the drawing so a
screenshot and `POST /areas/plan` can be scripted; `GET /areas/draw` reads it); the Offline areas
sheet's outline for a polygon. The size line: bytes always (from the manifest's estimate, 160 kB a
DEM tile, a mountain upper end), the tile count in a details line, and a time only when the fetcher
has measured a rate in this session ("about 3 min at the current speed"), omitted otherwise; the
survey found no app that shows a time, so this is a departure the owner asked for, kept honest by
the measured rate (T75's emulator run fetched 18.1 MB in 19 s against a local server). A rough scale
at Rainier's latitude with today's estimates, to be confirmed by the critic or a unit test over
`AreaManifest.tileItems`: a 5 km disc at z8 to z15 is about 300 tiles and 55 MB; 10 km about 900
tiles and 150 MB; 20 km (the cap) about 2,800 tiles and 450 MB; a drawn region the size of a
national park (about 1,900 km²) about 4,300 tiles and 700 MB at z8 to z15 but about 260 tiles and
50 MB at z8 to z13, which is why the detail choice is offered.

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
   10 mi), default 5 km or 3 mi, the circle drawn live, a typed value allowed within the 20 km cap.
   No surveyed app has this picker, so the presets are a proposal, not practice.
3. The summary: "About 150 MB (900 tiles) at full detail" with a second choice "Less detail (about
   30 MB)" at z8 to z13 when the area is large or over the budget; time as above. The two-level
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
- A downgrade path for alpha testers matters more than a clean kind: C.
- A 2,000-vertex ring at z15 over a 100 km box takes more than two seconds to fill on a phone: a
  coarser simplification or rows precomputed by the server.
- The owner wants a radius above 20 km: raise `MAX_BUFFER_M` in the same version step, accepting
  that older builds drop such records, or make the disc a polygon (H).
- `CorridorOutline` cannot draw a single point: a circle outline for one-point corridors in the
  sheet (a few lines), not a change of shape.
- T130 reshapes `TileCover` or the plan path this wave: a rebase of the build order, not a reversal.

## Claims to verify

- `AreaOfInterest.parse` throws on an unknown kind ("kind must be box or corridor"):
  `shared/.../areas/AreaOfInterest.kt`, `parse`. `AreaManifest.parse` refuses `version` above 1 with
  the quoted sentence: `AreaManifest.kt`, `parse`.
- `Areas.load` drops an unparseable record ("unreadable area dropped") and `changed(save = true)`
  rewrites `areas.json` from the parsed records only: `androidApp/.../areas/Areas.kt`.
- A one-point corridor is a disc: `TileCover.corridor` builds `listOf(points[0] to points[0])` for one
  point; whether `CorridorOutline` handles it is unverified (`CorridorOutlineTest`).
- `AreaDownload.plan` requires a corridor today (a box has no size-first flow): `AreaDownload.kt`.
- Whether `serverManifest` falls back to the phone's own manifest on a 400 from an older server, as
  it does for a server without `/area`: `AreaDownload.kt`, `serverManifest`.
- The server's cap and its sentence: `BuildRoutes.kt`, `maxAreaTiles` (200,000), HTTP 413; the
  phone's `localManifest` has none.
- The size figures above: a unit test over `AreaManifest.tileItems` for a one-point corridor at 5, 10
  and 20 km near latitude 47 with `estimate`'s 160 kB a DEM tile, and `POST /areas/plan` with
  `line=<lat>,<lon>&bufferM=10000` on an emulator.
- Whether the online fetcher keeps bytes and times from which a session rate can be read (unknown;
  if not, the time line waits for a counter).
- The `park` layer is polygons without names, merged per tile, and `boundary` is lines at admin
  levels 2 and 4 only: `tilebuild/PROFILE.md`.
- `pinnedCacheMb` defaults to 2,048 MB: `AppSettings.DEFAULT_PINNED_CACHE_MB`.
- Even-odd on tile centres plus the edge band equals the exact cover for buffer at or above 0: a
  property test against a brute-force sampler, in the build, not here.
- Older peers: `PeerSources` lists areas from `GET /peer/areas` without parsing `area`; where a
  refused `GET /peer/areas/<id>` is shown to the user is unverified.

## Critic's objections and responses

(none yet)
