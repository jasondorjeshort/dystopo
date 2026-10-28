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
through the debug routes (`GET /camera`, `GET /location`, `POST /location`, `POST /camera/recenter`):

- The app reports `ready` about 12 s after launch.
- On a cold open the camera shows the first pack's view: a 41 N, 76 W view at zoom 12, north-up, bearing 0.
- With no fresh fix, the last location is a stale network fix (accuracy 100 m) from an earlier session, hundreds of
  kilometres from that view. Device fixes are not listening at launch (`deviceListening: false`).
- `fixOffsetPx` is 610796 at every sample after a cold open: nothing moves the camera to the stale fix.
- A fix injected with `POST /location` into the running app does not move the camera by itself
  (`fixOffsetPx` rose to 946436). A recentre tap then moves the camera to the fix, centred (`following: true`,
  `centred: true`, `fixOffsetPx: 1`). So the recentre path works when a fresh fix is present.
- Relaunching after an injected fix still opened on the pack's view with `fixOffsetPx` 610796. The injected fix did
  not persist across the relaunch (it is in-process only, so the stale network fix remains the last one).
- One relaunch (the one after the injected fix) crashed the app at start-up: `IllegalStateException: Reading a state
  that was created after the snapshot was taken or in a snapshot that has not yet been applied`, from
  `AttributionLine` (`app/about/AttributionLine.kt:63`) reading `MapViewport.getBounds` (`MapViewport.kt:28`) during
  `MainActivity`'s `MapScreen`. The next relaunch with no injected fix did not crash (0 crashes in its log buffer).
  Not reproduced twice; the crash is the first thing to check if it recurs.

## Not yet known

- Whether the open view should follow the last known fix, the last saved view, or the pack, and what the owner expects.
- Which code picks the first camera on open.
- Whether a fresh GPS fix at launch (a real device fix, not injected) would move the camera on open. Only injected fixes
  were tested.
- Whether the start-up crash repeats, and whether it comes from the injected fix or the relaunch timing.

## Next

Read the code that sets the initial camera, and the `AttributionLine` / `MapViewport` read at start-up, with the numbers
above as the repro. The task that fixes the centring writes its before-and-after readings from these routes in its report.
