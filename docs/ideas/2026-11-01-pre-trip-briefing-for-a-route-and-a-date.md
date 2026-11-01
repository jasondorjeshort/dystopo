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
