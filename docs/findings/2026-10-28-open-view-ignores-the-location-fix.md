---
date: 2026-10-28
status: open
area: android / map camera / location
promoted_to:
---

# On a cold open the camera shows the first pack's view, not the location fix

## Question

The owner reports that the map's centring on app open is wrong (owner, 2026-10-28, next wave; TASKS.md › the
"Map centring on app open" bullet). What does the camera do on a cold start, and what is the location fix at that moment?

## Findings

Measured on the owner's phone (Android, the debug build signed with the release key, `0.1.0-dev`), on 2026-10-28,
after a force-stop and a relaunch, through the debug routes (`GET /camera`, `GET /location`):

- The app reports `ready` about 12 s after launch.
- The camera opens on the view of the first pack in the list: a 41 N, 76 W view at zoom 12, north-up, bearing 0.
- The last location fix is a stale network fix, accuracy 100 m, from the previous session, hundreds of kilometres
  from that view. Device fixes are not listening at launch (`deviceListening: false`), so no fresh fix arrives.
- `fixOffsetPx` is 610796 from the first sample to the last: nothing moves the camera to the fix on open.
- On the same phone, before the relaunch, a recentre tap logged `result: recentered` with `advanced: false`
  from the same far-away camera. The tap did not move the camera, which is not explained yet.

## Not yet known

- Whether the open view should follow the last known fix, the last saved view, or the pack, and what the owner expects.
- Which code picks the first camera on open, and whether the recentre tap's `advanced: false` is the same fault.
- Whether a fresh GPS fix at launch would move the camera (not tested; the phone had no GPS fix in this run).

## Next

Read the code that sets the initial camera and the recentre action, with the debug-route numbers above as the repro.
The task that fixes it writes its before-and-after readings from these routes in its report.
