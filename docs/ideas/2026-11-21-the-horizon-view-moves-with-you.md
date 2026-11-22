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

2026-11-22, the read-only research the owner asked for (desk only; no sweep timing is recorded anywhere in `docs/`): no
published algorithm updates a raster terrain horizon incrementally for a moving viewer. Synthetic-vision and game
practice re-sweeps from a cached height field; GRASS `r.horizon` is GPL, so it is a source of ideas only. Geometry still
allows an estimate: a ridge at range r shifts about d/r in bearing, so 100 m moves a ridge at 5 km about 1.1 degrees
and one at 30 km about 0.2 degrees. The recommendation, in three tiers on `LayeredSkyline`:
(1) never clear: keep the last skyline until the next is complete, then swap; this alone ends the blanking;
(2) re-sweep the near layer at about 25 to 50 m and the far layer at 500 m or more, at least 1 s apart, one sweep running
at a time with the latest position winning, the interval adapting to about 1.5 times the measured sweep time;
(3) store range and height per azimuth so the far layer can be moved to the new position each frame (under 1 ms), and
optionally sweep ahead at the position 2 to 3 s ahead from GNSS speed and bearing.
Measure on a phone first: the near and far sweep times, cold and warm; the cost of reading the elevation for each new
window (likely the real limit); the error of moving the far layer against a fresh sweep at 50 to 500 m.

Question: the view already follows the fix: while it is open, `Gnss.horizonAt` sweeps again once the phone is 100 m
from every kept horizon (`Gnss.HORIZON_MOVE_M`, T307), so "stays put" above is not what the code does. What is left is
(a) smoothing: the last skyline and its names stay up while the next is swept (today they drop out meanwhile), the
step grows with the nearest ridge's distance, and the sweeps are counted in the battery scenario; and (b) the scrub
along a planned route from skylines worked out ahead. Is (a) wanted as a small pass now, and (b) with the one clock?
Recommended: yes to both; (a) wants the sweep's time on your own phone first, and (b) is one control with the route's
clock (`2026-11-21-one-clock-for-the-whole-app-past-and-future.md`).

Answer: both (the owner, 2026-11-22, by multiple choice at triage). Smoothing is the more important: while driving the view blanks out constantly, so the last skyline and names stay up until the next is ready, with a minimum interval between updates. The owner asks for a read-only research pass on whether an algorithm can update the horizon steadily while moving (an estimate carried forward between full sweeps, in the spirit of dead reckoning) before (a) is built. (b) comes with the one clock.
