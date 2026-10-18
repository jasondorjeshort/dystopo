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

Today (T75, ADR 0027): an area of interest is a box or a route corridor with a buffer and a zoom
range, expanded to a manifest of files; the Offline areas sheet starts box downloads and corridor
downloads. No boundary-based or drawn areas.

## Open

- Boundary data: administrative and park boundaries need a licensed source (OSM boundaries are
  already in the vector tiles; check what the outdoors profile keeps) and an entry in
  `sources.json` first. Search by name could reuse the search index.
- Area shape: a polygon in the manifest rather than a box; tile selection by polygon with a
  buffer; size estimate shown before starting (bytes and time).
- Whole states are large: the zoom range and a size confirmation matter; this ties to the cache
  budgets and the pinned-area budget.
