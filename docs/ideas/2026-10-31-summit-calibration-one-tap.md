---
date: 2026-10-31
kind: feature
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

Plan: one task. In `shared`: `ElevationFilter` (`readout/ElevationFilter.kt`) gains a known-height update (a height
measurement of 1-sigma 3 m for a tagged summit, 5 m for a saddle, pass or typed sign, through its scalar `update`; with
the barometer fused it pins the offset, as `prior` does from a station but tighter) and `ReadoutEstimator`
(`readout/Readout.kt`) a `calibrate(name, eleM, sigmaM, tMs)` whose `BarometerStatus` carries `calibratedAt`,
`calibratedName` and `calibratedSigmaM`; the mark holds while `offsetSigmaM` stays under twice the calibration's sigma
(the offset's own 5 m per root hour random walk ages it, so no separate timer). The candidates: `Viewshed.readPeaks`
(`dem/Viewshed.kt`) within 100 m of the fix, widened from `PEAK_CLASSES` to the layer's `saddle` and `pass` classes
(`tilebuild/PROFILE.md` › `mountain_peak`), named and with an `ele` only; the DEM check `Viewshed.peaks` applies to a tag
150 m off the DEM rejects the tag. Phone: the map's long-press menu (`app/tracking/LongPressMenu.kt`) shows "I am at
<name>, <height>" when the press is on such a feature and the fix is within 100 m of it; Readout settings
(`app/readout/ReadoutSettings.kt`) get "Set my height now" for a sign; the readout bar (`app/readout/ReadoutStrip.kt`)
draws a small mark beside the elevation while calibrated; `GET /readout` reports the calibration and a debug
`POST /readout/calibrate` (in `app/controls/ScaleBarRoutes.kt` with its KDoc) drives it. Survey (ADR 0039): how
altimeter watches and phone apps offer a known-height calibration and say it has aged; no setting (an action). Done:
`ReadoutSim` replays (`shared/src/commonTest`) where a calibration at a summit pulls a 40 m offset in at once and the
mark clears as sigma grows; `devtool readout --calibrate <ele>@<time>` reports how long it holds against the station
prior (the idea's figure); a device screenshot at Paradise with the menu item. Out of scope: feeding T178's trend (it
reduces pressure with the DEM height at the fix, `HeightSource`, not the barometer's height; a later question).
