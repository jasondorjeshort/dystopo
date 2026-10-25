---
date: 2026-10-25
kind: feature
related: [2026-10-25-track-activity-profiles-and-dropout-handling.md, 2026-10-13-routing-modes-crow-bushwhack.md]
---

# Live velocity readout (speed, climb rate, heading) near the scale bar, shaped by travel mode

The owner (chat, 2026-10-25, medium-high priority) wants velocity shown on the map, likely beside the
scale bar (`controls/ScaleBar.kt`). Today nothing user-visible shows speed:
`controls/Heading.kt` keeps the last fix's speed and course only to pick the facing orientation, and
`GET /camera` reports them under `heading` (`speedMps`, `courseDeg`). The owner's points:

1. The form depends on the **mode of travel**, set by the user or detected by the app. This overlaps
   the activity profile of the track idea (related); one mode list should serve both.
2. **Horizontal speed** in mph or km/h (the unit preference, ADR 0022). On foot it must be measured
   over a period of time, and for trail and road travel it should count the trail's winding (speed
   along the path, not between two noisy fixes).
3. **Vertical speed** per hour, in feet or metres rather than miles. It may be left out when near zero.
4. **Heading.**
5. Boat and rail are modes of their own. The owner says this part "may not be formulated well enough".
6. The owner asked for heavy research on what other apps and the literature do (below).

## Open

- The mode set and how it is chosen (the Question below). Activity recognition needs Play services
  and a permission, and has no train, boat or plane class.
- The vertical source per mode. The phone has a barometer and the app reads none today. GPS height
  is noisy and DEM height along the path is a third option. A barometer is wrong in a car with a
  window or fan going, and shows cabin altitude in a plane.
- Along-trail speed: snapping onto the T45 routing graph, or distance along an active route
  (`nav/Navigator.kt`), versus a long-window distance over time. Speed made good toward a target is
  a further option.
- Where the readout sits: a compact strip on the scale bar's line, or a tap-to-expand strip. Also
  how it shares space with the nav card and bottom panels (`MapInsets.kt`).
- Agent-testable: a pure `shared` estimator (fixes in, readout out) with replay tests. A debug route
  (`GET /velocity`) mirrors what is drawn; screenshots go in the report (ADR 0031: built in alpha,
  strings shown).

## Research (2026-10-25: three read-only agents, web only; the evidence is partly forum-grade)

**Apps.** Outdoor and fitness apps put speed in a stats bar or data page the user configures. OsmAnd
is the one with map widgets: current speed, average speed over a user window of 15 s to 60 min (with
"ignore stops" below 1 unit/h), and average vertical speed over the same window. Foot gets pace and
bike gets speed (Strava run vs ride; AllTrails defaults to pace and has a switch to speed). The sport
is always chosen by the user; no outdoor app was found to auto-detect it. A live vertical rate is a
watch and handheld feature (Garmin, Suunto, COROS, Polar): instant and 30 s fields exist, users call
instant "too jumpy", and units are m/h, ft/min or ft/h. "Moving" vs "overall" averages are standard.
Garmin handhelds show the compass heading when slow and switch to GPS course once moving, as
`Heading.kt` already does. **No app was found showing speed along the trail or speed made good
toward a target**, so here Dystopo would lead rather than follow.

**Other modes.** Road: a speed badge in a corner of the navigation screen, coloured when over the
limit, beside a speed-limit sign; no vertical speed. Aviation apps let the user pick instruments:
ground speed in knots, GPS altitude, ground track (not heading: no compass), and vertical speed in
ft/min (m/min optional). Avionics look ahead with a 60 s default. Marine: speed and course over
ground in knots and degrees, with **damping as a user setting** (low/medium/high), because boats
yaw. Rail: no convention. GPS drops in tunnels and can spike to impossible speeds on reacquiring, so
good apps dim the last value and show how long the outage has lasted.

