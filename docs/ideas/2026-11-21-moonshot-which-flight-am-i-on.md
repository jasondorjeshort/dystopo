---
date: 2026-11-21
kind: feature
related: [2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md, 2026-11-20-the-view-from-that-plane.md]
---

# Moonshot: the app works out which flight you are on

On a plane with the phone's GPS working at the window, the phone's track (position, ground speed, heading) and its
barometer (cabin pressure is not altitude, but its changes mark climb and descent) can be matched against live ADS-B
aircraft near the fix: one aircraft whose positions sit within a few hundred metres of the phone's for minutes on end is
yours. Then window-seat mode (the related idea) knows your altitude and heading for the view out the window, the card
names your flight, its route and its arrival, and the Horizon view from that plane (the related idea) is the view from
your own seat.

The owner, 2026-11-21: "Haha that's hilarious; lets do it. Long term we could allow integration with your itinerary
somehow."

## Open

- The match: how many polls and how close before naming a flight; at cruise a position is a kilometre in four seconds,
  so position age matters (the same issue as the plane-camera and transit ideas).
- In the air the phone often has no data connection: the match needs an online moment (the gate, the climb on Wi-Fi), or
  the itinerary (the owner's long-term thought: a flight number from a booking, typed or shared) names the flight first
  and the offline track only confirms it.
- The quota: polling during a flight counts against the 500-a-day allowance like any other.

Plan: one pass, the match and its mark (the owner said yes above). `shared/.../aircraft/` gains a pure matcher fed each
second with the phone's fix (position, speed, course, GPS altitude, age) and `AircraftTracker`'s moved-up aircraft: a
candidate must stay within a distance that grows with both positions' ages, its speed, course and (where the fix has
one) altitude agreeing, over several polls spanning at least a minute, with no second aircraft as close; it lets go
when the two part, and never starts from a fix slower than an aircraft taking off. Unit tests from `AircraftFixtures`
(a matching track, two aircraft on one airway, a car under a low approach). `AircraftPoller` feeds it while Flight
tracking is on, from the map's own polls (no request added); the matched flight's card opens with "Your flight" as
its first line and its silhouette is marked on the map; `GET /aircraft` reports `ownFlight` (the hex, since when,
the nearest rival). Verify: on the emulator a replay (`POST /aircraft/replay`) with fixes injected along one of its
tracks, the card and the map viewed at the default text and at font scale 2.0; the same replay with the phone on the
ground matching nothing. Strings in the report (ADR 0031). Out of scope: a match with no connection (the cached
flights of `2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md`), an itinerary, and the view from the seat
(`2026-11-20-the-view-from-that-plane.md`); both read `ownFlight` when they are built.
