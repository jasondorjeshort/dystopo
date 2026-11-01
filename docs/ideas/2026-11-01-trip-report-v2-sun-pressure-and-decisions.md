---
date: 2026-11-01
kind: feature
---

# Trip report v2: the sun and pressure graphs and the day's decisions

T198's trip report page left out the sun and pressure graphs, earlier walks of the same trail, the water count and the turn-back events because the track does not store them. Record the pressure trend minutes, the sun and shade stretches and the turn events with each track, then add them to `render/TripReport.kt`'s margin and a graph strip.

## Open

- Storage: with the track, or beside it, and what syncs between phones.
- The pressure store lives in `PressureCard` and the turn events only in `NavSession` today.

Recorded 2026-11-01 at the close of wave 24 from the coordinator's list, moonshots included at the owner's invitation. Untriaged, and not even sanity checked: the premises below were not read against the code. Web delivery (the browser demo at `tools/demo/`, `devtool serve`) is an open question for every idea here; no Kotlin web site exists in the repository today.
