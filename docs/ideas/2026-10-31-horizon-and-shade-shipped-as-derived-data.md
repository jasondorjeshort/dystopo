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
