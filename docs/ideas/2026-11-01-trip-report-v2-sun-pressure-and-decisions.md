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

2026-11-02 (triage): of the four, two are recomputable from the track and the DEM at print time (the sun
and shade stretches through `ShadeAlong`; earlier walks through `PastEfforts`, which exists and is not
wired into the report; the water count through `WaterAlong`), and two exist only live: the pressure trend
in `PressureWatch`'s `pressure-trend.csv` (per device, 3 h 40 min kept, overwritten by the next recording)
and the turn events in `NavSession` memory. Storing those with the track changes the GPX extension that
sync and the browser read (ADR 0033).

Question: should the pressure minutes and the turn events be stored with the track (and sync), or is a
report with the recomputed strips enough? Recommendation: one pass now for the recomputed items (the
sun and shade strip, "Walked before", the water count; no format change), and a proposal for the stored
two afterwards, with the retention question (3 h 40 min is shorter than a long day) settled there.
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
