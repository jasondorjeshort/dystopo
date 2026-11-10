---
date: 2026-11-07
kind: feature
---

# The readout shows the barometer's pressure when the phone has one, even when it is not used

The owner, after a third flight on 2026-11-07 (v0.1.22-alpha): "Maybe I'm wrong and this should be a setting. But I
feel like when pressure is available it should be shown at the bottom alongside elevation etc, even if not used. We
could fit a verbose mode there with... I have no idea if anything else is useful."

The readout strip (`androidApp/.../app/readout/ReadoutStrip.kt`, `readout/LiveReadout.kt`; the pressure trend
card of T178) uses the barometer for the elevation estimate and shows the pressure only in the trend card and the
warning. A pilot, a forecaster or anyone watching weather reads the raw figure; showing it beside the elevation
also tells the user which source the elevation rests on, which the flight reports asked for.

## Open

- A pressure entry in the strip (hPa or inHg by the units setting), with the trend arrow T178 computes, shown
  whenever the sensor reports, dropped first when the strip lacks room (T177's width rule).
- Whether a "verbose" strip is a setting (ADR 0044: a survey of what the established apps' readout bars offer
  beyond speed, elevation and course; the owner leans to a setting but is unsure), and what else belongs in it
  (the GNSS accuracy, the fix age, the source words).
