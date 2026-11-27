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

**Reproduced and gated (T379, 2026-11-26, API 35 emulator, debug build, 200,007 files from `POST /debug/cache/fill`,
the snapshot removed so a start must walk).** The start with no activity: `adb root`, then `adb shell content query
--uri content://<package>.gpxshare/none` (the FileProvider starts the process: `am_proc_start` reason `content
provider`, `curProcState=19`, cached and empty, as the phone's preload). On `main`, with the emulator's cached apps
freezer on, the `tile-index-tiles` thread had 7.9 s of CPU when Android froze the process 12 s after its start; with the
freezer off (`settings put global cached_apps_freezer disabled`, a reboot; the phone did not freeze it), the thread took
16.4 s of CPU over 30 s and walked all 200,007 files in 21.8 s (`walkMs`), the process 28 s of CPU in all, with no
screen. On the branch the same start: the `tile-index-tiles` thread took 0 ticks in 21 reads over 10 min 15 s (the process 1.2 s of CPU in all, its other threads and the debug reads), `GET /debug/cache` showing `state` `indexing`, `entries` 0, `index.gate` `open` false, `why` `no screen and no notified job`, `held` true, the snapshot not even read, and the launch stage `cacheIndex` ended at the hold. Home during a walk begun on screen (300,013 files): the thread stopped at 2.7 s of CPU and took 0 ticks in the 2.5 min after, held at 59,690 files filed; back on screen it went on and ended (`walkMs` 215,914 of which `heldMs` 202,670). With a recording running, Home did not stop it: the walk ended in the background (`why` `under recording`). Not tried on the device: the walk under an area download's notification (unit-tested); the phone's day in the background (ADR 0071's prediction, the owner's action). Since T379 the walk waits for the screen or a notified job
(ADR 0071).

**A kill no longer costs a whole walk (T390, 2026-11-27, the same emulator and fill, 200,040 files).** Changes are journalled beside the snapshot (`tiles.index.journal`) and replayed at the next start; the walk writes checkpoints and resumes after a kill (`android-app.md` › The online tile cache's index). A whole walk with the screen on took 16.9 s (`walkMs`, five checkpoints of 56 to 92 ms each). After 500 tiles rewritten at a new size and a `kill -9` on screen, the next start read the snapshot and replayed 2,500 journal records in 127 ms, walked nothing (`walkedThisLaunch` 0), and was ready 1.85 s after the cache's construction, its byte total equal to before; after a change, Home and `am kill`, the stop's write had folded the journal and the start read the snapshot alone (ready at 1.9 s). A `kill -9` at 137,277 files walked resumed from the checkpoint at 136,552 and walked the other 63,488 (6.7 s); Home at 83,048 files wrote a checkpoint of all of them before the walk waited, and after `am kill` the next start walked the other 116,992; both ended equal to the disk (200,040 files, 57,196,733 B, counted with `find`). A start with no activity read neither the snapshot nor the journal. The contour cache, which walked at every start, keeps a snapshot by the same code: 50,016 files walked in 4.7 s, read from its snapshot in 1.6 s at the next start. A journal append cost 76 µs a record on the emulator against 4.4 ms for the put it belongs to (2,000 synced puts). Not tried on the device: a device stop (the boot check), unit-tested.

**The other launch stages (T387, 2026-11-27, the same emulator, the debug build, `cached_apps_freezer` off, a start with
no activity read for 300 s after its first 3 s).** On `main` the whole process took 0.20 % of a core over the 300 s (no
thread above 0.09 %: the online probe's thread, which probes the sources every 30 s, then the UI watchdog's 1 s beat
and the memory sampler's line a minute) and 6.7 s of CPU from its start, most of it the launch itself; but every
stage ran: the world overview's check, the updater, the cache budget's engine, the memory sampler (five lines written),
the areas' resume check (`start` trigger) and the trips' corridors (a 2.2 s reconcile), and the contour warm-up of the
tile service, which wrote 11 contour tiles (up to 256 on a fresh pack). On the branch the same start: 0.17 % of a core
(the probe's thread 0.08 %, its 1 s and 2 s map checks; the probe itself skips), 4.2 s of CPU from the start, nothing of
the app's data written but the start trace's own report, `GET /debug/launch` reading `world`, `cacheIndex`, `updater`,
`budget`, `sampler` and `pending` deferred. A recording restarted by the system (`kill -9`, `am_proc_start` reason
`service`) resumed under its notification with the sampler and the walk running under it and the rest deferred; an
area download cut by `kill -9` resumed from its cursor when its WorkManager job started the process (reason `service`,
`SystemJobService`), under its notification, with `pending` still deferred; an activity arriving in such a process opened
every deferred stage at once (`openedMs` 32.0 s, all done by 32.04 s) and the map drew. The stage table is T387's report (merge commit). Since T387 a start
without a screen runs only the settings, the tracker, the packs, the online and tile services and the servers.

## Next

1. Done (T379: no walk without a screen; T390: the resumable walk and the journal). The fix, for wave 49 (the owner, 2026-11-25), under the owner's rule that the app does no silent work in the
   background (`docs/ideas/2026-11-25-no-silent-background-work.md`, deleted by T387; ADR 0071) [no-check: the idea is deleted]: no walk at a start without a screen (wait for
   an activity, or run it under an ongoing notification); a walk that writes its progress so a kill resumes it; a
   kill after a change that does not cost a whole walk (record the changes since the snapshot instead of discarding it).
2. A test on the phone after the fix: exit-info and batterystats over a day with the app left in the background.
3. Done (T387). Whether other launch stages (downloads' resume, the updater, the memory sampler, the cache budget) do work at a
   start without a screen, by the same reads.
