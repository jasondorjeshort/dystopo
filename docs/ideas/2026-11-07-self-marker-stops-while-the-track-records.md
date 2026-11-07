---
date: 2026-11-07
kind: bug
related: [2026-11-07-climb-reads-zero-when-the-data-is-contradictory.md]
---

# The self marker stops moving while the track keeps recording, and elevation and gain hide for minutes

The owner, on a flight on 2026-11-07 (v0.1.22-alpha, airplane mode, no map downloaded beforehand): "We aren't
always tracking the current location. The blue self marker isn't moving but the track is recording. And elevation
and gain aren't showing. Then after a few minutes they start again. What would cause that?" The screenshot shows the
recorded line reaching well past the frozen puck.

What the code suggests, unchecked: the puck is MapLibre's location component fed by `MapController`'s own
listener (`androidApp/.../app/MapController.kt`, `startDeviceLocation`, `onFix`), the recorder by its own source
(`tracking/LocationSource.kt`, the `dystopo-tracks` worker), so one can stall while the other runs; and the
readout's elevation is gated when the barometer and GNSS disagree (`readout/LiveReadout.kt`, `shared`'s
`readout/Altimeter.kt`, T173 and T197's rules), which a pressurised cabin guarantees: the barometer reads the cabin's
pressure altitude, thousands of feet under the GNSS height. The "few minutes" would be the gate's window.

## Open

- Reproduce with a replay of the flight's track and a fixed cabin pressure (`POST /location`, the barometer's
  injected pressure), reading `GET /readout` and the puck's position each second.
- Whether the puck should follow the recorder's fixes rather than its own listener while a recording runs.
- A flying activity's rule for the barometer: off, GNSS only. The owner had set the activity to flying by hand
  in the track and the barometer still fed the estimate ("maybe the algorithm tried to do better until the cabin
  was pressurized"); the sibling idea on climb carries the rule.
