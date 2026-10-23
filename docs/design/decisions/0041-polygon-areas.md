# 0041. Polygon areas in the manifest: a drawn region and a distance around a point first

Date: 2026-10-23
Status: accepted (the coordinator's technical decision after the critic; the owner's positions in
Decision §5 are built as written in alpha and adjusted afterwards, ADR 0031)

Record: proposal `../proposals/0041-polygon-areas.md` and `../proposals/0041-critic-review.md`
(0 blocking, 5 serious, 6 minor; the recommendation upheld after corrections); the owner's answer
of 2026-10-23 in `docs/ideas/2026-10-13-download-by-region.md`; the ADR 0039 survey in the
proposal's Prior art.

## Context

An area of interest (ADR 0027, amended by ADR 0030) is a box or a route corridor; the owner asked
for a region the user draws, a distance around a point, and later a county, state or park, each
with the size shown first. The manifest is read by the phone's own records (`files/areas/areas.json`),
by peers (`GET /peer/areas/<id>`) and by the server (`POST /area`), so the shape went through a
proposal (ADR 0033 §4). Verified by the critic in the code and on the JVM: every shipped build
parses the `area` object before it checks `version`, so a new kind draws "kind must be box or
corridor" whatever the version; `Areas.load` drops a record it cannot parse and the next save (which
`adopt` can trigger with no user action) rewrites the file without it; the phone's own manifest
fills every tile set before counting, with no cap; a one-point corridor is already a disc, drawn by
`CorridorOutline`; `serverManifest` falls back to the phone's lists only on 404 and 405. Measured
over `AreaManifest.tileItems` at latitude 46.85 (DEM z6 to z15, vector z8 to z14, one tile of
margin): a 5 km disc 554 tiles and 68 MB, 10 km 1,333 and 166 MB, 20 km 3,988 and 505 MB; a
national-park box of about 2,750 km² 5,610 tiles and 716 MB at z8 to z15 and 903 tiles and 89 MB
at z8 to z13; the fill of that box 137 ms on the JVM. The outdoors profile keeps admin lines at
levels 2 and 4 and unnamed, merged `park` polygons only (`tilebuild/PROFILE.md`), so no named
boundary can be read from the tiles.

## Decision

1. **The format.** `AreaShape` gains `Polygon`; the area object is `{"kind": "polygon", "rings":
   [[[lon, lat], ...], ...], "bufferM": 0, "minZoom", "maxZoom", "name"}`: rings in GeoJSON order,
   inside decided by the even-odd rule (holes and further parts are more rings; no multipolygon
   type), rings never overlapping (the writer's guarantee: the drawing flow makes one ring, the
   boundary step unions first), `bufferM` optional with default 0, no ring crossing the
   antimeridian. The id canonical form is `polygon:<buffer>:<rings>:<zooms>`, rounded as today. The
   vertex count is the writer's budget, not a reader's rule: writers simplify to about 2,000
   vertices over all rings with `Polyline.simplify` and add the tolerance to `bufferM`, as corridors
   do; readers accept any count; the server's 2 MiB body is the only hard limit. A tile is in the
   cover when its square is within `bufferM` of a ring edge (the corridor code on the closed ring)
   or its centre is inside by even-odd, filled per tile row so the result is the manifest's
   `[y, x0, x1]` rows; `AreaPins`, the margin and the version pins are unchanged.
2. **Versions and older readers.** A writer writes the lowest manifest `version` that holds the
   content: box and corridor manifests stay 1, a polygon manifest is 2. From this build on,
   `AreaManifest.parse` and `Areas.Record.parse` check `version`, then `kind`, then the fields, and
   both refusals reach the user as "this phone runs an older Dystopo; update it to use this area"
   with the raw sentence under it (the Share screen's toast, the area card). A record the phone
   cannot parse is kept opaque: its JSON written back unchanged, its record-level `bounds` and
   `zooms` used as a conservative box pin, its card saying it needs a newer app. Shipped builds
   (v0.1.10 and earlier) meet a version 2 record as follows: the server answers 400 "kind must be
   box or corridor"; a peer's phone shows that sentence; a phone's own `areas.json` is protected by
   the **sibling file**: records a version-1 reader cannot parse are written to
   `files/areas/areas2.json` and `areas.json` keeps only what v0.1.10 reads whole, so a downgraded
   build's save loses nothing and the upgrade reads both. The sibling file is retired (folded back
   into `areas.json`) once every build in the field keeps opaque records, that is one release after
   the first polygon build has been the oldest alpha the owner supports. Tests: a v0.1.10 fixture
   read unchanged; a version-3 manifest getting the version sentence; an unknown-kind record kept,
   pinned by its bounds and written back; the two files saved atomically.
