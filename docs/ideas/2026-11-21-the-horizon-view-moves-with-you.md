---
date: 2026-11-21
kind: feature
related: [2026-11-20-horizon-view-from-another-place-and-saying-where-it-is.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md]
---

# The Horizon view moves with you along a route, refreshed as cost allows

Proposed 2026-11-21 as a Horizon view that scrubs along a planned route (the peaks in view, the sun at the time you'd be
there, when you drop below a ridge). The owner, 2026-11-21: "We want the horizon view to move with us, but given the
cost of updating it this can't be immediate."

Today the view is computed for the fix when it opens (`Gnss.horizonAt`, about 0.1 s warm, 46 s cold just after boot
on the emulator, T317's report) and stays put. Moving with you means recomputing as the fix moves: a sweep only when
the position has moved far enough to change the skyline (a distance that grows with the nearest ridge's distance),
reusing the cached tiles, never on every fix; and, along a planned route, precomputed skylines at intervals so a
scrub is cheap. The battery doctrine (`2026-10-13-battery-efficiency.md`) bounds how often.

## Open

- The move threshold and its cost on a real phone (the sweep's time per point, measured).
- Whether the route scrub belongs with the one-clock idea (`2026-11-21-one-clock-for-the-whole-app-past-and-future.md`):
  a point along the route and the time you reach it are one control.
