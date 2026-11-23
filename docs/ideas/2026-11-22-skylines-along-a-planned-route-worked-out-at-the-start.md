---
date: 2026-11-22
kind: feature
related: [2026-11-21-the-horizon-view-moves-with-you.md]
---

# Skylines along a planned route, worked out when navigation starts

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

When a route is followed, sweep the far layer at points along it in the background at the start, so while driving the Horizon view reads kept skylines and only the near layer is swept again. It fits the research's three tiers (the far layer changes slowly) and is the same store the route scrub would read.

See also: `../findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`.

**What exists already (to be checked):** The Horizon view keeps horizons within 100 m (`Gnss.HORIZON_MOVE_M`); routes and navigation exist; no pre-sweep.

Plan: after the moving horizon's tiered smoothing (answer (a) of `2026-11-21-the-horizon-view-moves-with-you.md`, with
the far layer stored as range and height per azimuth) lands, one pass. When navigation starts (`app/nav/NavSession.kt`),
a background job sweeps the far layer every 2 km along the route (low priority, one sweep at a time, stopped with the
session) into a store keyed by distance along the route; `Gnss.horizonAt` reads the nearest kept far layer, moved to the
fix as the research's tiers say, and sweeps only the near layer. A debug route reports the store (samples, sweep ms,
bytes, hits). Verify: unit test of the store's lookup; on the emulator, the `paradise-panorama-hike` route replayed with
the far sweeps counted (one per sample at the start, none while moving) and the skyline within 0.3 degrees of a fresh
full sweep at three points; the battery scenario counted. Out of scope: the route scrub (it comes with the one clock).
