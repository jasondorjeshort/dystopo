---
date: 2026-10-25
kind: bug
related: [2026-10-25-crash-capture-and-github-issue-reports.md, 2026-10-25-heap-diagnostics-page-in-release-builds.md]
---

# The whole app froze right after a view was saved as a pack

Seen by the owner on a real phone (2026-10-25): the map showed "Saved as pack view-…" and then nothing
responded, neither buttons nor the map. That means the UI thread was blocked. The network and permit
waits all run on worker threads, so the cause is the UI thread reaching into state the workers hold.
No thread dump was taken, so the path below is a lead from reading the code, not a root cause.

- `online/OnlineOverlay.kt` refreshes once a second on the UI thread and calls `service.region.json()`
  (which reads `PackManager.activeName()` and `inUse()`), and `OnlineView.currentOnMain`, which calls
  `tiles.online?.viewSource(…)`.
- When a save finishes, `online/RegionDownload.kt` calls `packs.activate()`, which is `@Synchronized`
  on `PackManager` and runs `DystopoApp.reload()`: it opens the new tiles, closes the old service and
  its packs while server threads may still be reading them, then `map.onMain { loadStyle }` waits up
  to 5 s for the UI thread. `PackManager.jumpTo` adds another `onMain` round trip.
- Each step has its own timeout, but nothing guards the whole chain. A lock the UI thread needs, held
  by a worker that is itself waiting for the UI thread, freezes everything, and the 5 s timeouts can
  pile up into an "isn't responding" kill.

The rule this should leave in place: **the UI thread never takes a lock and never does disk or
network I/O.** It reads snapshots (a volatile field or a `StateFlow`) that workers publish.

Reproduce (unconfirmed): online mode, a city view at z12–14, "Download this region" with a small zoom
range, and watch the screen as it switches to the new pack. Repeat a few times, also while panning.

## Open

- The actual blocked frame. The first freeze the watchdog below catches names it; until then the fix
  targets the pattern, not a proven line.
- Whether `reload()` should close the old packs only after the server's in-flight reads drain
  (reference counting) rather than at once.

Plan: one wave 20 task owning `online/OnlineOverlay.kt`, `online/OnlineView.kt`,
`online/RegionDownload.kt`'s `json()` and its activation call, `packs/PackManager.kt`'s `activate` and
`jumpTo`, `DystopoApp.reload`, a new `app/MainWatchdog.kt` and its debug route, the tests, and the
`android-app.md` and `android-debug-routes.md` lines.
1. The overlay's state becomes a snapshot that a background ticker publishes; the composable only
   reads it. `currentOnMain` stays on the UI thread but takes no lock (the view source is read from a
   published value).
2. `activate` and `reload` open and close off the UI thread and hold no lock across `onMain`; the UI
   thread only swaps the style and moves the camera, posted rather than awaited where the caller does
   not need the result.
3. StrictMode in debug builds (thread policy: disk reads and writes, network, slow calls; penalty
   log), its violations counted on `GET /health` so the device tour reports them.
4. A UI-thread watchdog in every build: a worker posts a heartbeat to the UI thread every second;
   when one goes unanswered for 3 s it writes `Thread.getAllStackTraces()` to the app's private files
   (the store the crash-capture idea reads), once per freeze. `POST /debug/freeze?ms=` blocks the UI
   thread on purpose so the watchdog can be tested.
Done when the repro above runs ten times on the emulator with no freeze and no watchdog dump, the
device tour reports no StrictMode violation from the UI thread (or lists each with its owner), and
`/debug/freeze?ms=4000` produces one dump that names the blocking frame.
