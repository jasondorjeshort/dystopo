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

2026-11-16, the owner on the drive half: "Yes! Really what we want is to know how fast YOU drive. But how would we
even do this?" How (the coordinator's sketch, not read against what `routing/TrackMatch.kt` can do with a drive):
each recorded drive is matched onto the roads, then four small sets of numbers are taken, each a median over many
samples with a floor on how many. (1) Your speed against the edge's speed on free stretches, by road class. (2) The
lateral acceleration you take bends at (`v^2 / r` at each bend's slowest point): the one value the bend cap of
`routing/SpeedProfile.kt` needs and the winding-roads idea lacks. (3) Your acceleration and braking, from the speed
changes around turns and stops. (4) The seconds stopped at each kind of turn, by the class of the road crossed: the
first data for `2026-11-15-a-turn-across-traffic-costs-more-where-the-road-is-busy.md`. These are `ProfileLimits`'
own fields, so the fit sets the profile's limits and a drive's time follows from them. Needed first: which tracks
are drives (the activity on the track), and whether the recorded fixes are close enough together to see a bend's
slowest point (unmeasured). All of it on the phone; nothing leaves it.

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

Answer: the drive half is wanted (owner, 2026-11-16: "Yes! Really what we want is to know how fast YOU drive"), by
the recommended measurement first; the how is the paragraph of that date above. The hike half's question (is the
fit in Route times what was meant) is not answered yet.
