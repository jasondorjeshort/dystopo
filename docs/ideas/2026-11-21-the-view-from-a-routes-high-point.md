---
date: 2026-11-21
kind: feature
related: [2026-11-21-the-horizon-view-moves-with-you.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md]
---

# The view from a route's high point, and where a peak first comes into view

Two answers a planned route can give before the walk:

- **"View from the top."** The route card offers the Horizon view (the Panorama when it has a screen) at the route's
  highest point: the names you will see from there, and the sun at the hour you would arrive. It is the another-place
  mechanism as built (T317's chosen point with its amber place line, opened as `ViewSwitch.openAtSelection` opens a
  selection), with the route's highest sample as the point.
- **"First sight."** For a chosen peak, the point along the route where it first comes into view, marked on the route
  line and on the elevation profile ("Mount Rainier comes into view at mile 3.2"). One ray per route sample toward the
  peak is enough (`dem/Horizon.kt`, `RayMarch`), far cheaper than a skyline per sample.

Differs from the moves-with-you idea: that one scrubs the whole view along the route from skylines computed at
intervals; this is two fixed answers that need no scrub, and they can ship first. The skylines it computes are the same
ones that scrub would reuse.

Recorded 2026-11-21 from the coordinator's list after wave 44, on the owner's "we can record them all". Untriaged.

## Open

- The highest point is not always the best view (a wooded summit, a pass below a ridge), and the elevation model knows
  no trees: whether to offer the sample with the most names in view instead, at the cost of a sweep per candidate.
- A route with several tops (two passes): one button per top, or the highest alone.
- Which peak "first sight" is for: one picked from the peaks list, or the most prominent in range by default (T309).
- The arrival hour needs the route's clock (`2026-11-16-the-clock-time-at-each-point-of-a-route-and-the-sun-there.md`).
