---
date: 2026-10-26
status: open
area: android / routing / memory
---

# The routing graph for a vector pack is built whole in memory on the phone and runs out of a 512 MB heap

## Question

The owner's phone filed five problem reports from v0.1.13-alpha in two days (issues 23 to 27): an ANR, a close
for memory, two copies of one crash, and an `OutOfMemoryError` on the `dystopo-routing` thread. What do they
share, and what is the one with a stack?

## What was tried

- `gh issue view` on all five. Four distinct events (issues 23 and 26 are the same crash, filed twice, same
  timestamp): an ANR with the user closing it (PSS 614 MB), the system closing the app for memory (PSS 718 MB,
  about 16 minutes later), a crash with no stack (PSS 672 MB, an hour after that), and the next day an
  `OutOfMemoryError`. The phone runs Android 12 with a 512 MB growth limit.
- The OOM's stack is R8-obfuscated (`r8-map-id-b17e28cd...`). The release build's `mapping.txt` on the second PC
  carries that same id, so it was retraced with the SDK's `retrace` (the plain mapping lookup does not match:
  R8 positions are compact).

## Result

- The memory is the common thread: three of the four events are at PSS 614 to 718 MB, and the fourth is an OOM at
  the heap's limit ("Failed to allocate a 2097168 byte allocation with 1075112 free bytes ... growth limit
  536870912").
- The OOM retraces to `RoutingGraphBuilder.assemble` (`RoutingGraphBuilder.kt:285`, the crossings step, an
  `Arrays.copyOf` growing an array) called from `RoutingGraphBuilder.build`, `RoutePlanner.run`
  (`RoutePlanner.kt:235`) and `ensureRegion` on the `dystopo-routing` thread. So the phone was building a
  routing graph for a whole vector pack in one pass, with the snapped vertices and every line held in arrays,
  while the rest of the app already held several hundred MB.
- What starts a build: the first long-press, `POST /route` or the trip editor (`RouteSession.kt`), and every
  finished "Download this region": `RegionDownload.recordArea` (`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/online/RegionDownload.kt`)
  starts the place-search index and `RoutePlanner.ensure` together on one thread, and `ensure` builds the graph of
  every pack on the phone whose graph file is missing or stale, the new pack included. So a region download is
  followed at once by two whole-pack builds on top of the app's working set. Which pack it was is still not known
  (the report removes pack names).
- The owner (2026-10-26): all five reports are filed, none came directly from toggling airplane mode, but airplane
  mode was in heavy use in the period they fall in (2026-10-25 17:27 to 18:45 UTC, and 2026-10-26 11:55 UTC). No
  code path from a connectivity change to a graph build was found; region downloads made in that period are the
  likelier trigger.

- Issue 29 (2026-10-26 20:45 UTC, same build and phone): an `OutOfMemoryError` on the **main** thread, a 48-byte
  `StringBuilder` allocation with 4.8 MB left of the 512 MB limit ("<1% of heap free after GC"). The main thread is
  only the victim: the heap was already full, so any allocation anywhere can be the one that fails. It says the
  pressure is app-wide and not only inside the graph build (not retraced; the stack is in `StringBuilder` from
  an obfuscated string-building frame called off a main-looper message).

- T149 (2026-10-27), the graph and search part: a finished download no longer starts either build, and the search
  box no longer builds every index at launch (it starts them only while open or holding text); every graph and index
  build first asks `HeapGuard.admitBuild` for its estimated peak (trim, up to 10 s of waiting, refusal; one build at
  a time), and a refused graph says so on the route card instead of an `OutOfMemoryError`. Measured heap growth
  through a graph build on an API 35 emulator (576 MB heap): the Rainier outdoors pack 65 MB (58 k routable vertices),
  a 1 x 0.7 degree box around Rainier 143 MB (347 k); the Washington 3 degree box (7.9 M vertices, 1.9 M nodes) does
  not fit that heap at all (a JVM needed more than 2 GB), and a "Download this region" over a z10 London view
  (1.2 M vertices, estimate 630 MB) was refused on the emulator. So the one-pass builder caps routing at about a
  1 degree rural box on a phone; the streaming build and the server-shipped graph stay on the Later list. Status
  stays open for the background-CPU part (T150).
- T150 (2026-10-27), the background part, on an API 35 emulator with the Rainier packs, location granted, the map
  following the puck and a geo fix every 2 s (per-thread ticks from `/proc/<pid>/task/*/stat`, 60 s after HOME): main
  before the change, the UI thread 0.95% and the process 1.5% with one activity, 2.2% and 3.4% with six stacked
  activities (the stacking reproduced: `am start -n`, then five launcher taps, Activities 6); the T150 build 0.00% for
  the UI thread and `HeapTaskDaemon`, 0.15% for the process, Activities 1 after the same taps. On this Android the
  system already throttled the background listener to one request per 10 minutes (`dumpsys location`: `{bg, na}`,
  `@+10m`, inactive), so the emulator's remaining UI-thread cost came from the polling loops and the stacked activities,
  not from fixes; after T150 `dumpsys location` lists no registration of the package in the background. The country
  list's sizes (computed when the list opens, not at launch) take 69 s and 60 s of one core for 240 countries; the
  worker now pauses in the background. Not reproduced: the phone's 50% UI thread (its background state was not
  captured); a real-phone `GET /debug/cpu` pair after HOME on a T150 build is the check, and the status stays open for it.

