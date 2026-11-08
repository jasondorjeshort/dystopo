---
date: 2026-11-07
kind: bug
related: [2026-11-02-replay-your-own-hike-with-the-gps-cut.md, 2026-11-07-self-marker-stops-while-the-track-records.md]
---

# The fix was lost for five minutes mid-flight, then for 18 minutes on the next flight, and much of a track went unrecorded

The owner, on a flight on 2026-11-07: "partway through we still lost fix for 5 minutes. Seemed odd." A phone at a
cabin window usually keeps a GNSS fix; a five-minute gap is either the receiver (the window's side, the phone's
position) or the app's own gating of poor fixes (`androidApp/.../app/tracking/LocationSource.kt`, the recorder's
accuracy cut from T203 and T204, 150 m) or the recorder's auto-pause (T159, T172).

On the second flight the same day (v0.1.22-alpha), the fix was lost on takeoff again, the track card read "Waiting for
GPS: no fix for 18 min, last accuracy 395 ft", and in the end much of the flight was not recorded at all. The owner:
"Actual lack of GPS fix, or incorrectly discarding the data because flight mode still isn't very good? How to even
tell?" The last accepted accuracy, 395 ft (about 120 m), sits just under the 150 m cut, which fits fixes at altitude
drifting past the cut and being dropped as poor: 18 minutes of "no fix" may be 18 minutes of fixes refused.

## Open

- The recorded track's gap and the fixes around it: export the track (GPX plus the recorder's log, if the replay
  idea's "GPS cut" export exists by then) and read whether fixes arrived and were dropped, or never arrived.
- Telling the two apart on the phone: the recorder keeps a count and the last accuracy of the fixes it dropped
  (`GET /tracks`, the card's waiting line: "no fix for 18 min; 240 fixes refused, best 160 m"), and the replay
  export carries the dropped fixes, so the next flight answers the question.
- Whether a flying activity should relax the accuracy cut (fixes at altitude are often worse than 150 m while
  still right to within a few hundred metres, which is nothing at 500 km/h).
