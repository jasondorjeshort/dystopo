---
date: 2026-11-22
kind: feature
related: [2026-11-21-a-viewpoint-finder-where-a-peak-can-be-seen-from.md, 2026-11-21-when-the-sun-or-moon-stands-on-a-named-peak.md, 2026-11-21-a-mountains-shadow-forecast.md]
---

# One line-of-sight function in `shared`, called by every visibility feature

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

The owner's correction of 2026-11-22: "what can I see from here" and "where can I see this from" are the same question, and "where to stand for moonrise over a peak" is the same line of sight with a bearing filter. Make that structural: one API in `shared` with three calls (between two points; everything visible from a point; visible from a point toward a bearing and elevation band), the eye height a parameter, and the viewshed, "View from here", the viewshed's list of places and every "where to stand" calling it, so no idea or task describes a new search again.

**What exists already (to be checked):** `Viewshed.compute` (T182), `LayeredSkyline.sweep`, the Horizon view's rays; each feature calls its own entry.
