---
date: 2026-10-16
status: open
area: android / first run / direct sources
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

## Result

Open. Two of three cold starts met T110's numbers; one did not, and its stall was not CPU: a
36 s gap with no tile request on a quiet device, then a fast finish. Candidates not yet ruled out:
a range read blocked on the network (the emulator's first DNS or TLS to the public sources), a
MapLibre tile request queue waiting on an earlier request, the direct-source scheduler's own wait,
or the host (the emulator crashed with GL errors on two boot attempts in the same session and booted
on retry). The tour's 60 s first-view wait would not have covered this run either.

## Next

- Reproduce on a fresh AVD with the request log of the phone's loopback server and the direct
  source's per-read timings (`GET /firstrun/direct` stages plus a per-request log) and name what the
  36 s waited on. A host-side packet capture or the emulator's `-verbose` network log would tell a
  network wait from a scheduler wait.
- If it is the first network round trip, the first view could start its reads earlier or the tour
  could warm the connection; if it is the scheduler, a timeout and retry on a stalled read.
- Wave 16 Android sweep item.
