---
date: 2026-11-21
kind: feature
---

# The aircraft that passed over you, a line in the recording and the trip report

While Flight tracking is on and a track is recording, the app already polls aircraft around the map
(`AircraftTracker`, T311 to T316) and knows each one's position, altitude and, since T316, height above the ground. Kept
per recording, the passes become a line in the trip report or the track's details: "14 aircraft passed within 5 km;
the lowest a tanker 2,100 ft above you at 2:14 PM; the closest an airliner 3.2 km away", with the passes on the track's
time axis. No extra requests: only polls the map made anyway are counted, and the help says so (a gap in polling is a
gap in the list).

The owner, 2026-11-21: "Sounds cool."

## Open

- Where the closest approach is measured (straight-line distance to the track at the same instant, from the polled
  positions; between polls interpolated, and said so).
- Storage: a small table beside the track, or a summary only (the track database's migration rules, `.claude/rules/trackdb.md`).
- Privacy: the list is derived from public broadcasts, but a share of the track must not add it unless asked.

Question: the Flights data is polled only while the app is open and Flight tracking is on (`AircraftPoller`: "in the
foreground"), so a recording carried in a pocket would list almost nothing. May a recording poll in the background at
a slow rate to make this list (a request every few minutes, counted against the 500 a day, the phone's rounded
position sent with the screen off), or is the list built from the open-app polls alone? Recommended: neither yet.
Your answer of 2026-11-21 on the overhead alert kept polling out of the background, and without it the line would
read "2 aircraft" on a day twenty passed, which is worse than no line. If you want it anyway, the slow background
poll during a recording is the form that works, as a setting of its own, off by default.

Answer:
