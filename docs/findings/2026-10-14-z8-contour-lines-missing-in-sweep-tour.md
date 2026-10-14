---
date: 2026-10-14
status: resolved
area: android / contour tiles over a DEM pack
promoted_to: docs/reference/android-online.md
---

# The device tour's z8 view draws no contour lines again (post-wave 13 sweep)

## Question

The post-wave 13 sweep on `main` at 91bd3cd (T88 merged) failed its device tour 9/10 in both runs:
the first view, `region-z8` (`8/46.85/-121.72`, the Rainier DEM pack z8 to z14 and the OSM pack
installed, debug build, a fresh API 35 emulator) drew 0 contour lines although contour tiles were
served and generated. T87 fixed the same symptom before v0.1.5-alpha. What makes the z8 contour tiles
empty now, and why does a rerun on the same emulator fail too?

## What was tried

- `tools\sweep.ps1` from the main checkout, 2026-10-14 (`out\sweep-20261014-1427`): device tour run 1
  view 0 settled in 14.1 s, checks all true except `lines`; tiles dem 5 served, osm 8, contours 8
  served and 8 generated (avg 1337 ms, max 3766 ms, 0 disk cache hits); layers `contour-minor` 0,
  `contour-index` 0, `contour-labels` 0, hillshade and OSM layers drawn. Run 2 on the same emulator:
  8.5 s, contours 5 served and 5 generated in at most 1.6 ms each (avg 1.1 ms), again 0 lines. The
  other nine views passed in both runs (48 and 50 contour tiles).
- Screenshots `device\tour1-0.png` and `tour2-0.png` viewed: hillshade, land cover, roads, park
  boundaries and labels, no contour lines anywhere, the two frames identical.
- T88's verifier (PR #96, 2026-10-14) had seen view 0 on a fresh emulator fail by the 30 s idle rule
  with lines present, and `main` before T88 fail with no lines drawn; the implementer's follow-up names
  the just-booted emulator's CPU. The sweep's failure is different: the view went idle within 30 s
  and still had no lines, and the rerun's tiles were "generated" in about 1 ms.

## Result

Open. The 1 ms generations on the rerun suggest the z8 contour tiles are answered empty from a
cache or from a plan that reads no DEM. T88 changed how contour tiles that a DEM pack's bounds
overlap are made on the direct path (read at the hillshade's zoom, taking the pack's tile wherever
the pack answers one; `OnlineTiles`, `DirectSources`, `TileService`), which is the first suspect;
whether the sweep's tour runs with a server set or with none is to be read from `tools\sweep.ps1`
(`android-check.py --port ...`), since T88's measurements were with no server.

Resolved in T98 (2026-10-14, PR #101): the sweep's tour ran with a server set but unreachable, not with no
server. A debug build on an emulator uses `http://10.0.2.2:8765` (the host's live viewer) until a server
is set, and the sweep had stopped that viewer to rebuild the install jars. With a server set, a contour
tile the pack's bounds overlap but do not contain samples a coarser grid (z7 for a z8 tile, z6 for z7)
that the online pyramid read from the server only, so every cell was NaN and a 17-byte empty tile went
out; the rerun's 1 ms was the fetcher in backoff. Reproduced on `main` with an unreachable server
(0 lines, the sweep's screenshot), fixed by reading at least the pack's first zoom
(`OnlineTiles.serverContourOptions`, `PackFloorContourTest`): 10/10 twice as the sweep runs it, with a
dead server and with no server. T88's direct path was not involved.