**Literature (the levers).**
- *Speed source.* Use the fix's own speed (`Location.getSpeed`, Doppler or fused), never
  differenced positions, for the live number. Differencing short steps overestimates distance from
  noise: with σ = 3 m, a 10 m step reads about 17% long and a 1 m step 500% long (Ranacher et al.,
  arXiv:1504.04504). A large phone dataset of differenced, filtered speed still had 0.4 to 0.6 m/s
  median error (Petrella et al. 2025, PLOS ONE 20(8):e0327896), about 40% of walking speed. Distance
  over time is only fair over 30 s or more. `speedAccuracyMetersPerSecond` is a 1-sigma value.
- *Standing still.* Chips already clamp speed below a static threshold (Quectel's default is 0.4 m/s).
  Course wanders by more than 20° at rest. So: show a dash below about 0.3 to 0.5 m/s, or below 1 to
  2× the speed accuracy, with hysteresis, and freeze the course with it.
- *Vertical.* Phone GPS vertical error is about 1.3 to 2× the horizontal and can exceed 50 m in
  canyons. Barometer noise is about 0.1 to 0.25 m, and weather drift about 3 to 8 m/h (more than
  16 m/h in a front, by our own arithmetic). Hikers climb 300 to 600 m/h (DIN 33466: 300 up, 500
  down), so on foot the barometer over a 30 to 60 s window is sound, corrected slowly by GPS or DEM.
  Hiding the rate below about 50 to 100 m/h is our estimate, not a published threshold. Vehicles
  give barometer errors of 15 to 50 m (windows, ventilation), and cabins are held at 8,000 ft or
  lower and change about 300 to 500 ft/min. So use no barometer in road, rail or air modes. Show
  ft/min or m/min in the air (aviation convention), m/h or ft/h on foot.
- *Trail winding.* Coarse sampling cuts corners: GPS read up to 50% short on switchback mountain-bike
  trails in practitioner tests. Map matching is a standard method (an HMM with Viterbi decoding,
  Newson and Krumm 2009); nothing was found for trails specifically.
- *Mode detection.* Still, foot and bike separate well; motorised modes do not (car vs bus, train vs
  subway: the best results in the SHL challenge were about 78 to 94% F1). Smoothing uses an HMM or a
  rolling median with a minimum dwell time of 30 to 60 s, and a user override that holds.
- *Display.* Update about twice a second, round to a step that matches the noise (whole km/h on foot,
  tens of m/h vertical), and lag the vertical rate by about 5 to 10 s as an aircraft vertical speed
  indicator does. Most use is a glance of about 5 s.
- *Pace models.* Naismith, Tobler, Langmuir and Scarf (1 m of climb is worth 8 to 10 m of flat) are
  planning models. Nothing shows that a live grade-adjusted pace helps a hiker.
- *Gaps the literature leaves.* Phone `getSpeed` accuracy at walking pace under canopy, per chipset;
  phone barometer drift in the field; the readability of a phone overlay while walking. Our own
  recorded tracks against a reference would answer the first two.

**Sketch the research supports (tentative).** One estimator in `shared` takes fixes, an optional
barometer and the mode, and gives speed, climb rate and course, each with "hidden" as a state.
- Foot: speed (or pace, by a setting) from the fix speed over about 10 to 30 s, plus distance along
  the matched trail or route over a long window. Climb rate in m/h or ft/h from the barometer over
  30 to 60 s, hidden near zero. Heading by `Heading.kt`'s rules.
- Bike: speed over about 3 to 5 s, climb rate optional.
- Road and rail: speed only, no barometer. Dim the value and show the outage on a gap, and drop the
  spike after it.
- Boat: speed and course, knots as an option, damping as a setting.
- Air: ground speed (knots as an option), GPS altitude, vertical speed in ft/min from GPS only, track
  in degrees.
- Mode: the user picks it (shared with the track's activity profile). Auto-detection is at most a
  suggestion by speed, with dwell time and hysteresis, never a silent change of units.
- Labels in plain words ("speed", "pace", "climb", "course"), no product or jargon names.

Question: which modes, and how is one chosen? (a) A fixed list (foot, bike, road, rail, boat, air)
picked by the user, remembered, and shared with the track's activity profile; the app may suggest a
change by speed but never switches on its own. (b) The same list, but the app switches by speed with
dwell time and hysteresis unless the user has pinned a mode. (c) Something else: a different list,
or foot/bike/vehicle only for a first cut. Also, is knots wanted for boat and air?
Answer:
