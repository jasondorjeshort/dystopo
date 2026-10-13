---
date: 2026-10-13
kind: bug
status: new
pipeline: none
related: [2026-10-13-route-waypoints-and-reroute.md]
---

# Android: cannot create a waypoint on a track

Reported by the owner: on the phone there is no way to create waypoints.

Likely cause, from the code (unconfirmed): the only waypoint control is the "Waypoint" chip in
`app/tracking/TrackControls.kt`, shown only while recording and only when the Tracks bar is
unfolded. With no recording running, or with the bar folded, nothing offers a waypoint. A
long-press on the map is used by routing and trips, not for waypoints.

## Open

- Reproduce on the emulator first (debug routes for tracks: `TrackRoutes.kt`) and say which case
  fails: not recording, folded bar, or a real defect while recording.
- Wanted behaviour: drop a waypoint at the current fix any time, and at a long-pressed map point;
  name and edit it in the existing waypoint sheet; it syncs as today.
- Needs a debug route or an existing one to test it (every feature must be agent-testable).
