---
date: 2026-10-25
kind: feature
related: [2026-10-25-track-activity-profiles-and-dropout-handling.md, 2026-10-13-routing-modes-crow-bushwhack.md, 2026-10-29-route-times-fitted-to-your-tracks.md]
---

# Live readout of speed, climb rate, heading and elevation near the scale bar, shaped by travel mode

The owner (chat, 2026-10-25, medium-high priority, planning only for now) wants velocity and
elevation shown on the map, likely beside the scale bar (`controls/ScaleBar.kt`). Today nothing
user-visible shows speed:
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
7. **Elevation** in the same readout. Use the **barometer** for precise elevation and climb rate when
   the phone has one, as a setting that defaults to on. Calibrate it mostly from GPS readings, and
   from sea-level pressure fetched online from a free source when one is reachable.
8. **No pages to swipe and no boxes covering the map.** A reference screenshot of another topo app
   in flight showed six large boxes (position, heading, speed, accuracy, altitude, distance) over the
   lower third of the map; the owner finds its swipe system poor. Instead, each entry has its own
   user options (show or hide, and the variants below). Adjust after use.
9. During implementation, an agent driving the emulator gives feedback on what reads well: replay a
   walk, a drive and a flight through the readout, compare screenshots, and iterate.

## Open

- The mode set and how it is chosen (the Question below). Activity recognition needs Play services
  and a permission, and has no train, boat or plane class.
- The vertical source per mode: the barometer on foot and bike, GNSS and DEM in vehicles and the air
  (research below). The app reads no barometer today.
- **A new data flow** (`CLAUDE.md`, Decisions: flagged, not guessed). The owner proposed the
  sea-level pressure fetch; which source still needs choosing. Weather-station altimeter settings
  (METAR, public domain) are preferred to a CC BY model service. It needs a registry entry in
  `sources.json` and a credit line, it is fetched only while the readout is in use, and nothing
  is gated to Wi-Fi (ADR 0023).
- Along-trail speed: snapping onto the T45 routing graph, or distance along an active route
  (`nav/Navigator.kt`), versus a long-window distance over time. Speed made good toward a target is
  a further option.
- Where the readout sits: a compact strip on the scale bar's line, or a tap-to-expand strip. Also
  how it shares space with the nav card and bottom panels (`MapInsets.kt`).
- Agent-testable: a pure `shared` estimator (fixes and pressure samples in, readout out) with replay
  tests. A debug route (`GET /readout`) mirrors what is drawn and also gives GNSS, barometer and DEM
  heights separately; screenshots go in the report (ADR 0031: built in alpha, strings shown). The
  replay route needs a pressure track beside the fixes.

## Research (2026-10-25: four read-only agents, web only; the evidence is partly forum-grade)

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
  canyons. Hikers climb 300 to 600 m/h (DIN 33466: 300 up, 500 down). Show ft/min or m/min in the
  air (aviation convention), m/h or ft/h on foot.
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

**Barometric elevation.**
- *Sensor.* A BMP390-class sensor has a relative accuracy of ±0.03 hPa (about 25 cm) and an absolute
  accuracy of ±0.5 hPa (about 4 m, which differs per phone; calibration absorbs it). It draws a few µA
  at 1 Hz; the real cost is waking the CPU, so batch the samples. Only about 31% of Android models
  released in 2024 have a barometer (flagships mostly do), so every part needs a no-barometer path.
- *Weather drift.* About 3 to 8 m/h, more than 16 m/h in a front, against a hiker's 300 to 600 m/h.
  So climb rate from the barometer over 30 s is sound (about 15 m/h σ, derived).
- *Calibrating from GNSS* is what watches do; a Garmin patent (US 6735542) gives the shape. A loop on
  the barometer-minus-GPS difference, with a slow gain (about a 1 h constant) for steady tracking and
  a fast one (about 5 min) after a cold start. A residual beyond k × the vertical uncertainty
  triggers a coarse recalibration. Derived for a phone: an offset state as a random walk of about
  5 m/√h, GNSS decimated to one update per 30 s (its error is correlated), a variance of (2 × vAcc)²,
  a time constant of 8 to 30 min, and 3 to 8 m absolute in practice. Changes are sub-metre.
- *The DEM as a calibration source* while on foot: Copernicus GLO-30 RMSE is about 1.7 to 2.3 m (high
  under canopy, worse above 15° slope); 3DEP is about 0.8 m. Horizontal error becomes height error
  on slopes (tan(slope) × hAcc: about 4.6 m at 30° and 8 m). Gate it to foot speed, slope under 20°,
  and no bridge, building, tunnel or cliff nearby (the app has these in its tiles). Limit it to one
  update per 150 m moved, because DEM error is correlated over about 100 m. It is the better anchor
  in canyons and under canopy, where GNSS vAcc runs to 50 m. The best published phone result
  (barometer bias against a local terrain model and nearby stations) is 1.4 m RMSE outdoors (Li et
  al. 2023, IEEE TIM). Garmin offers "calibrate from DEM".
