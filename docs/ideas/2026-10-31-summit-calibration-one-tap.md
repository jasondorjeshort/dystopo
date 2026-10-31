---
date: 2026-10-31
kind: feature
related: [2026-10-25-live-velocity-readout-by-travel-mode.md]
---

# One tap at a summit or sign calibrates the altimeter

T160's elevation fuses GNSS, the barometer, the DEM and a station's pressure. Hikers calibrate altimeter watches at
known heights: a summit, a pass, a trailhead sign. The pack's `mountain_peak` layer has named heights, and T182 already
reads them. Add "I am at <name>" when the fix is within 100 m of a peak or a labelled saddle: one tap sets the
barometer's offset to the known height, marks the estimate as calibrated with the place and time, and the readout bar
shows a small mark until the offset has drifted past the calibration's own uncertainty. Also "I am at <height>" for a
sign with a number. The report says how long a calibration holds against the station prior. Open: whether a
calibration also feeds T178's storm trend (a better height, so a tighter interval) and how it ages.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.
