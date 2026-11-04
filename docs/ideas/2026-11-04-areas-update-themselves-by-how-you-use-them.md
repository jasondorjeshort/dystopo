---
date: 2026-11-04
kind: feature
related: [2026-11-02-pack-updates-as-deltas.md, 2026-10-13-auto-download-when-zoomed-in.md]
---

# Downloaded areas refresh themselves, soonest where you actually go

Today the phone notices that a downloaded area is older than its source on its own (the version check in
`androidApp/.../online/OnlineFetcher.kt`, `maybeCheckVersions`) and marks the card "Older than the source", but
nothing fetches the newer data until the user taps Update on that card (`areas/AreasScreen.kt`, `AreaDownload.start`).
The owner wants automatic updates in some form, and finds the usual all-at-once, irregular whole-map update
(the mainstream navigation app they named) the thing to avoid.

The shape the owner described: places never visited do not matter, so a smarter policy than "update everything" is
welcome; but on the daily drive or a hike the newer data should be pulled basically as soon as it is published.
So priority follows use: the areas and routes in regular use (a saved or active route, the commute, a trip's
corridor, recently viewed places) update promptly and in small pieces; the rest wait, update rarely, or only when
asked.

## Open

- The use signal: recent views and dwells (the dwell box's recent-places store, proposal 0055), saved and planned
  routes and trip corridors, navigation history, or an explicit "keep fresh" mark.
- The trigger: on the version check noticing a change, on a schedule (WorkManager), on opening the area, or on
  starting a route. Whether a hike's start counts as "pull before I lose signal".
- The unit: whole area, per source (`dem`, `osm`), or per tile (the deltas idea). Updating only the used part of
  an area against a half-updated area that is internally inconsistent.
- Limits: ADR 0023 forbids Wi-Fi gates, so the steer is a visible daily data limit and the battery mode, as for
  the dwell box. A setting for the policy (off, used places only, everything), with the researched default.
- Feedback: how the card says "refreshed today" or "waiting", and a way to see what was fetched and how much.
- Cost on the server side: how often upstream data really changes (OpenFreeMap builds, the owner's pack rebuilds),
  which bounds how much an eager policy would fetch.

2026-11-04: recorded from the owner's words in conversation; a read-only research agent on how the established
apps schedule and prioritise map updates is next (`benchmark.md`, ADR 0039 and ADR 0044). Untriaged.
