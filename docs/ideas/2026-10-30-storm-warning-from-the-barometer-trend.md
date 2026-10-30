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
