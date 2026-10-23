---
date: 2026-10-13
kind: feature
related: [2026-10-13-auto-download-when-zoomed-in.md]
---

# Download topo by county, state, park, drawn region, or distance around a point or route

Broader ways to pick what to keep offline than the box or corridor we have now:

- an administrative area: county, state, or country;
- a national park (or other protected area);
- a region the user draws;
- a distance around a point, or around a route (a corridor with a configurable buffer).

Today (T75, ADR 0027, pinning amended by ADR 0030): an area of interest is a box or a route
corridor with a buffer and a zoom range (`AreaShape` in `AreaOfInterest.kt` has only `Box` and
`Corridor`), expanded to a manifest of files; the Offline areas sheet starts box downloads and
corridor downloads. No boundary-based or drawn areas.

## Open

- Boundary data: administrative and park boundaries need a licensed source (OSM boundaries are
  in the vector tiles, but `tilebuild/PROFILE.md` keeps only country and state lines, admin_level
  2 and 4, as lines rather than polygons; no county or park areas) and an entry in
  `sources.json` first. Search by name could reuse the search index.
- Area shape: a polygon in the manifest rather than a box; tile selection by polygon with a
  buffer; size estimate shown before starting (bytes and time).
- Whole states are large: the zoom range and a size confirmation matter; this ties to the cache
  budgets and the pinned-area budget.

Question: which shapes, in what order? Administrative and park boundaries need a licensed boundary source and a registry entry first; a drawn region, or a distance around a point, needs only a polygon in the area manifest, which changes a format phones read (ADR 0027), so either goes through a proposal. Recommendation: one proposal that adds a polygon area to the manifest and builds the drawn region and the distance around a point first (no new data source), with the boundary search (county, state, park) a later step, since the outdoors profile keeps no county or park polygons today; the size estimate before starting is common to all.
Answer: one proposal for a polygon area in the manifest, building the drawn region and the distance around a point first; boundaries later, as recommended (owner, 2026-10-23).
2026-10-23 (T133): the proposal is `../design/proposals/0041-polygon-areas.md`, under review; it recommends a `polygon` kind with even-odd rings downloaded as pinned tiles, and the distance around a point as the one-point corridor the format already has. The boundary search stays open here with what it needs (a licensed boundary source and registry entry, the polygons kept whole for a name search) until the ADR names its step.
