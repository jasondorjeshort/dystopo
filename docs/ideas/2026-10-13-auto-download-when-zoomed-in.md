---
date: 2026-10-13
kind: feature
related: [2026-10-13-download-by-region.md]
---

# Online use should download automatically when zoomed in on an area

If the user dwells zoomed in on an area, fetch and keep topo (elevation and contours) and map
(vector) data for it without being asked; the same rule for maps and for topo. Online and offline
should blend without the user noticing.

Today: ADR 0025 principle 1 (everything on the phone is a cache in front of a source), the online
tile cache, and T75: pinned areas, corridor downloads, ahead-on-the-route prefetch and box
downloads the user starts by hand. Nothing starts a download from how the map is being viewed.

## Open

- The trigger: zoom threshold plus dwell time, or repeated visits. Whether the result is cache
  (evictable) or a pinned area, and when a cache region is promoted to a pinned one.
- Data and battery guards: ADR 0023 says no Wi-Fi gates, so the steer is visible limits (a budget
  per day or per area, shown to the user), plus the low-battery mode.
- Size: how big an area one dwell fetches; uses the area manifest (ADR 0027) so it is resumable.
- Routes: a route (planned or navigated) downloads a corridor around itself automatically, to a
  configurable distance. T75 prefetches ahead on a route being navigated; this makes it the
  default for saved and planned routes too, with the distance a setting.
- Feedback in the UI: a small indicator that an area is being kept, and a way to unpin.

Question: which part first, and with what limits shown? The idea bundles two pieces: a corridor kept automatically around every saved or planned route (T75 already prefetches ahead while navigating; this makes it the default, with the distance a setting), and a box kept after the user dwells zoomed in (trigger, size, cache or pin, and an indicator are all open). Recommendation: the route corridor first as one pass once you name the default distance and whether it counts against the pinned-area budget, since it reuses ADR 0027's manifest and T75's corridor download unchanged; the dwell rule after it as a short proposal, because its trigger, its daily limit (ADR 0023: a visible limit, no Wi-Fi gate) and its indicator are what you see and touch.
Answer:
