---
date: 2026-10-31
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md]
---

# Horizon profiles and day-shade shipped with a server-built pack

T182's viewshed and T188's shade pay a cold DEM read and a horizon sweep on the phone (50 s cold, 3 s warm at 30 km).
T189 made the routing graph derived data a server-built pack ships under a catalog `derived` entry. Do the same for
the terrain: a horizon layer (the highest elevation angle per bearing at a coarse grid, 32 bearings per cell) and a
sun-exposure summary per cell per month (hours of direct sun, terrain sunrise and sunset), built once on the server,
versioned and fingerprinted like the graph. The phone then answers "sun reaches here at" and draws a first viewshed
from the grid before refining locally, and the shade slider scrubs without a march. Open: the grid's cell size against
the bytes (the horizon at 1 km cells for a 0.5 degree pack is a few MB); whether the phone's own march stays as the
fallback with no server, as the graph builder did.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Question: a horizon or sun-hours grid shipped with a pack is a new file format phones read, so under CLAUDE.md ›
Decisions it needs a proposal, a critic and an ADR before building; do you want that proposal started, after a
measurement says what it would save? Recommendation: measure first, then decide. The `derived` mechanism is ready
(`packs/PackDerived.kt` reads any `kind` with `format`, `version` and `key`, and ignores kinds it does not know; the
server's `graph` phase in `serverlib/.../BuildRoutes.kt` is the pattern), but the gain is unproven: the 50 s cold
figure is mostly the DEM read (`Horizon.terrainAround`), which a coarse grid does not remove for the viewshed or the
per-pixel cast shadow (`render/CastShadow.kt` marches every cell at an instant, so a 1 km grid cannot drive the
slider; it can only answer a point or seed a first viewshed). A one-pass measurement task: `devtool shade --horizon-grid`
on the Rainier pack at 250 m, 500 m and 1 km cells with 32 bearings, reporting bytes against the pack, build time, and
the error of the grid's terrain sunrise and sunset against `ShadeTiles.pointAnswer` at sampled points; the proposal only
if the point error is a few minutes at a size under the graph's share. The sun-hours-per-month summary is derivable from
the same horizon grid, so it should not be a second file.

Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
