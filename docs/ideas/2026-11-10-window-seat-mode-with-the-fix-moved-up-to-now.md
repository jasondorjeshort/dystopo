---
date: 2026-11-10
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md]
---

# Window-seat mode: what is passing below, from a position moved up to now

Three of wave 31's fixes came from flights (the barometer in a pressurised cabin, the frozen marker, the turn-back
clock in the air), and "What can I see from here" (T182, `shared/.../dem/Viewshed.kt`, `androidApp/.../app/viewshed/`)
already lists the named peaks in view with distance and true bearing. In the air the same question is "what is that
below", asked from a seat moving at 250 m/s. The owner, 2026-11-10: "Windowseat mode is just like the feature where
it tells you what you're looking at ... yes I would like this."

**The lag first.** The owner: "I noticed the GPS seemed to lag behind the actual location by a few seconds though,
very noticeable when flying above sharp mountains. So in flight mode we need a speed and direction and the time of
the gps fix (slightly in the past) could inform us of real time movement." A fix carries its own time
(`Location.elapsedRealtimeNanos`, read by `fixElapsedMs` in `app/MapController.kt`; the readout's fix age entry of
T257 shows it), a speed and a course. A few seconds at airliner speed is a kilometre: the puck and the viewshed
should stand at the fix moved along the course by the fix's age, not at the fix.

- **Dead reckoning in the air:** position = last fix + speed along the course for (now minus the fix time), drawn as
  the puck with the fix itself faint behind it when the air activity is set (T250's flying detector and
  `TurnActivity`); the readout's course and speed (`shared/.../readout/Readout.kt`) are the inputs. Reported by the
  debug route as `extrapolatedM` so the tour can check it with a replayed flight.
- **What is below:** the viewshed from the moved position with the view range at 100 km, its peaks listed by bearing
  relative to the course ("left, 20 km: Mount Rainier 4392 m") and the glaciers, lakes and passes the base map
  names within the window's cone; a tap on the list pans there.
- Agent-testable: `devtool readout --gpx` on a replayed flight reports the lag and the moved position; a unit test on
  the extrapolation; `GET /readout` and `GET /viewshed` on the emulator with a flight replay.

## Open

- Whether the lag is the receiver's (the fix time says so) or the fused provider's smoothing (then the mode reads
  `GPS_PROVIDER` fixes directly); the fix ages from a real flight settle it, so the self-test idea's sample or a
  diagnostics dump from a flight comes first.
- The cone is one side of the aircraft: the user says which, or the mode shows both.
- Names below need the base map at a coarse zoom under the aircraft, which T264's world overview supplies.

Plan: two passes, the lag first; this file stays for the second. Pass one: `shared` gains a pure extrapolation
(the fix, its speed and course, its age from `elapsedRealtimeNanos`; capped at 10 s and used only above a speed
floor) with a test; under the flying activity the puck stands at the moved position with the fix itself faint
behind it, `GET /readout` reports `extrapolatedM` and the fix's age, and the diagnostic dump keeps the ages of the
recent fixes by provider, so the next real flight says whether the lag is the receiver's or the fused provider's.
Done when: `check` green; `devtool readout --gpx` on a replayed flight reports the lag and the moved position; on
the emulator with a flight replay the two marks are seen apart in a screenshot. Pass two, after a dump from a real
flight has been read: "what is below" from the moved position (the viewshed at a 100 km range, the names by side
of the course, the side asked once). Out of scope in pass one: reading `GPS_PROVIDER` directly (the dump decides).

2026-11-20: the owner, "I suspect the pano or horizon views will supercede the window seat idea." Pass two ("what is
below") would then be the Horizon and Panorama views seen from altitude: the names below the horizon need a downward
band of elevation angles. Pass one (the fix moved up to now) stands on its own, since both views and the map's puck read
the position.
The owner, same day: "the window seat is an inversion of the current horizon mode: looking down instead of up. But
it's the same view, just flipped." So pass two is the Horizon view's plot turned over: the centre is the nadir
instead of the zenith, the rings are angles below the horizon, and the same layers draw there (peaks, lakes,
glaciers, and other aircraft below).
Raised in the same talk and recorded on the owner's "yes, record all that": the view flips by itself when the flying
detector (T250) says the phone is in the air, with a toggle to override (a hiker on a summit looks down too); and
aircraft from a seat can be above or below, so a plot that spans both sides of the horizon (the Panorama ribbon does)
serves one aircraft layer in every case. Priority (owner, 2026-11-20): the aircraft paths come first; this follows.

2026-11-20: from the coordinator's review of the flights plan, at the owner's request. Your own flight with no
on-board Wi-Fi: before takeoff (with a connection) the app caches the aircraft layer's positions and the route table
around the departure airport; in flight it matches the phone's own GPS track (position, speed, course, altitude)
against the cached flights to name the flight, then names the terrain below from the flipped Horizon view. It needs
only data fetched before takeoff, so no cabin signal. Open: how many flights to cache and how soon they go stale, a
wrong match (two aircraft on one airway), and whether cabin GPS reception limits it. Data licences as in
`docs/findings/2026-11-20-aircraft-layer-sources-and-display.md` (positions ODbL; routes CC0).
