---
date: 2026-10-16
status: resolved (T115)
area: android / first run / direct sources
promoted_to: docs/reference/android-online.md
---

# A cold no-server z8 view took 62 s on a quiet fresh AVD, with a 36 s gap in tile requests

## Question

T110 (PR #112) measured the no-server cold z8 view on five fresh AVDs at 9.7 to 14.5 s and
attributed T105's 43 s to a busy device (a 3DEP block decodes in 1.8 to 3.3 s on a just-booted
device against 0.01 to 0.1 s when quiet); the tour's first view now waits 60 s and reports
`idleSeconds` and `deviceCpu`. The verifier of PR #112 saw one run that this explanation does not
cover. What stalls the view when the device is quiet?

## What was tried

Verifier of PR #112, fresh AVD `dystopo35-T110-verify` (API 35, four cores), debug build of the
rebased branch, `-Server none`, the public Rainier packs, host CPU 20 to 97% with other agents'
emulators running; `android-check.py` with the tour file, `GET /firstrun/direct` before and after.

- Fresh AVD, first boot: quiet wait 49.3 s, then the z8 view took 62.4 s and failed the tour
  (idle and labels). `deviceCpu` said the device was quiet during the view (other work 0.1 cores,
  the app 0.2). The `GET /firstrun/direct` timeline shows no tile request at all between 13.5 s and
  49.6 s, then everything finished within about 5 s. Direct-source range reads were slow (0.2 to
  2.7 s each).
- Reboot after `-Stop` (disk caches kept): quiet wait 18.5 s, z8 view 4.2 s, tour 10/10.
- Second fresh AVD (empty caches): quiet wait 52.3 s, z8 view 5.0 s, tour 10/10; 25 range reads
  19.8 s in total, 22 blocks decoded in 7.0 s.

T115 (2026-10-18), one fresh AVD as the task's time box allowed: `dystopo35-T115` (API 35, four
cores, `-gpu host`), the debug build of the branch base (no direct-source change), `-Server none`,
the public Rainier packs, one other agent's emulator on the host. Boot 56 s; the tour started about
three minutes after `sys.boot_completed`.

- `android-check.py --server none` on the tour's first view alone (`8/46.85/-121.72`): relaunch
  4.3 s, quiet wait 6.2 s (0.07 cores besides the app), z8 view 11.2 s (`idleSeconds` 7.1,
  `deviceCpu` others 0.39, app 1.22), 1/1 ok with labels. No gap: the cold view met T110's numbers.
- `GET /firstrun/direct` after it: `stages` renderWaitMs 0, coarseFirstHeldMs 0, renderFetchMs
  23,158, renderEncodeMs 145, contourDemWaitMs 11,591; `sources` rangeReads 21 (12,344 ms summed),
  rangeShared 7, diskHits 12, blocksDecoded 18 (400 ms), blockWaits 11, geoidMs 3,496, resampleMs
  1,011; `rangeStalls` 0; 24 requests, 16 at most in flight, 7 DEM renders (one z8, six z9; 3.3 s
  on average, 4.3 s at most), 5.1 MB, no failure. Logcat had no network warning; the only
  contention lines were threads waiting on the geoid's one-time load (`Once.get`, `ProjGeoid`),
  which is the 3.5 s `geoidMs`.
- The timeline (67 events) starts at the relaunch's own start view (one contour tile, 0 to 374 ms),
  then nothing until 7,303 ms, then the z8 view's requests; the first source reads (64 KB, the first
  DNS and TLS included) took 0.5 to 0.6 s, and the view's last tile came at 11,701 ms.
- That empty stretch is the quiet wait: `android-check.py` relaunches, waits for a quiet device, and
  only then moves the camera, while the timeline counts from the process's first event. The same
  holds for the verifier's run: its gap from 13.5 to 49.6 s lies inside its own 49.3 s quiet wait,
  and its view's direct work ended about 5 s after the camera move. So the 62.4 s was the map not
  reaching idle after the DEM and contours were done, which this timeline did not record: it had
  no events for OpenFreeMap's vector tiles, the other request a no-server view makes.
- Mechanism check: `adb root`, then `iptables` and `ip6tables` DROP to the OpenFreeMap tile host's
  three IPv4 and three IPv6 addresses, camera to an unvisited z8 view (Glacier, 48.70, -113.80).
  The DEM and contour events ended within about 7 s of the move, but `/health` said not idle
  (`pendingTiles`, `notFullyLoaded`, `frameNotFullyRendered`) at every 5 s sample for 98 s,
  `/screenshot?wait=90000` returned at 95.8 s without idle, and the screenshot had hillshade and
  contours but no water, roads or labels; `/online` stayed `online` with 0 failures. With the rules
  removed, an unvisited z8 view at Mont Blanc was idle in 19.6 s (`osmFetched` 1 before, 18 after).

## Result

Resolved as far as one reproduction can go. The "36 s gap in tile requests" was not a stall: it
was the tour's own quiet wait, before the view had asked for anything. What made the 62.4 s view
slow happened after its DEM work, and its signature (DEM and contours done in seconds, then idle
and labels failing at the 60 s wait, the direct timeline silent) is what a stalled OpenFreeMap
fetch produces: `DirectSources.osm` waits up to 30 s per attempt with two attempts
(`SourceIo.get(url, 30_000, 2)`), longer than MapLibre's own 10 s client timeout, so the loopback
request stays open and `/health` reports `pendingTiles`; the range reads' 4 s stall guard (T88)
does not cover it. Which connection stalled in the verifier's run cannot be shown now. Ruled out
for that run: the device's CPU (quiet), the direct-source scheduler and the range reads (they
finished), and the first DNS or TLS round trip to the elevation hosts (0.5 to 0.6 s here).

Since T115 the timeline has `o?`, `o<status> <ms>` and `oERR <ms>` for OpenFreeMap fetches and, in
debug builds, `camera z<zoom>` where a script's view starts, and the device tour reports
`notIdleBecause` for a view that did not go idle (`../reference/android-online.md`,
`../reference/android-app.md`), so a recurrence names its own cause.

## Next

- Not built (the wave is minimal; a follow-up in T115's PR): a stall guard on OpenFreeMap fetches,
  failing a fetch that gets no byte for a few seconds, as the range reads do, so a stalled
  connection costs one missing tile instead of a minute of a busy loopback request.
