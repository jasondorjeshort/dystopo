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

Waits: the owner reopens background polling (neither a background poll nor an open-app-only list for now) (the owner, 2026-11-22, by multiple choice at triage).
