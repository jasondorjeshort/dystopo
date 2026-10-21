---
date: 2026-10-21
status: open
area: android / direct sources / memory
---

# An unvisited direct Mont Blanc z9 view right after a full tour crashed the app out of memory

## Question

Does the phone's direct (no-pack, no-server) DEM rendering fit the 192 MB Java heap after a full
device tour, or does a large unvisited view push it over? T123's implementer hit the crash while
testing the request log and ruled its own change out.

## What was tried

- On `dystopo35-T123` (API 35, the Rainier packs and the default region on the phone, no server set),
  a full `tools/android-check.py` tour, then an unvisited direct view at Mont Blanc z9: the app
  crashed with an out-of-memory error on the 192 MB heap.
- The same view on a fresh process: the heap peaked at 186 to 191 MB and the view went idle at 45 s.
- The request log (T123, a 4,096-line ring) is a few hundred kB at most; the verifier's tour showed
  the Java heap at 160 of 201 MB after the full tour before any such view.

## Result

The pressure comes from direct DEM rendering of a large z9 view (many tiles rendered from the
elevation source at once), not from the request log: a fresh process barely fits it, and a process
that has run a tour does not. Not reproduced by the verifier (not attempted); the numbers are the
implementer's single run and the verifier's memory block.

## Next

A task: measure the direct view's peak per zoom with the tour's memory block (`/health` memory,
`dumpsys meminfo`), find what holds the heap after a tour (tile caches, MapLibre's own, the shade
and contour generators), and bound the concurrent direct renders or trim the caches under pressure
(`onTrimMemory`), with a check in the tour that a cold direct z9 view after a full tour stays under
the heap. Public areas only (Mont Blanc, Bonneville).
