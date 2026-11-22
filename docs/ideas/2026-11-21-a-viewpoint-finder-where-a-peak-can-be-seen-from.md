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

2026-11-22, the owner at triage: "What can I see from here, and where can I see this from, are the same question
though. They're both just line of sight." The first half is therefore not a new search: it is the existing viewshed
(`Viewshed.compute`, "What can I see from here") run at whichever end is convenient, here the summit, and what is new
is only the result read as a list of places to go (mapped viewpoints, trailheads, trail and road stretches, nearest
first). The same holds for "where to stand" in `2026-11-21-when-the-sun-or-moon-stands-on-a-named-peak.md` and
`2026-11-21-a-mountains-shadow-forecast.md`: line of sight with a bearing and elevation filter from the sun or moon at a
time. Only the second half (ranking many candidate viewpoints by their skylines) is a different cost.

Question: with that correction, is the one new piece wanted: the viewshed's result (from your position, a long-pressed
point or a picked peak) also listed as places to go, nearest first, each routable? Recommended: yes, as a small pass on
the existing viewshed when you want it; the "best view" ranking waits for the sweep's time on a real phone.

Answer: yes, on the wave planner's timeline (the owner, 2026-11-22, by multiple choice at triage).
