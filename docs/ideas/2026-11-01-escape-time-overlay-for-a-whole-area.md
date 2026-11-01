---
date: 2026-11-01
kind: feature
---

# An escape-time overlay: minutes to non-exposed ground, for a whole area

T196's `nav/WayDown.kt` answers, from one point, how long it takes to reach ground 100 m lower and off the ridge. Precompute that over a pack's DEM as a derived layer (like the shade tiles) and draw it as an overlay, so the way down is known before a warning fires and a route can be judged by its worst stretch. Uses T195's `RidgeRule` and the clock's pace.

## Open

- Cost of one pass per pack and whether it ships as derived data (the horizon-and-shade-as-derived-data idea) or is computed on the phone.
- Walking time across country is not trail time: pace model off trail, and the 5 km window.

Recorded 2026-11-01 at the close of wave 24 from the coordinator's list, moonshots included at the owner's invitation. Untriaged, and not even sanity checked: the premises below were not read against the code. Web delivery (the browser demo at `tools/demo/`, `devtool serve`) is an open question for every idea here; no Kotlin web site exists in the repository today.
