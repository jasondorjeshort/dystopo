---
date: 2026-11-19
kind: feature
---

# "Check my compass" beside "Check my GPS"

The GPS view's sky plot follows the phone's heading by default, and the readout's course and the map's heading
mode lean on the magnetometer too. Android reports the magnetometer's calibration as a sensor accuracy
(`SensorManager.SENSOR_STATUS_*` in `onAccuracyChanged`) and the field strength, which near metal or a magnet
leaves the Earth's 25 to 65 µT range (the WMM's figure at the fix, `nav/Wmm.kt`). A check on the GPS view's screen:
the accuracy, the field against the model's, the heading against the GNSS course while walking straight, and the
figure-eight prompt when it is unreliable. Agent-testable through a debug route that injects sensor values.

## Open

- Where the warning shows outside the GPS view (the heading mode's button, the plot's north mark).
