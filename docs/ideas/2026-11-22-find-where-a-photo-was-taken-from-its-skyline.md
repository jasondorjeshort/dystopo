---
date: 2026-11-22
kind: feature
related: [2026-11-21-peak-names-for-your-old-photos.md, 2026-11-02-horizon-match-fix-from-the-camera.md]
---

# Find where a photo was taken from its skyline alone (moonshot)

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

A photo with no location in it: trace its ridgeline and search skylines worked out across a region for the match, giving the place and heading. The best-view ranking's machinery run backwards (a skyline per candidate), with the candidates narrowed by the user ("somewhere around here").

**What exists already (to be checked):** Skylines per point exist; matching does not (`2026-11-02-horizon-match-fix-from-the-camera.md` is the nearest).

Question: fold this into the horizon-match idea, where the same search on a still photo was recorded on 2026-11-21 as its output (2), rather than keep a file of its own?
Recommendation: fold it in as a `Came up again:` entry there and delete this file; it is the same skyline match with a wider search area, and that idea's measurement pass (answered, 2026-11-11) is where its error and the search bound would first be measured. Nothing matches skylines in the code today (`horizon/LayeredSkyline.kt` sweeps, nothing scores).
Options:
a) Fold into `2026-11-02-horizon-match-fix-from-the-camera.md` (recommended).
b) Keep it separate, at rest.
c) Not worth building.

Answer:
