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

Plan: one pass, `devtool` and the sweep's scripts. `devtool tour --random [--seed <yyyy-mm-dd>]`: a land point
seeded by the date (UTC), rerolled while the elevation source has no data there, while its cell matches a private
term or while it lies inside a personal area's box (`devtool.local.json`, read as `PrivacyCheck` reads it; the
reroll is silent, so no report says a cell was skipped), rendered at z9 and z12 with the tour's checks; the point,
the sources that answered and the cells fetched in the JSON. A sweep part `random` in `tools/sweep.ps1` and the
nightly workflow's tour step run it; `summary.json` carries the point and the result, the sheet the two PNGs; the
part deletes the cells it fetched after a green result and keeps them after a red one. Done when: `check` green
with a test that one seed gives one point and that a listed cell is rerolled; three seeds rendered and their PNGs
viewed; a red forced by a broken source names the point. Out of scope: adding a failure to `areas.json` (by hand).
