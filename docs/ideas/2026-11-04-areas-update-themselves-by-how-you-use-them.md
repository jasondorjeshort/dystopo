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

2026-11-04: recorded from the owner's words in conversation. Untriaged.

## First survey (2026-11-04, web-only, read-only; thin in places)

- No app found weights updates by use. Google's offline maps refresh in the background on Wi-Fi once an area is
  within 15 days of expiry (expiry age is the only signal; toggle for auto-update and for Wi-Fi only; help page
  support.google.com/maps/answer/6291838). Mapy.cz updates automatically while charging, overnight, with a
  Wi-Fi-only switch (one secondary article; paid tier). OsmAnd Live pulls small patches hourly, daily or weekly
  for the maps the user picks (the only delta approach found; secondary sources). Organic Maps has "update all"
  and plans automatic updates only with incremental updates (issue #12841); Locus Map and Gaia GPS update by
  hand. HERE and Sygic Professional alert and offer update-all, with a network choice.
- MapFactor Navigator's actual mechanism was not established from any public source; "all-at-once, irregular" is
  the owner's observation. Not established either: Apple Maps, Komoot, AllTrails, CalTopo, Avenza, Maps.me,
  OpenFreeMap's versioning, OsmAnd's Wi-Fi option.
- Technical: Android WorkManager periodic work has a 15-minute minimum and constraints for network type, battery
  not low, charging, idle and storage not low; a run can be delayed while constraints are unmet. Protomaps
  rebuilds its planet file daily and keeps a week; no official PMTiles diff format exists.
- The survey's recommendation, for the owner to weigh: a user setting (Off, Places I use as the default, All
  downloaded areas) with a visible daily data limit in place of any Wi-Fi gate (ADR 0023); a daily background job
  plus a check on app open and on opening a route or saved area; priority: the area of the route or place being
  started or open now (immediately), saved routes and favourites, recently viewed places (the dwell box), pinned
  areas within their budget, and never-visited areas on manual Update only; whole-area updates now, per-tile
  deltas later; quiet feedback such as "Updated automatically: 3 places, 42 MB today" and "Update waiting: daily
  data limit reached".

The owner wants a fuller research pass on this later (more apps established from primary sources, the real
update cadence of the upstream data, battery and data cost of the polling, how use-weighting could be measured);
this survey is the starting point, not the answer.
