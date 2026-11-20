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

Plan: a shared `nav/CompassHealth.kt` (pure Kotlin): from the magnetometer's accuracy status, the measured field
strength against `Wmm.field(...).totalNt` at the fix (outside 0.75 to 1.25 of the model reads "disturbed"), and, when
supplied, the heading against the GNSS course over a straight stretch, it gives a verdict (good, calibrate, disturbed)
with its figures. The app reads the magnetometer's uncalibrated field and accuracy in `app/nav/NavDevice.kt` (its
`onAccuracyChanged` already exists) and shows a "Check my compass" row and result beside Check my GPS in the Horizon
view's satellites section (`app/gnss/GnssCheck.kt`'s neighbour), with the figure-eight prompt when it says calibrate.
Debug: `POST /compass?accuracy=&fieldNt=` to inject, `GET /compass` for the verdict, in a `CompassRoutes.kt` with its
KDoc. Verify: unit tests per verdict; the emulator with injected values, frames of each verdict. New strings in the
report (ADR 0031). Out of scope: warnings outside the view (the heading button, the plot's north mark).