- *Sea-level pressure online.* Weather-station altimeter settings (aviationweather.gov data API: a
  US government work, public domain, 100 requests/min). Use the altimeter group, not the remarks
  sea-level pressure, which drifts at high stations. Or Open-Meteo surface pressure (CC BY 4.0,
  non-commercial free tier). It is good to about 1 hPa per 100 km in ordinary weather (≈ 8 m) plus
  the drift with age. So it seeds the offset with a variance that grows with distance and age, and
  after about 12 h it is weaker than one good GNSS fix. Cold air makes ISA heights read about 4%
  long per 10 °C below standard (about 40 m on a 1000 m climb), so continuous GNSS calibration beats
  a single pressure value.
- *Weather or climbing?* Suunto treats a pressure change as altitude after 5 m of vertical movement in
  3 min. With under 5 m in 12 min it treats it as weather: altitude held, sea-level pressure moved.
  This matters indoors with no GNSS.
- *When the barometer lies.* In vehicles dynamic pressure gives about 1 m of error at 10 mph and about
  36 m at 60 mph (54 m in one test). Cabins and wind are also problems: a 20 m/s gust can reach
  about 25 m. Mark it suspect at GNSS speed over about 7 m/s, when its vertical speed and GNSS
  disagree by over 1 m/s twice in 60 s windows, or when its jitter exceeds 1.5 m over 10 s while
  walking. Drop it while suspect and reopen its offset afterwards. Above about 60 m/s it is off
  altogether.
- *Display.* Watches show one blended elevation in whole metres or feet, with no accuracy figure or
  source; none shows GPS, barometer and DEM side by side. The fused provider is not documented to use
  the barometer, so assume it does not. Use `getMslAltitudeMeters` on API 34+, else the ellipsoid
  height minus EGM2008 (the app's datum).
- *Gaps the literature leaves.* Phone `getSpeed` accuracy at walking pace under canopy, per chipset;
  GNSS vertical error correlation and how honest vAcc is; barometer jitter in a pocket and in wind;
  false alarms of the suspect rule in buses, trains, gondolas and lifts; GLO-30 bias on real trails.
  Our own recorded tracks, with pressure, against a reference would answer them.

**Sketch the research supports (tentative).**
- *One estimator in `shared`.* It takes fixes, pressure samples, DEM lookups and the mode. It gives
  elevation, climb rate, speed and course, each with "hidden" as a state and an uncertainty. The
  elevation part is a small Kalman filter: height, vertical speed and barometer offset. Barometer at
  1 Hz; GNSS each 30 s with gates; the DEM on foot with gates; sea-level pressure as a prior.
- *Foot.* Speed or pace (by a setting) from the fix speed over about 10 to 30 s, plus distance along
  the matched trail or route over a long window. Elevation and climb rate (m/h or ft/h, 30 s, hidden
  near zero) from the barometer when present. Heading by `Heading.kt`'s rules.
- *Bike.* Speed over about 3 to 5 s; climb rate optional.
- *Road and rail.* Speed and elevation; no barometer. On a gap, dim the number and show how long the
  gap has lasted; drop the spike after it.
- *Boat.* Speed and course, knots as an option, damping as a setting.
- *Air.* Ground speed (knots as an option), GPS altitude, vertical speed in ft/min from GPS only, and
  track in degrees.
- *Mode.* The user picks it (shared with the track's activity profile). Auto-detection is at most a
  suggestion by speed, with dwell time and hysteresis, never a silent change of units.
- *Entries.* Each entry (speed, climb, heading, elevation) has its own options: show or hide, pace or
  speed, damping, and whether to show the ± and its source. The default set comes from the mode.
- *Labels.* Plain words ("speed", "pace", "climb", "course", "elevation"; "weather station pressure"
  rather than QNH or METAR); no product names. A credit line for the pressure source if it needs one.

Question: which modes, and how is one chosen? (a) A fixed list (foot, bike, road, rail, boat, air)
picked by the user, remembered, and shared with the track's activity profile; the app may suggest a
change by speed but never switches on its own. (b) The same list, but the app switches by speed with
dwell time and hysteresis unless the user has pinned a mode. (c) Something else: a different list,
or foot/bike/vehicle only for a first cut. Also: is knots wanted for boat and air, and which online
pressure source (public-domain station data, or a CC BY model service, or both)?
Answer: (owner, chat, 2026-10-25) (a) for now: a fixed list the user picks, remembered and shared
with the track profile, with suggestions only. Knots is an option alongside mph and km/h for boat and
air, not a default. Fully free sources only: public-domain weather-station data (the altimeter
setting); no CC BY or non-commercial-tier service.
