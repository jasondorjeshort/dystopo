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

On the second flight the same day the owner gave the rule a shape: "Barometer should have three modes: auto,
always used, never used. Always used may make sense in a hurricane and never used... I dunno... but in auto we
should immediately fall back to not using it rather than go inconsistent. This is just my guess though; a research
agent should look into it more."

After a third flight: "it does seem to be elevation change breaking the tracking. Once we hit inconsistency in
barometer we should just stop using it for that time." The auto mode's rule, in the owner's words: on a
disagreement, the barometer is out until the sources agree again, not blended.

## Open

- A barometer setting with three values, auto, always and never (ADR 0044: the research agent on how altimeter
  apps and watches expose the barometer, and what "auto" should key on: the activity, the disagreement with GNSS,
  the pressure's rate of change), with auto falling back to GNSS at once on a disagreement rather than blending.
- Three states for the entry: a figure, "?" when the data is insufficient, a dash when the sources contradict
  (the owner's words), with the rule stated in the help text.
- The flying activity choosing GNSS outright over a cabin barometer for both elevation and climb. The owner, the
  same day: "Barometer is not useful in flight mode; that's why it is important to set or recognize that. I did set
  it in the track but maybe the algorithm tried to do better until the cabin was pressurized." So the activity was
  set by hand (`tracking/ActivityPicker.kt`, T172) and the barometer still fed the estimate: the rule is that a
  flying activity, set or detected (T158), turns the barometer off for the readout and the track, not merely down.
- A replay test with a fixed cabin pressure and a descending GNSS profile reading a negative climb.
