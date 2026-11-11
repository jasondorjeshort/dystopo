---
date: 2026-11-01
kind: feature
related: [2026-11-01-trip-report-v2-sun-pressure-and-decisions.md]
---

# A hike replay page on the site: the track, the profile, the sun and the pressure under one slider

The trip report (T198) is a page after a recording on the phone; trip-report v2 wants the sun,
pressure and decisions on it. In a browser the same data is cheap to make scrubbable: a slider over the
hike's time moves the position on the map, the cursor on the elevation profile, the sun angle and
terrain shade at that moment, the pressure trend, and the readout's figures as they were. A URL to
send instead of a PDF. The site has the DEM for the shade and the recording arrives through the sync
peer the phone already pushes to.

## Open

- Which recording fields sync today (`docs/reference/android-tracks-and-sync.md`) and which the
  replay needs (barometer samples, the readout's fused elevation, the decisions).
- Public by link, or under the user's token only.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): what syncs today is the track's points (position, elevation, time, accuracy, speed,
course, flags), its gaps and its activity (`model/UserData.kt`, the `AppendPoints` op); barometer samples,
the pressure trend and the turn events are not stored with a track (the trip-report v2 idea). The server
has the DEM and `Sun`, but `CastShadow` is used by shared and devtool only; `/s/<token>` is a page for a
planned route, not a recording.

Question: public by link, or under the user's token only? Recommendation: token only (a recording is where
you were, hour by hour), and after the trip-report v2 question is answered, since the pressure and the
decisions on this page need that storage; the slider over position, profile, speed and the sun recomputed
from the DEM needs no new field and is two passes (a track-as-JSON route on the sync peer, then the page).
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