3. **The download.** A polygon area is pinned tiles, the corridor mechanism: `AreaDownload.plan`
   extends to polygons, the size (bytes from the manifest's estimate, the tile count in a details
   line, a time from the last completed download's `bytes` over `downloadMs`, omitted before the
   first) and the pinned budget (`pinnedCacheMb`, ADR 0030) are shown before anything starts, with
   no Download when the budget would be passed; no Wi-Fi gate (ADR 0023). Before any tile set is
   filled, the bounding box's tile count over the zoom range is checked against a limit derived
   from the heap, and a shape over it is refused with "This area is too large at this detail; make
   it smaller or choose less detail"; the budget stays the steer in bytes and the bounding box is
   never used for it. The live size line while drawing uses the phone's own manifest; the server is
   asked once at Download. The server's `POST /area` defaults a polygon to tiles, refuses `pack=1`
   on a polygon ("a pack is a box; download a box for the trail graph"), keeps its
   `DYSTOPO_AREA_MAX_TILES` cap (the operator's lift) with the polygon remedy in its 413 sentence,
   and `GET /area` reports `manifestVersion` 2 and `shapes`; the phone treats a missing `shapes` as
   box and corridor and falls back to its own manifest on a 400 that starts "kind must be", as on
   404 and 405. A polygon area has no trail graph or search index until graph tiles become a
   manifest kind (ADR 0030's trigger), as for corridors.
4. **The first two shapes.** A drawn region is a polygon with `bufferM` 0. A distance around a point
   is the one-point corridor the format already has (`TileCover.corridor` treats one point as a
   zero-length segment; every build since T75 reads it), labelled from its geometry; its radius
   shares `MAX_BUFFER_M` (20 km) with T130's corridors and that cap is not raised: a typed radius
   above 20 km is written as a polygon circle (a 48-vertex ring) once the kind has shipped. Debug:
   `POST /areas/plan` and `POST /areas/download` take the polygon body; `POST /areas/draw?ring=
   lat,lon;...` sets the drawing and `GET /areas/draw` reads it, so the sheet can be scripted and
   screenshotted. Built in one task after T130 has merged (it owns `areas/**` this wave), readers
   first, then the shape and fill in `shared`, the server, the phone's plan and download, the flows.
5. **The owner's positions** (ADR 0031: built as written, shown in the PR with screenshots and the
   strings, adjusted by the owner afterwards; no product name in any string):
   - The gesture: "Draw an area" in the Offline areas sheet starts from the current view as a
     four-vertex polygon; a tap adds a vertex, a vertex drags, a midpoint handle adds one, a long
     press removes one, with Undo; the size line updates while drawing. No lasso.
   - Around a point: "Keep offline around here" from the long-press menu and a search result;
     presets 2, 5, 10, 20 km or 1, 3, 5, 10 mi by the unit preference, default 5 km or 3 mi, the
     circle drawn live, a typed value; the sentence "Up to 20 km around a point; draw an area for
     more".
   - The summary: "About 170 MB (1,300 tiles) at full detail" with "Less detail (about 30 MB)" at
     z8 to z13 offered when the area is large or over the budget; the time line as in §3.
   - Over the budget: "This area is about 450 MB; 1.6 GB of your 2 GB offline budget is in use.
     Make the area smaller, choose less detail, or raise the budget in Settings › Storage.", no
     Download.
   - Names and labels: the search result's name, else "Around here" or "Drawn area" with the date;
     renamable.
6. **Deferred: the boundary search** (county, state, park), revived when the owner asks for it or
   the first polygon build has shipped. It needs a boundary source with a licence and a registry
   entry in `sources.json` (candidates: OSM boundary and protected-area relations from the server's
   extracts, ODbL; US Census TIGER and USGS PAD-US, public domain; Natural Earth admin-1, too
   coarse for a county), the polygons kept whole where a name search can reach them (a server
   index or a per-region boundary pack; the tiles cannot serve it), simplification within the
   writer's budget with the tolerance in the buffer, and a buffer. The format carries it unchanged.

Rejected: a polygon that degrades to a box (`kind: box` plus a `ring` hint: a format that lies
about its kind, and the same trick again for holes); everything as a polygon (every existing id
changes; a corridor's buffer belongs to its line); a polygon as the region pack of its bounding
box (no size-first flow for packs, half the pack outside the drawing); a `disc` kind or a
polygonised circle for the first build (the one-point corridor exists and shares its id across
phones); a reader-enforced vertex cap (the `MAX_BUFFER_M` trap: a raise drops records on every
older build); accepting the one-time downgrade loss (a silent drop, ADR 0033 §3).

## Consequences

- One format change carries both first shapes and any later boundary; the disc costs nothing.
- Two files hold the records for one release, and every reader gains a parse order and an opaque
  path that must be kept; the first downgrade in the alpha costs a tester nothing.
- Coupling: the disc, T130's trip corridor (1 km each side, counted against the same budget) and
  ADR 0040's corridor per stop (T132's proposal, pending) are all corridors under the same
  `MAX_BUFFER_M`, `plan` and pins; a change to the corridor's cap, zooms or plan moves all three,
  and a disc is told from a corridor by "one point" in its label, its outline and the server's
  default name.
- The memory guard refuses what a raised budget would allow; the lift is less detail or a smaller
  shape, never a hidden constant. Server operators lift their own cap as before.
- Supersede when graph tiles become a manifest kind (a polygon then carries routing and search),
  when the boundary search lands (its source, index and buffer rule), or when areas are synced
  between phones (the opaque `area` object then needs a sync rule).
- Prediction, check when the first polygon build ships: a v0.1.10 phone that reads its `areas.json`
  after that build has written polygon records keeps every box and corridor area it had.
- Prediction, check by 2027-04-23: no reader-side cap on the polygon's vertices or the disc's
  radius has been raised in a version step; the boundary step, if built, fitted the writer's budget.
