---
date: 2026-10-12
status: resolved
area: server / contour tiles
---

# Two z9 contour tiles over Hawaii answer 500 with an IndexOutOfBoundsException

## Question

T74's verifier ran the world and demo tours against its server. Which failures were not T74's?

## What was tried

`/contours/9/33/227.pbf` and `/contours/9/34/227.pbf` (Hawaii, z9) on main, before T74's change.

## Result

Both answer 500 with an `IndexOutOfBoundsException` in contour generation (pre-existing on main; T74
log entry, 2026-10-12). Separately, Flevoland is a flat polder with no contours and is no use as a
contour check in the demo tour (`tools/demo-tour.txt`).

## Next

For the next sweep task: reproduce with `devtool serve` and a request for each tile, find the index
that runs past the grid (likely the Hawaii geoid or the antimeridian-adjacent window), add a test; drop
Flevoland from the contour expectations in the demo tour.

## Resolution (T87, 2026-10-13)

Cause: `ThreeDepSource` (and `CopernicusSource`) read every cell of a window at the overview level the
first cell chose. Hawaii's mostly-ocean 3DEP cells carry fewer overview levels, so a z9 window over
several cells asked one of them for level 5 of 5 (`RemoteTiff.prefetch`). The level is now the finest of
the cells' own choices, which every cell has (`RemoteTiff.commonLevel`, `CommonLevelTest`). Both tiles
answer 200 through `serve --live` (`9/33/227` is open ocean west of Kona and is empty; `9/34/227` has
lines). Flevoland: `tools/demo-tour.txt` has no Flevoland view (its 21 views are Rainier), and no other
tour expects contours there, so there was nothing to drop.
