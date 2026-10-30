---
date: 2026-10-30
kind: feature
---

# A storm warning from the barometer's trend

The readout (`androidApp/.../app/readout/LiveReadout.kt`) reads the barometer while shown, and T173's
`PressureSource.kt` gives it the nearest station's sea-level pressure, which separates altitude from
weather. T160 left "a pressure change with no movement as weather" as a follow-up. Build the classic
altimeter-watch feature on it: a three-hour pressure tendency corrected for the phone's own climb,
shown as a small trend arrow in the strip and, past a threshold (the usual 3 hPa in 3 h), one quiet
card and one spoken line, "Pressure falling fast". Open: the barometer is read only while the strip
is shown, so a trend needs a low-rate background sample while recording (the battery doctrine idea);
the threshold and the wording want the benchmark pass (ADR 0039).

Plan: one task, after the readout bar task (both edit `readout/ReadoutStrip.kt` and `StripText.kt`). What exists:
`readout/Barometer.kt` samples at 1 Hz only while the strip shows in the foreground (`LiveReadout.recompute`'s `wanted`). T160's
`ElevationFilter` carries a barometer offset (pressure altitude minus true height) that absorbs the weather but is pulled
toward GNSS and the DEM, so a walker standing still under a falling barometer reads a false climb (T160's follow-up).
`PressureSource` (T173) gives a station's sea-level pressure when online. New in `shared/.../readout/PressureTrend.kt` (pure,
unit-tested): one sample a minute over 3.5 h. Each sample is converted to its sea-level equivalent at a reference height (the
inverse of `Altimeter.heightM`). The reference height is frozen while the phone is still (under 50 m of horizontal movement in 10 min), so all change counts as weather.
While moving, the reference follows the fused height (else the GNSS or DEM height), so a climb does not read as a falling barometer. Outputs:
- the 3 h tendency in hPa and its class (rising, steady, falling, falling fast)
- a confidence: the share of the window spent moving, and the height's sigma in hPa
- an alert when the fall passes the threshold.
Sampling: `Barometer.want` takes reasons (`readout`, `trend`). The trend wants a sample every 60 s, batched as deep as the FIFO allows, while a recording runs (T159's foreground service) and not otherwise:
a trend exists on recorded outings, at the cost of one wake per batch. The device check confirms samples arrive with the
screen off. Shown: an arrow beside the elevation in the strip once an hour of samples exists. Past the threshold, one quiet
dismissable card per fall, and one spoken line, "Pressure falling fast", through a `NavSpeech` line like `sayTurnAround` under the
same setting. New strings go in the report (ADR 0031). Settings (ADR 0044): the alert on or off (default on) and the threshold
(default from the survey: the usual 3 hPa in 3 h, against what altimeter watches use; they commonly let the user switch the
storm alarm, which is why this is a setting). Synced keys, rows and help text, the help saying a phone barometer gives a
trend, not a forecast. Debug: `GET /readout` › `trend` (samples, tendency, class, reference mode, alert). `POST /readout/trend`
feeds a synthetic series. Devtool: `devtool readout --pressure <csv>` reports `trend`, with fixtures for:
- a still phone under a 4 hPa fall in 3 h (alert)
- a 600 m climb in 2 h in steady weather (no alert)
- both together.
Out of scope: feeding the weather back into the elevation filter (a tuning pass on the hike's real logs);
peers as a pressure source (the barometer-network idea); any forecast.
