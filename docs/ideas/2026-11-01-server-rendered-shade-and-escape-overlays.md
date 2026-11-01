---
date: 2026-11-01
kind: feature
related: [2026-11-01-escape-time-overlay-for-a-whole-area.md, 2026-10-31-horizon-and-shade-shipped-as-derived-data.md, 2026-11-01-shade-aware-routing.md]
---

# Server-rendered overlays: sun and shade at a chosen time, escape time, as tile routes on the site

The shade-along-the-route and escape-time ideas compute from the DEM alone, and the phone's heap is
the constraint that keeps shipping them. The server has the DEM, the cache and the CPU
(`serverlib/.../LiveTiles.kt` already renders hillshade and slope rasters at `/shade/{z}/{x}/{y}.png`).
A route `/overlay/shade/{z}/{x}/{y}.png?at=<time>` gives a time-scrubbable sun-and-shade map on the
web today (a slider on the page), and `/overlay/escape/...` the minutes to lower ground for a whole
area. The same tiles, precomputed for a pack's box at a few times of a few days, become the
derived-data layer the horizon-and-shade idea wants to ship to the phone.

## Open

- Cost per tile at z12 to z14 with horizon shadows (the horizon idea's measurement).
- Caching by time bucket (15 minutes) and date, or the overlay is never warm.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
