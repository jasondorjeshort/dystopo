---
date: 2026-11-01
kind: feature
---

# The site's pack builder takes a drawn polygon and hands the phone a download link

"Build a pack here" (`tools/demo/index.html`, `serverlib/.../BuildRoutes.kt`) takes the current view
as a box. On a desk one would rather draw: a polygon tool on the map, the size shown before the build
(the phone's size-first card has the arithmetic), then a link or QR code the phone opens to pull the
pack from `/out/`. For anyone with a desk this replaces most of the in-app country picker.

## Open

- `POST /build` takes a bbox; a polygon build is the server-side half (proposal 0041 polygon areas
  covers the phone's side).
- The link's form (the share-links idea) and whether the phone registers it as an offline area.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): the server half exists: `POST /area` takes a polygon and answers a manifest with
estimated sizes, refusing an oversize one with "draw a smaller area" (`BuildRoutes.kt`); the phone draws,
sizes and downloads polygons (`app/areas/AreaDraw.kt`). But a polygon is tiles in the phone's cache, not a
pack file: `pack=1` on a polygon is a 400, and `/out/` serves box packs only. No link scheme for an area
exists and the page has no drawing tool or QR renderer.

Question: a tile area (what the server builds for a polygon today) or a real pack file for a polygon, and
may a link the phone opens register an area by itself? Recommendation: the tile area, in two passes: (1) a
drawing tool on the page with the size line from the manifest, `tools/demo` only; (2) a `dystopo://area`
link and QR the phone opens to a confirmation card, nothing registered without a tap (the `dystopo://share`
rule). A polygon pack file is a pack-format change and a proposal; not now.
Answer:
