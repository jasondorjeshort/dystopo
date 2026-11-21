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
