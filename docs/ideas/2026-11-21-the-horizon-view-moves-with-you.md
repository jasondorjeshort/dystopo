---
date: 2026-11-21
kind: feature
related: [2026-11-20-horizon-view-from-another-place-and-saying-where-it-is.md, 2026-11-21-the-view-from-a-routes-high-point.md]
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

Question: the view already follows the fix: while it is open, `Gnss.horizonAt` sweeps again once the phone is 100 m
from every kept horizon (`Gnss.HORIZON_MOVE_M`, T307), so "stays put" above is not what the code does. What is left is
(a) smoothing: the last skyline and its names stay up while the next is swept (today they drop out meanwhile), the
step grows with the nearest ridge's distance, and the sweeps are counted in the battery scenario; and (b) the scrub
along a planned route from skylines worked out ahead. Is (a) wanted as a small pass now, and (b) with the one clock?
Recommended: yes to both; (a) wants the sweep's time on your own phone first, and (b) is one control with the route's
clock (`2026-11-21-one-clock-for-the-whole-app-past-and-future.md`).

Answer:
