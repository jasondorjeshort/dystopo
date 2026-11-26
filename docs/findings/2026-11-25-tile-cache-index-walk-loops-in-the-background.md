---
date: 2026-11-25
status: open
area: android / online tile cache, background CPU and battery
---

# The online tile cache's index walk loops in the background, and Android kills it for CPU

## Question

What does the app cost in the background on a real phone? Asked in the owner's phone session of 2026-11-25
(v0.1.43-alpha, release build, a Samsung phone on Android 12, wireless adb, read-only reads).

## What was tried

- `dumpsys activity exit-info`: 16 recorded exits from 2026-11-23 to 2026-11-25; 14 are `EXCESSIVE RESOURCE USAGE`,
  `excessive cpu` with the process cached (`importance=400`, `state=empty`), at 3 % to 53 % of one core over Android's
  five-minute window (limit 2 %, or 25 % in the first minutes after leaving the screen). Kills before and after the
  update to v0.1.43-alpha on 2026-11-24, so not new in it. No crash, ANR or watchdog line from the app in logcat.
- `dumpsys batterystats` over 7 h 43 m since a charge: the app on screen 18 s, running 42 m, CPU 15 to 24 min (two
  counters; most of it kernel time), 31.2 mAh of Android's estimate (29.0 of it CPU), 8th of all uids, about 5 % of the
  estimated drain; 7 kills for CPU in that span. A day at that rate is about 100 mAh by Android's model, about 250 mAh
  scaled to the gauge's measured drain (its model counted 582 of about 1480 mAh): 2 % to 6 % of a 4300 mAh battery.
- The latest two starts' `am_proc_start` reason was `IpmLaunch` (the maker's predictive preload) with no activity of
  ours resumed: the process ran without the user opening it. The app is on the battery-optimisation exempt list (the
  T172 card), which does not stop these kills. No foreground service, no notification, no job of ours listed.
- Per-thread CPU after the owner opened the app for 30 s and pressed Home (`top -H`, `/proc/<pid>/task/*/stat`): the
  thread `tile-index-tiles` at 100 % of a core, `HeapTaskDaemon` at 59 %, everything else near 0. Android killed the
  process 99 s after it left the screen (`excessive cpu 97610 during 300056 dur=98891 limit=25`).
- `dumpsys diskstats`: the app's data 74.4 GB, its cache 27.0 GB (the owner keeps large areas).

## Result

The cause is `TileCache`'s index (`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/online/TileCache.kt`,
`index`, `indexOrRead` and `walk`; T242, T249, T267). At every process start the index's thread either reads the
snapshot (`<dir>.index`) or walks the whole store with one `lstat` per file. `loadSnapshot` discards the snapshot when
the dirty marker is there, that is, whenever the last process was killed after any change to the index since the last
write (`the last run changed the index after its last write`). The walk over a store of this size runs longer than
Android lets a cached process spend CPU, so it is killed mid-walk; the snapshot is written only after a walk ends, so
the next start, preloaded by the system or the app's own work, walks from zero again. The loop sustains itself until a
walk finishes with the app on screen. The heap daemon's share is the walk's garbage.

Not measured: how long a whole walk takes on this phone in the foreground, and how many files the store holds.

## Next

1. The fix, for wave 49 (the owner, 2026-11-25), under the owner's rule that the app does no silent work in the
   background (`docs/ideas/2026-11-25-no-silent-background-work.md`): no walk at a start without a screen (wait for
   an activity, or run it under an ongoing notification); a walk that writes its progress so a kill resumes it; a
   kill after a change that does not cost a whole walk (record the changes since the snapshot instead of discarding it).
2. A test on the phone after the fix: exit-info and batterystats over a day with the app left in the background.
3. Whether other launch stages (downloads' resume, the updater, the memory sampler, the cache budget) do work at a
   start without a screen, by the same reads.
