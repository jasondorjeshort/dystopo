---
date: 2026-10-21
status: resolved
area: android / direct sources / memory
promoted_to: docs/reference/android-online.md
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
- T127, on `dystopo35-T127` (same image, no server): `android-check.py` gained the step (after the tour
  and the background step, clear the online and source caches, go to Mont Blanc z9, sample
  `/stats` `memory.javaHeapUsedBytes` every 0.4 s, read logcat for `OutOfMemoryError`) and
  `--direct-only` for a cold view per zoom in a new process. A heap dump (`am dumpheap`, `hprof-conv`,
  a class histogram and the owners of every array of 256 kB or more) after a tour.
- Tried and dropped: a render limit from the heap used at admission (16 MB a render: ten renders were
  admitted before any had allocated and the heap still reached its max, with `OutOfMemoryError`s);
  a new decoded-block cache on each trim (the geoid grids keep the cache they were opened with, so the
  dump found 23 MB of geoid blocks in a replaced cache beside the new one); 12 MB for renders from z12
  (a z11 view's 256-px DEM tiles are z12: 193 MB); 24 MB a render (four at once: 181 to 194 MB).

## Result

Reproduced before the change: the full tour then the cold Mont Blanc z9 view killed the process
(`OutOfMemoryError ... target footprint 201326592`), and in a fresh process every cold z8 to z11 view
at Mont Blanc and Bonneville sat at the 192 MB max (182 at Bonneville z11), z12 at 127 and 132 MB.
Two causes. Ten renders at once (each holds the 4 MB COG blocks it reads until it ends) beside a
decoded-block cache of a quarter of the heap; and what a tour leaves on the heap where a fresh
process holds about 40 MB: 97 MB before the view in a first bounded build that dropped the pack
pyramids (24 MB each) and the cliff memo, held by the server's 32 MB geoid offset memo (31 MB of
offsets for windows rendered once) and the online pyramid's decoded tiles (10 MB), the dump showed.

Bounded (T127, `online/HeapGuard.kt`): a render starts while the running ones, at 32 MB each, fit in
the max heap less a reserve, the heap without renders and the block cache (now an eighth of the heap):
three at once on 192 MB. The pyramids and the cliff memo are dropped by the application's
`onTrimMemory` (the activity is stopped in the background) and when a render finds the heap short;
the geoid memo is 4 MB on the phone. After the change, the same per-zoom views peak at 91 to 166 MB
(table in the reference), the heap holds 49 to 50 MB after a tour, and the tour's direct step passed
four times on fresh AVDs (peaks 118 to 155 MB, bound 0.95 of the max, no `OutOfMemoryError`). The
cost is time: the cold Rainier z8 tour view takes 73 to 93 s where it took 50 to 61 s, and Bonneville
z11 no longer goes idle within 60 s (25 s before). A larger heap (`android:largeHeap`, not set) would
let the admission run more renders.
