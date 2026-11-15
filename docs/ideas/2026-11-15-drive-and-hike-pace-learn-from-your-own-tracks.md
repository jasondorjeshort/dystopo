---
date: 2026-11-15
kind: feature
related: [2026-11-02-physical-limits-from-your-own-tracks.md, 2026-11-15-your-own-metric-sliders.md]
---

# The pace learns from your own tracks: hike and drive

The app records tracks and already fits a pace to them on the desktop (devtool `PaceFitCommand.kt`); a hike card
shows past effort. The drive's turn seconds and class speeds (`routing/TurnCost.kt`, `routing/Router.kt`) are first
values nobody measured. Wanted, on the phone and private to it: the hike model's parameters and the drive's speeds
and turn seconds fitted to what you actually did, shown as "based on your last N trips" with the fitted figures,
and used for planned times. The owner, 2026-11-15: having the pace learn from you would be good.

## Open

- Hike first (the owner's interest; the fit exists) or drive.
- Whether the fitted values sync between your phones (they describe you, not the phone).
- A floor on how much data before it replaces the default, and a way back to the default.

Question: the hike half is built; is it what you meant, and is the drive half wanted? Found 2026-11-15: since T164
and T184 the phone fits a speed-by-grade curve to your newest 30 hikes and rides (`routing/PaceFit.kt`,
`app/routing/PaceSettings.kt`), ready at 5 tracks over 20 km on mapped trails, shown in Settings, Navigation and
tracking, Route times ("Your pace from your last N hikes: X min/km on the flat, Y m an hour climbing", with Use the
standard estimate and Reset) and on the card ("Based on your last N hikes", and a range); it syncs, the last phone
to refit winning. If that never showed for you, the bug is there and worth a finding. Not built: any drive fit.
The survey found no app that learns a driver's own speeds; where apps personalise at all it is a recent average or
a hand-set level. Recommended for the drive: a measurement first and nothing user-visible, a debug route that
matches your recorded drives onto the roads (`routing/TrackMatch.kt`) and reports your speed by road class and the
seconds lost at each kind of turn against `routing/TurnCost.kt`'s table, on the phone only. That is also the first
real data for ADR 0062's guessed values.

Answer:
