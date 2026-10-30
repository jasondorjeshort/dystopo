---
date: 2026-10-28
status: resolved (T162)
area: android / map camera / location
promoted_to: docs/reference/android-app.md (The open view)
---

# On a cold open the camera shows the first pack's view, not the location fix

## Question

The owner reports that the map's centring on app open is wrong (owner, 2026-10-28, next wave; TASKS.md › the
"Map centring on app open" bullet). What does the camera do on a cold start, and what is the location fix at that moment?

## What was tried

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

### Code reading (2026-10-28)

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

### The crash on the emulator (T162, 2026-10-30)

- With a log line at `MapViewport`'s first touch, 25 launches of a fresh API 35 emulator with the Rainier packs (17 of
  them after an injected fix) all made the state in the first composition on the main thread (`AttributionLine`,
  inside the composition's snapshot): no crash.
- With the caches over their budget at launch (a 1.1 GB file in the cache's `sources` folder, as a phone with full
  caches has), 6 of 6 relaunches made it first on the `cache-budget` thread, in the global snapshot, from
  `CacheBudget`'s eviction pass (`BudgetEngine.enforce` > `CacheGuard.predicate` > `viewBox` > `MapViewport.<clinit>`).
  On the emulator that ran before the first composition began, so it did not crash; with a larger cache index to walk
  first it lands later, which on the phone can be during the first composition.
- A unit test (`MapViewportTest`) reproduces the reported message in the Compose runtime the app ships: state made on
  another thread while a composition's snapshot is open is unreadable in that snapshot ("Reading a state that was
  created after the snapshot was taken").

## Result

- **The crash** is the hypothesis, confirmed in its specific form: `MapViewport`'s Compose state was made by whichever
  thread touched the object first; with the caches over budget at launch that is `CacheBudget`'s thread, and when that
  falls inside the first composition the composition cannot read it. Fixed in T162: the state is made by
  `MapViewport.prepare()` on the main thread in `MainActivity.onCreate` before `setContent`, and every reader off the
  main thread (`DirectSources`, `CacheBudget`, `AppCredits.mapLine` for `GET /attribution`) reads a plain volatile
  copy, `MapViewport.current`. After the fix: 20 relaunches after injected fixes, 10 of them with the caches over
  budget, no `IllegalStateException` and no crash.
- **The open view** follows the owner's rule: the newest of the last fix (persisted, `LastFix.kt`), the saved view and
  the primary pack's view, a link first, a fix in the first 10 s after open recentring once (60 s when the open view
  is a week old). Before T162 a relaunch after an injected fix opened at the pack's centre at zoom 12 (the emulator:
  the Rainier pack's middle, the fix not kept); after it the camera opened on the fix (0 m) with `openView.won: fix`,
  on Sunrise after a move there (`saved`) and on the pack after activating another (`pack`). The rule and its numbers:
  `../reference/android-app.md` › The open view.
- Not measured: a real device fix at launch on the owner's phone; the phone's numbers come with the next alpha.
