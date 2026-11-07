---
date: 2026-11-07
kind: bug
related: [2026-11-07-self-marker-stops-while-the-track-records.md]
---

# Climb reads 0 for long stretches in the air, even while descending; show "?" or a dash instead

The owner, on a flight on 2026-11-07: "climb was interesting at times but just showed 0 for a long time during
other periods, even while we were descending. A ? or - here might be more useful if the data is insufficient or
contradictory."

The climb entry (`androidApp/.../app/readout/ReadoutStrip.kt`, `readout/LiveReadout.kt`, `shared`'s
`readout/Altimeter.kt` and `ElevationFilter.kt`) is derived from the elevation estimate; T161's follow-up already
says climb is hidden most of the time in the air on GNSS alone. In a pressurised cabin the barometer and GNSS
disagree by thousands of feet, so the estimate's confidence is low and the climb falls back to 0, which reads as
"level". A value the app does not know should not read as a number.

## Open

- Three states for the entry: a figure, "?" when the data is insufficient, a dash when the sources contradict
  (the owner's words), with the rule stated in the help text.
- The flying activity (T158) choosing GNSS outright over a cabin barometer for both elevation and climb.
- A replay test with a fixed cabin pressure and a descending GNSS profile reading a negative climb.
