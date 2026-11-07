---
date: 2026-11-07
kind: bug
related: [2026-11-02-replay-your-own-hike-with-the-gps-cut.md, 2026-11-07-self-marker-stops-while-the-track-records.md]
---

# The fix was lost for five minutes mid-flight

The owner, on a flight on 2026-11-07: "partway through we still lost fix for 5 minutes. Seemed odd." A phone at a
cabin window usually keeps a GNSS fix; a five-minute gap is either the receiver (the window's side, the phone's
position) or the app's own gating of poor fixes (`androidApp/.../app/tracking/LocationSource.kt`, the recorder's
accuracy cut from T203 and T204, 150 m) or the recorder's auto-pause (T159, T172).

## Open

- The recorded track's gap and the fixes around it: export the track (GPX plus the recorder's log, if the replay
  idea's "GPS cut" export exists by then) and read whether fixes arrived and were dropped, or never arrived.
- Whether a flying activity should relax the accuracy cut (fixes at altitude are often worse than 150 m while
  still right to within a few hundred metres, which is nothing at 500 km/h).
