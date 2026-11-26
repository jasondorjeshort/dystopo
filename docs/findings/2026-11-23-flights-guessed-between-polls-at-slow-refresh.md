---
date: 2026-11-23
status: open
area: flights / polling and extrapolation
---

# Flights guessed between polls: how far a straight-line guess holds at a slow refresh, and how to show it

## Question

The owner, 2026-11-23, answering what a hidden flights layer should do: "how long dead reckoning on a reduced poll
rate is going to be effective. Can we poll every 2, 5, 10 minutes and still have reasonably accurate data? Is there
a good way to show that a flight's data is out of date and being guessed at based on dead reckoning?"
(`../ideas/2026-11-22-flights-have-two-switches-and-the-map-view-has-no-way-to-hide-them.md`).

## What was tried

One read-only agent (Sonnet-class) on `main` at 48bf56fe: the code read, ten sources fetched in the run, the error
table from its own geometry. Nothing measured; the replay fixture is synthetic and holds no truth. `S/` is
`shared/.../aircraft/`, `A/` is `androidApp/.../app/aircraft/`. Product names stay here.

## Result

**Today.** Refresh is a setting of 10, 20, 30, 60, 120 or 180 s, default 20 (`S/Aircraft.kt:175-176`); nothing
changes it by itself. The one feed allows 500 requests per UTC day per address without a key (the registry's
`adsblol-live`; the feed's own README says only that limits are "dynamic" and a key will come): 2.8 h at 20 s,
8.3 h at 60 s, 16.7 h at 120 s, 25 h at 180 s. Kept per aircraft: position, altitudes, track, ground speed,
vertical rate, the position's age, squawk, emergency, category; not the turn rate, roll or accuracy fields the
feed carries. `AircraftTracker.moveUp` already moves each mark along its track at constant speed for up to the
larger of 30 s and one interval, then freezes it; altitude is not moved. The hollow mark and "N s ago" start at
1.5 intervals and the mark is dropped at 2.5, so **hollow means a missed poll, not a guess**: between polls a
guessed position is drawn exactly like a measured one, and nothing shows uncertainty.

**Sources.** A thesis on projecting en-route ADS-B tracks: the 5 NM miss is passed by 1 % of tracks at 160 s, 10 %
at 320 s and 30 % at 540 s, and below FL300 deviations are up to three times larger. A trajectory-prediction paper
puts useful look-ahead at "about 5 to 7 minutes". The feed's decoder documents that a position "older than 60
seconds" is "no longer considered valid" (its own coasting limit). The surveillance track format has a coasted
flag with a system-dependent threshold. One flight-tracking site documents a hollow icon, a range ring and the
word "ESTIMATED" for a guessed position. A standard-rate turn is 3° a second; airliners at cruise turn slower.

**Error of a constant-velocity guess** (own geometry: 0.5 a t² with the speed change capped at 20 m/s, plus
2.6 m/s of speed or wind error; a 30° course change at the report gives 0.52 v t; cruise 250 m/s, climb or
descent 150, terminal 100).

| Phase, case | 30 s | 2 min | 5 min | 10 min |
|---|---|---|---|---|
| Cruise, steady | 0.08 km | 0.31 | 0.78 | 1.6 |
| Cruise, a 30° turn | 1.9 to 3.9 | 15.5 | 39 | 78 |
| Climb or descent, steady | 0.21 | 2.0 | 6.1 | 12.9 |
| Climb or descent, a 30° turn | 2.3 | 9.3 | 23 | 47 |
| Terminal, steady | 0.30 | 2.3 | 6.4 | 13.2 |
| Terminal, a 30° turn | 1.6 | 6.2 | 15.5 | 31 |

Seen from the ground, steady cruise is 0.4° at 10 km and 0.1° at 50 km after 30 s, 1.8° and 0.4° after 2 min,
4.5° and 0.9° after 5 min; steady climb, descent or terminal traffic is 11° to 13° at 10 km after 2 min; any
turn at 10 km is 9° or more from 30 s. Altitude left unmoved costs about 5° of elevation at 10 km after 2 min of
a 1,500 ft/min climb. A cruising plane 10 km away is gone from that range in 40 s.

**Answer to the owner.** Two minutes is honest for the map at small scale and for far cruise traffic in the sky
views; five minutes only for cruise traffic on the map; ten minutes for nothing (13 km of error on low traffic,
past every source's horizon). Pointing the phone at a near or low plane needs 20 to 30 s.

**Recommended.**
1. A guess horizon separate from the interval: stop drawing a guessed position at 2 min for traffic below
   10,000 ft, climbing, descending or on the ground, and at 5 min for cruise; stop naming it in the sky views when
   its error arc passes about 5° or at 2 min. Today a long interval would move and draw a plane for 2.5 intervals.
2. A guessed state from about 15 to 20 s after the position's own time: hollow mark with a faint ring that grows
   with age (provisional radius 0.1 v t en route, 0.3 v t low or terminal; an arc of azimuth in the sky views),
   the leg from the last report to the drawn point dotted (the dotted style already means estimated), the label's
   "N s ago" from the guess's start, the card "Position estimated, last report 94 s ago, about 3 km". A missed
   poll gets its own "stale" word.
3. Refresh adapts as the default choice, "Automatic", beside the fixed ones: 20 s with a sky view open, at most
   30 s while a plane is within 15 km or its closest approach is under 3 min, 60 s on the map at zoom 9 or wider,
   30 s closer in, none when hidden or in the background. A fixed 5 min choice may be offered; 10 min is not.
4. No setting for the horizon or the guessed look (an honesty rule, ADR 0044); Refresh stays a setting.
5. An "overhead soon" alert fires only on a position at most 60 s old.

## Next

Measured by T365 (2026-11-26): `2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md` has the table from two recordings and where it bears this finding's reasoning out (the medians) and where it does not (the 90th percentiles, and 5 minutes for cruise). What this paragraph asked for, as written before it:

Measure before building the guessed state: there is no recorder and no truth. A small recorder that saves each
poll's whole answer (one or two hours over a public area and one near an airport; at 20 s two hours is 360 of
the day's 500 requests), then a devtool report that applies `moveUp` from each fix to 30, 120, 300 and 600 s,
compares with the real fix, bins by phase and gives the median, p90 and p99 and the angle from an observer at 10
and 50 km; and the same tracker fed every k-th poll to show what 60, 120 and 300 s polling would have drawn. Also
how often the feed's turn-rate and roll fields are present. Battery and radio cost are unmeasured.
