---
date: 2026-11-10
kind: chore
---

# A random place of the day in the sweep: one date-seeded land point rendered through every source

The stale range cache (finding `docs/findings/2026-11-10-range-cache-keeps-blocks-across-a-republish.md`) was found
because the republished cell lay under one of the tour's places; 55 other stale cells sat unseen until the repair.
The world tour renders the same 27 public places every time (`devtool tour`, `areas.json`), so a republish, a datum
oddity or a seam bug anywhere else waits for a user. The owner, 2026-11-10: "Sure."

- A sweep part `random` (`tools/sweep.ps1`; the nightly workflow's tour too): a point seeded by the date (the same
  point for every run that day), rerolled until the elevation source has data there (Copernicus returns no data
  over the sea), rendered at z9 and z12 through the live source with the tour's checks (no exception, contours
  present, the hillshade's range plausible), the sources that answered and the cells fetched in its report; the
  PNGs on the sheet.
- The point and the result in `summary.json`, so a failure names the place to add to `areas.json` as a fixed case.
- A seed option in `tools/sweep.ps1` for reproducing a day.

## Open

- The run fetches a new cell each day (tens of megabytes); the cache grows by that unless the part deletes its
  cells after a green result.
- Only public points: the point is random, but the report names a cell; a cell near a personal area is a
  coincidence the privacy scan cannot tell from a hint, so the part skips cells listed in the private terms.
