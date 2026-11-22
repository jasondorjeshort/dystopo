---
date: 2026-11-21
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md, 2026-11-21-a-mountains-shadow-forecast.md, 2026-11-21-the-view-from-a-routes-high-point.md]
---

# A viewpoint finder: where a peak can be seen from, and where the most peaks are in view

Moonshot. Two searches over the offline map:

- **"Where can I see it from?"** For a chosen peak, the places it is visible from. Sight lines run both ways, so the
  visible area computed from the summit (`Viewshed.compute`, T182, already reachable by "What can I see from here" on
  the summit) is close to the answer. What is missing is the question asked that way and a list a person can use: the
  trails, roads, trailheads and mapped viewpoints inside that area (the pack's `viewpoint` class, `tilebuild/PROFILE.md`),
  nearest first, each a place to route to.
- **"Where is the best view?"** Around a point or along a route, the candidates ranked by how many named peaks, or how
  much prominence (T309's ranking), stand on their skyline. A skyline per candidate is the cost, so the candidates are
  few: mapped viewpoints, trail high points and passes, a route's samples.

The shadow forecast's "where to stand" and the view from a route's high point are single cases of the same search.

Recorded 2026-11-21 from the coordinator's list after wave 44, on the owner's "we can record them all". Untriaged and
not sanity checked.

## Open

- The cost of the second search on a phone (a sweep per candidate; T317's report has the time per point), and its cap.
- The elevation model knows no trees or buildings: a "viewpoint" in forest is wrong, and mapped viewpoints are the
  only evidence of an open view the map holds.
- The eye stands 2 m above the ground and the summit does not, so the area from the summit is a little generous; one
  ray back from each listed place settles it.
- Whether the result is a list, a tint on the map like the visible-area overlay, or both.

Question: is the viewpoint finder wanted, and if so which half first? Recommended: yes to the first half only, as one
pass when you want it: on a peak (the long-press card on it, a picked peak in the sky views) "Where can I see it
from?" runs `Viewshed.compute` from the summit and lists the map's viewpoints, trailheads and stretches of trail or
road inside that area, nearest first, one ray back from each to confirm, each a place to route to. The second half
("the best view", a skyline per candidate) waits until the sweep's time per point on a real phone is known. The
elevation model knows no trees, so both would say "by the terrain", and mapped viewpoints are the only evidence of
an open view the map holds.

Answer:
