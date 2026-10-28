# 0047. The medium detail: elevation to z13 with vector to z14, as a per-kind zoom cap in the area

Date: 2026-10-28
Status: accepted (T157; the coordinator's technical decision, the field's name and encoding the implementer's; the
tiers' words built under ADR 0031 for the owner to adjust)

Record: task T157 (`docs/TASKS.md`, wave 21), the idea `docs/ideas/2026-10-26-downloads-without-a-size-cap.md` (item 3,
deleted with T157), the wave 21 critic. Amends ADR 0041: §5 (a third detail beside full and less), §1 (one optional field
of the area object and its place in the canonical id) and §2 (manifest version 3). The rest of ADR 0041, and ADR 0045,
stand.

## Context

An area had one zoom range: DEM tiles to `maxZoom`, vector tiles to `min(maxZoom, 14)`. Full detail (z8 to z15) is
mostly DEM bytes at z14 and z15; less detail (z8 to z13) drops them and also vector z14, where sidewalks, crossings,
driveways and several kinds of point of interest exist only (`tilebuild/PROFILE.md`) and which the routing graph reads.
The owner asked for the middle: elevation to z13 with vector to z14. One range cannot write it.

## Decision

1. **The field.** The area object gains `demMaxZoom`, written after `maxZoom` and only when set: the DEM tiles stop
   there while the vector tiles go to `min(maxZoom, 14)` (`{"kind": "corridor", ..., "minZoom": 8, "maxZoom": 14,
   "demMaxZoom": 13}`). A cap at or above `maxZoom` is no cap: readers drop it, writers never write it. Named after the
   route it caps (`dem`, as `GET /area` already says `demZooms` and `osmMaxZoom`); one field rather than a per-route map,
   because the vector cap is the source's own (z14) and no detail wants vector below elevation.
2. **The id.** The canonical form gains `:dem<z>` after the zooms (`...:8-14:dem13`) when the field is set, so the
   medium disc's id differs from the less and full discs' and every id written before stays as it was (an uncapped
   area's canonical form is unchanged; a v0.1.13 fixture is read and written back byte-equal).
3. **Version 3.** A capped area is manifest version 3 (records written to `areas2.json`, as polygons). A version-2 reader
   (v0.1.11 to v0.1.14) tolerates an unknown field, so it would read a medium area as `maxZoom` 14 with the elevation
   to z14, nearly all of full detail's bytes, and its T151 auto-resume would fetch them; at version 3 it keeps the record
   opaque, pinned by its bounds at z8 to z14, and says the phone needs a newer app (ADR 0041 §2's path). v0.1.10 never
   reads `areas2.json`. The sibling file's own `version` is the newest its records need (2 without a capped record).
4. **The details** (`AreaDetail` in `shared`): full z8 to z15; medium z8 to z14 with `demMaxZoom` 13; less z8 to z13. The
   picker's summary lists the two details not shown with their sizes, a country row the three sizes (the full size
   estimated from z14's cover when its floor is past the budget), a country opens at the deepest detail that fits the
   budget, a region's medium detail takes the area path (a pack holds one zoom range).
5. **The server.** `POST /area` reads the field (body or query, or `detail=full|medium|less`), answers a version 3
   manifest, defaults a capped box to tiles and refuses `pack=1` with a cap (400). `GET /area` names `zoomCaps` (`dem`)
   and `details`; a phone asks a server for a capped manifest only when `zoomCaps` names `dem`, and takes a server's
   manifest only when its id is the area's.

Rejected: lowering `maxZoom` to 13 and adding a vector-only extra (an old reader would download less than asked, silently,
and the field would mean the opposite of its name); a per-route map (`"maxZooms": {"dem": 13}`: generality nothing asks
for, and every reader then needs a rule for routes it does not know); keeping version 2 (the misread above).

## Consequences

- A medium area costs about a tenth of full detail's bytes over a large area (Switzerland: 1.2 GB against 12.8 GB, less
  1.1 GB) and a third over a 5 km disc (24 MB against 64 MB), with every vector feature of full detail.
- An older build meets a medium record as "needs a newer app"; a peer or server older than T157 does too, or (a server)
  is not asked.
- `AreaPins` pins an area's cover at every zoom to `maxZoom` for every route, so DEM z14 tiles browsed inside a medium
  area are pinned too (a small cost, not a misread).
- Supersede when areas carry graph tiles as a kind, when a detail needs a vector cap below elevation, or when areas are
  synced between phones.
