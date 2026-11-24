---
date: 2026-11-24
status: open
area: flights / the map's drawing cost
---

# The map's render thread never rests while flights are drawn on it

## Question

What does the flights overlay cost while nothing changes on screen? Asked by wave 48's T364 before and after it
built "Show flights" (ADR 0068), because the owner suspected overlays cost battery on a hike.

## What was tried

T364's implementer, on an emulator at Rainier, zoom 9, the map still: ten minutes in each state with six aircraft
fed by `POST /aircraft/replay` (no request to the feed), reading `GET /debug/cpu` per thread and `GET /aircraft`'s
counters. The table is in ADR 0068.

## Result

While the map draws flights, the map's render thread used 597 s of CPU in 600 s, about one whole core, against
0.0 s with the layer hidden, covered or tracking off. The main thread (10.8 s) and the aircraft thread (0.92 s)
are small beside it. The layer pushes its marks once a second (604 pushes in 600 s), and the marks are moved along
their track between polls, so every push differs from the last. Whether one push a second explains a render thread
that never rests is not known: nothing was tried with a slower push, with a push only when a mark moved a pixel,
or with the marks unmoved. The figure is an emulator's, whose drawing runs on the host's processor; a phone's
figure and its battery cost are unmeasured. T364 removed the same cost from two states (a sky view over the map,
and the map with "Show flights" off).

## Next

1. On a phone: the same four states by `tools/android-battery.py` with a flights scenario, and the render thread's
   CPU by `GET /debug/cpu`. If the shown state is within noise of hidden there, close this as an emulator's
   artefact.
2. If it is not: find what keeps the thread awake (one replay with the push at 5 s, one with marks not moved
   between polls, one with a push skipped when no mark moved a pixel at the map's zoom), then build the cheapest
   that holds the marks' motion.
