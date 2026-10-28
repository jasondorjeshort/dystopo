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
through the debug routes (`GET /camera`, `GET /location`, `POST /location`, `POST /camera/recenter`; procedure in
`docs/reference/android-phone-over-adb.md`):

- The app reports `ready` about 12 s after launch.
- On a cold open the camera shows the first pack's view: the pack's centre at zoom 12, north-up, bearing 0.
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
  `AttributionLine` (`app/about/AttributionLine.kt:63`) reading `MapViewport.bounds` (`MapViewport.kt:28`) during
  `MainActivity`'s `MapScreen`. The next relaunch with no injected fix did not crash (0 crashes in its log buffer).
  Not reproduced twice.

## Code reading (2026-10-28, not yet a fix)

- **Where the cold-open camera comes from.** `MainActivity.onCreate` (`app/MainActivity.kt:55-60`) starts the map at the
  centre of the active DEM pack's header box, zoom 12.0. Unless the launch carries a `dystopo://map` link (`DeepLink.camera`),
  nothing else sets the first camera: no last fix, no saved view. The style (`TileService.style`) has no centre. That is
  the observed pack view. The camera moves to a fix only when a user acts (recentre, `MapOrientation`) or when the
  first-run card (`FirstRun.kt:82`, `:110`) or a pack switch (`PackManager.jumpTo`) asks.
- **Why the fix is not used.** Nothing reads `LocationSource`'s last fix at start-up to place the camera, and the device
  location listener is off until the puck or a route needs it.
- **The start-up crash.** `MapViewport.bounds` is a Compose `mutableStateOf` on a Kotlin `object` (`MapViewport.kt:13-20`),
  written in the camera-idle listener on the main thread (`MapController.kt:201`) and read in composition by
  `AttributionLine` (`AttributionLine.kt:63`), `AppCredits` and `CacheBudget`, and read off the main thread by
  `DirectSources` (`:636`, `:642`, `:658`) and `CacheBudget` (`:58`). Hypothesis, not confirmed: the object is first touched
  inside a snapshot on another thread, so its state is created in a snapshot that has not been applied, and the composition
  reads it on the main thread. The check: find the first touch on a relaunch that crashes (a stack at `MapViewport.<init>`
  or the first `bounds` read) and whether it is off the main thread.

## Not yet known

- (Decided, owner 2026-10-28: the open view is the most recent of the last fix, the last saved view and the pack's view,
  whichever was set last. Not yet checked: whether a saved view exists today and where the time of each of the three is kept.)
- Whether a fresh GPS fix at launch (a real device fix, not injected) would move the camera on open. Only injected fixes
  were tested.
- Whether the start-up crash repeats, and what first touches `MapViewport` when it does.

## Next

1. The centring task (TASKS.md, next wave) builds the owner's rule: the most recent of the last fix, the saved view and the pack's view.
2. The crash: a reproduction on the phone (relaunch after an injected fix, several times) with the first `MapViewport` touch
   logged, or a task that makes `MapViewport`'s state created on the main thread.
3. The centring task's before-and-after readings come from the debug routes above, in its report.
