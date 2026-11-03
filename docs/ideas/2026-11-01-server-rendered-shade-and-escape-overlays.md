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

2026-11-02 (triage): `/shade/{z}/{x}/{y}.png` is lit-face hillshade and slope with no time; the derived
cache keys by options and DEM version only. `CastShadow` (T181) computes shadow at an instant over a
terrain window with far-ridge horizons, used by shared and devtool, not by serverlib. `WayDown` answers
from one ridge point along a line, 5 km each way; no area-wide form exists (the escape-time idea).

Question: is a time-scrubbable shade map on the site worth the home PC's CPU at z12 to z14 (unmeasured;
the phone's T188 figures were 0.5 to 1.3 s a tile toward a low sun)? Recommendation: yes, one task in two
passes: `/overlay/shade/{z}/{x}/{y}.png?at=` through `CastShadow`, cached by 15-minute bucket and day
beside the derived files, measured first at the three zooms on the Rainier pack, then the page's slider;
the escape overlay stays with its own idea, which needs a design.
Answer:
