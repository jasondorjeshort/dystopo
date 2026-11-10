---
date: 2026-11-07
kind: feature
related: [2026-10-30-pace-and-climb-zones-with-a-tone.md, 2026-11-02-physical-limits-from-your-own-tracks.md]
---

# The accelerometer as a third source for climb rate, and as a tell for car, plane, bike or foot

The owner, 2026-11-07, after the flight: "the accelerometer can help us measure climb rate. Perhaps notably with
jerk which would trigger a change in its estimated accuracy. This could also help us differentiate car from plane
from bike from hike. Though the algorithm might be hard and need some recording for comparison. Would need research."

Today climb comes from the elevation estimate alone (`shared`'s `readout/Altimeter.kt` and `ElevationFilter.kt`,
fed by the barometer and GNSS; `androidApp/.../app/readout/LiveReadout.kt`), and the activity from T158's detection
and the picker (`tracking/ActivityPicker.kt`). Both are blind to the phone's own motion: a step's bounce, a car's
vibration, a plane's steady hum, a bike's cadence. Vertical acceleration integrated twice drifts within seconds, so
the accelerometer cannot give height on its own; what it can give is the second derivative's timing (a climb rate's
change, jerk) to adjust the filter's trust in each source, and a motion signature per activity. The owner's
flight notes name a cabin barometer that should have been ignored once the activity was flying; a motion signature
would have recognised the plane before the cabin pressurised.

The owner's own second thought, minutes later: "Maybe not. Phones get moved around a lot." A phone in a hand, a
pocket or a cup holder moves with its carrier, not with the vehicle, so the signal is the carrier's handling as much
as the ride; the research question below has to answer that first, and this idea stands or falls on it.

## Open

- Research first (ADR 0044, technical variant): published practice for activity recognition from accelerometer
  signatures (Android's own activity recognition API included, which T203 already weighs for its permission), and
  for fusing accelerometer jerk into a barometer-plus-GNSS altitude filter; what phones' sensors give at the rates a
  background recording can afford.
- Recordings for comparison: a few of the owner's own tracks with the raw accelerometer logged beside the fixes
  (a debug route to log it; the replay idea's export), on foot, by bike, by car and in the air.
- Power: the sensor's cost over a long recording against the battery idea.

2026-11-10 (triage): T250 took the case that started this (the barometer off in the air: Barometer Automatic keys on
a flying activity, then on a disagreement with GNSS); its follow-ups name a flying detector and the pressure-rate
tripwire, which are the cheaper paths to the same end.

Question: Should a read-only research pass (ADR 0044, technical variant) settle whether a phone's accelerometer can
tell the vehicle from the carrier's handling, before anything else, or is this idea dropped after your second
thought? Recommended: the research pass only, one no-Gradle agent writing a finding; build nothing unless it shows a
published method that survives a phone in a pocket or cup holder, since T250's flying rule already covers the case
that prompted it.
Answer:
