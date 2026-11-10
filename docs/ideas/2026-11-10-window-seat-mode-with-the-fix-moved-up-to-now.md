---
date: 2026-11-10
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md, 2026-11-07-turn-back-card-toggle-and-its-clock-in-the-air.md, 2026-11-07-a-freeze-and-power-draw-during-the-flight.md]
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
