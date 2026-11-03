---
date: 2026-11-01
kind: feature
related: [2026-10-30-safe-return-planner.md]
---

# A pre-trip briefing: one page for a route and a date

Pick a route and a date; one page gives the terrain sunset at the end, the shade stretches, the steep ground and ridge time, the water, the way-down times and the hot-day temperature at the walker's height, all from figures that exist since wave 24 (`ShadeAlong`, `ExposureAlong`, `WayDown`, `WaterAlong`, `Deadline.resolve`). The layout would extend T198's page and T183's `PageLayout`; a single "plan for this day" action on the route card. A moonshot, and the natural fit for a web view as well as the phone.

## Open

- Weather inputs offline: only the station temperature the app already fetches.
- Phone sheet, web page, or both, and whether it needs a web front end beyond `tools/demo/`.

Recorded 2026-11-01 at the close of wave 24 from the coordinator's list, moonshots included at the owner's invitation. Untriaged, and not even sanity checked: the premises below were not read against the code. Web delivery (the browser demo at `tools/demo/`, `devtool serve`) is an open question for every idea here; no Kotlin web site exists in the repository today.

2026-11-02 (triage): each figure exists as a devtool command and a shared function, and `ShadeAlong`
takes a start instant, so a future date works for the shade and the sunset deadline (a terrain day for
that date). `WayDown` is per ridge point along a line, so a route's way-down times mean running it at each
ridge step of `ExposureAlong`. The hot-day temperature is the live station reading plus a lapse rate and
has no future-date form offline. `TripReport` and `PageLayout` are built for a recorded track.

Question: a phone sheet (a page beside the trip report, a "Plan for this day" action with a date picker
on the route card, printed through the sheet dialog), a web page, or both? Recommendation: the phone sheet
and the devtool option first, two passes after the best-start-time idea lands (its sweep gives the start
the briefing assumes), without the hot-day line for a future date; the web page when the site has
`/print` (the print-from-the-site idea).
Answer:
