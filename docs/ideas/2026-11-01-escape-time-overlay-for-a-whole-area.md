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

2026-11-02 (triage): `WayDown.find` needs a line to walk (the way back and ahead), runs only when the point
is itself a ridge, and searches 5 km each way at up to 200 samples by 17 reads a point; `RidgeRule` is in
`routing/ExposureAlong.kt`. There is no "way down from a point in any direction", so an area layer is a
new algorithm (an off-trail walk over the DEM with its own pace model), not a precompute of what exists.

Question: is a whole-area escape layer wanted enough to design it (a proposal: the off-trail time model,
the 100 m drop and 5 km window as constants or not, derived data in the pack format or computed on the
phone, and how "minutes to lower ground" is worded without saying safe)? Recommendation: not now; the
point-wise answer on the pressure warning covers the case the idea names, and the layer's cost over a
pack's DEM is millions of reads before anyone has asked for it.
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
