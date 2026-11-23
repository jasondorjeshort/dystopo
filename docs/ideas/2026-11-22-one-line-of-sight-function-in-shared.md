---
date: 2026-11-22
kind: feature
related: [2026-11-21-a-viewpoint-finder-where-a-peak-can-be-seen-from.md, 2026-11-21-when-the-sun-or-moon-stands-on-a-named-peak.md, 2026-11-21-a-mountains-shadow-forecast.md]
---

# One line-of-sight function in `shared`, called by every visibility feature

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

The owner's correction of 2026-11-22: "what can I see from here" and "where can I see this from" are the same question, and "where to stand for moonrise over a peak" is the same line of sight with a bearing filter. Make that structural: one API in `shared` with three calls (between two points; everything visible from a point; visible from a point toward a bearing and elevation band), the eye height a parameter, and the viewshed, "View from here", the viewshed's list of places and every "where to stand" calling it, so no idea or task describes a new search again.

**What exists already (to be checked):** `Viewshed.compute` (T182), `LayeredSkyline.sweep`, the Horizon view's rays; each feature calls its own entry.

Plan: one pass, a facade over what exists, no new search. Checked at triage: the entries are `SightLine.of` (T334,
`horizon/SightLine.kt`, viewer to one place), `Viewshed.compute` and `Viewshed.peaks` (T182, `dem/Viewshed.kt`, everything
from a point) and `Horizon.sweep`/`LayeredSkyline.sweep` (profiles beneath both, left as they are). Add
`shared/.../horizon/LineOfSight.kt` with three calls: `between(a, b, eyeA, eyeB)` (wraps `SightLine.of`, gaining a height
above ground at the far end), `from(point, eye, range)` (wraps `Viewshed.plan`/`compute`/`peaks`) and `toward(target,
bearingBand, angleBand, eye)` (the viewshed from the target by reciprocity, eye heights swapped, cells kept whose bearing
to the target and the target's apparent angle fall in the bands). Move the callers (`app/tracking/LongPressMenu.kt`,
`app/viewshed/ViewshedSession.kt`, `devtool/.../ViewshedCommand.kt`) onto it; add `devtool sight --from --to [--eye-m]
[--to-eye-m]` (JSON: the `Sight`'s figures). Verify: unit tests in `shared` (synthetic ridge: `between` agrees with `from`'s
cell state both ways within a cell; `toward` keeps only the band), `ViewshedTest`, `viewshed --area rainier` PNG unchanged
against `main`, `sight` from Paradise to the summit and to a hidden point, the long-press line on the emulator unchanged.
Docs: `devtool-commands.md`, a note under ADR 0064. Out of scope: any new feature calling `toward`; route-wide sampling.