## Next

- Bound the build by the heap: build in tiles or strips and write as it goes, or refuse with a sentence past a
  size, as the area guard does (`shared/.../areas/AreaLimit.kt`).
- Reproduce on an emulator with a 512 MB heap: "Download this region" over a wide view, then watch
  `GET /route/status` and the heap (`GET /diagnostics`) through the graph and search builds that follow; then
  defer or serialise those builds behind the heap, or build the graph lazily per tile as routes reach it.
- A possibility the owner raised (2026-10-27), an idea and not a decision; the agent doing the work decides: stop
  starting the search index and the graph when a download finishes (`RegionDownload.recordArea`), and build each on
  first use instead. `RouteSession` already calls `RoutePlanner.ensure()` when a route is started, so routing
  would not lose anything; search would need the same. The pack's tiles are not affected (the server builds
  them; the phone only downloads them), except in the no-server `runLocal` path.
- Background/foreground cycle test (2026-10-27, debug build on a 3 GB emulator, heap max 576 MB, small Rainier
  pack; raw rows were in the gitignored `out/memleak/`): 25 HOME-and-launcher relaunches leave Java heap, native
  heap and PSS flat, no pid change, no OOM. So a plain leak across leave-and-return is **not** shown. The run does
  not cover a whole-pack graph build, or a cold start after a background kill.
- Found on the way: when the task's root intent is not the launcher intent (for example the task was started by
  a notification `PendingIntent`, a `dystopo://` link or `am start -n`), each launcher tap creates **another**
  `MainActivity` and `MapView` (26 stacked after 25 cycles, about 40 MB native each, 1 GB PSS in total).
  `MainActivity` in the manifest has no `launchMode` (standard). Whether the owner's phone gets into that state
  is a guess; check with `adb shell dumpsys meminfo <pkg>` (Activities should read 1). A cheap fix to evaluate:
  `launchMode="singleTask"` on `MainActivity`.
- The phone itself (2026-10-27, wireless adb, `dumpsys meminfo` and `dumpsys activity exit-info`): `Activities: 1`,
  `ViewRootImpl: 1` in a process 19 hours old, so the stacking above is not happening on this phone now. Exit
  history of the previous processes: three `APP CRASH` records (PSS 524, 567 and 698 MB at the first three;
  220 MB, importance 100 = foreground, for the one at the time of issue 29) and **two kills by the system for
  `EXCESSIVE CPU USAGE`** while the app was cached (importance 400): "excessive cpu 8410 during 300089", limit
  2%, so the app burns about 2.8% CPU in the background and Android kills it. The user's next return is then a
  cold start. Open: what is running in the background (a build, a poll, tile or track work); the 220 MB PSS beside
  a "heap full" OOM does not add up and may be a stale sample.
- Background CPU (2026-10-27, agent on the phone plus code read; the first point re-checked by hand): the app was
  in the background at 26% user CPU (`dumpsys cpuinfo`); `top -H` showed the main thread at 50% and
  `HeapTaskDaemon` (GC) at 20%, every named worker thread at 0%. So it is main-thread work that allocates, not a
  graph, search or download build. The system then killed it again mid-sampling (70 s of CPU in 5 minutes, about
  23%). Suspects, in order:
  1. The device-location listener (`MapController.startDeviceLocation`, 1 s, 0 m, main looper) is stopped only by
     `detach()`, which `MainActivity.onDestroy` calls; `onStop` and `onPause` never stop it (checked). Each fix
     runs `onFix`, the puck and `MapOrientation.onFix` on the main thread, and follow mode moves the stopped
     MapView once a second.
  2. About ten Compose `LaunchedEffect` polling loops (400 ms to 1 s; `OnlineOverlay` rebuilds the region and
     service JSON every second) are not tied to the lifecycle, so they run while stopped.
  3. Lesser: `AheadPrefetch` polls every 2 s; the heading sensor listener's release and the NSD multicast
     wakelock (15 min in batterystats) were not confirmed.
  Fix to evaluate: stop the location listener in `onStop` and restart it in `onStart`; run the polling loops with
  `repeatOnLifecycle(STARTED)` or one shared foreground flag. Verify by two `/proc/<pid>/task/*/stat` snapshots
  60 s apart after leaving the app (main thread and `HeapTaskDaemon` should fall to about 0). Not measured: the
  per-thread delta over 60 s (the process was killed first).
- The unwritten trace of the crash in issues 23 and 26 may itself be an OOM that left the handler no memory to
  write with (a guess).
