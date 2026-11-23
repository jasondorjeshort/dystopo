---
date: 2026-11-22
kind: feature
related: [2026-11-21-stars-and-planets-over-the-named-skyline.md, 2026-10-29-group-positions-without-signal.md]
---

# The star cutoff each user settles on, as a sky-quality reading (moonshot)

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

Where users stop pressing "more" is a measure of how dark their sky is. With consent it could be shared as a sky-quality map, as citizen-science sky surveys do. Far out: a new data flow (flagged, ADR 0031) and a server side.

**What exists already (to be checked):** Nothing; the star layer waits on the owner's ask.

Question: sharing each user's settled star cutoff as a sky-quality map is a new data flow off the phone (flagged under
ADR 0031) and needs a server; is it wanted at all?
Recommendation: not now: the star layer it reads does not exist, and the readings would be few and noisy (screen
brightness, eyesight, what a user takes the cutoff to mean); leave it until the star layer has users.
Options:
A. Not now; keep it at rest until the star layer ships and has users (recommended)
B. A proposal for the data flow and the server now
C. Not worth building; drop it

Answer:
