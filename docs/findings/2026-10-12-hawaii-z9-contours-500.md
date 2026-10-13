---
date: 2026-10-12
status: open
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
