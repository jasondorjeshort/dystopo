---
date: 2026-11-10
kind: feature
related: [2026-10-30-phones-as-a-barometer-network.md, 2026-11-07-accelerometer-for-climb-rate-and-activity.md]
---

# A ground mode that sets the altimeter from the elevation model, and a fix that tightens over days

T197 sets the altimeter from one tap at a summit, pass or sign (`ReadoutEstimator.pin`, `shared/.../readout/KnownHeights.kt`,
`POST /readout/calibrate`), and T160's filter takes the DEM height on foot once per 150 m under 20 degrees
(`shared/.../readout/ElevationFilter.kt`). What neither does is set the height from the ground under the phone on
purpose: the DEM knows the ground to a metre, but a phone in a hand, a pocket or a pack is one to two metres above
it and moving. The owner, 2026-11-10: "This has already been suggested and we should do, but it needs its own mode.
You need the phone on the ground (or a given distance off the ground); even walking with it can make a measurable
distance off the ground."

- **The ground mode:** an action "Set my height from the ground here" that asks for the phone's height above the
  ground (0 on the ground; a typed figure, remembered), waits until the accelerometer says the phone is still, reads
  the DEM at the fix with its slope (a steep cell adds sigma; the position's own accuracy adds more), and pins the
  filter as T197 does with a sigma from those parts; the mark reads "Set from the ground".
- **A fix that tightens over days.** The owner: "we should be able to keep the phone in a single place for days to
  get a more accurate fix, like how they use gps to geofix mountain summits; this implies we know an uncertainty
  level for the fix and can drop it over time, but we could then show that 95% confidence interval. Would need
  research and math." A static mode that keeps every fix while the phone is still, averages them with their reported
  accuracies and the error's correlation over minutes (fixes an hour apart are nearer independent than fixes a
  second apart), and shows the position and the 95 % ellipse shrinking on the map; the result can be kept as a
  waypoint with its uncertainty, or pin the altimeter with that height.
- Agent-testable: `devtool readout` with a still log reports the pin and its sigma; the averaging as a pure function
  in `shared` with a test on synthetic correlated noise; `POST /readout/calibrate?ground=1` on the emulator.

## Open

- Research first (ADR 0044): how static GNSS averaging on a phone behaves (the error is far from white: multipath
  and the satellite geometry drift over hours, so the gain is slower than one over root n), what Android's raw
  measurements (`GnssMeasurement`) add, and how surveyors state a summit fix's confidence.
- Whether the still detector is the accelerometer idea's step detector or a smaller one of its own.
