---
date: 2026-11-15
kind: chore
related: [2026-11-14-sweep-frames-carry-the-place-they-show.md]
---

# A route sheet: the same public drives drawn before and after, every release

Wave 33 changed which road a drive takes (two Mont Blanc drives left the expressway's loop ramp, two more changed
under least fuel) and the only evidence was numbers in the reports: nobody saw the old and the new line side by
side. The screens have `tools/sweep-diff.py` and its `changes.png`; routes have nothing. Wanted: a sweep part that
plans a fixed list of public drives, hikes and rides on the `areas.json` packs (devtool `route`, each `--prefer`
value for a drive), draws each on its map, and beside the previous sweep's makes one sheet of the pairs whose line,
time, distance, turns or fuel index moved, with the figures under each. The owner, 2026-11-15: yes.

## Open

- The list: the six drives of wave 33's reports are a start; which hikes and rides.
- Whether a route that changed fails the sweep or only shows (it should only show: a wave may mean to change it).

Plan: one implementer. A list file `tools/route-sheet.txt` (name, pack, mode, from, to): the six drives of
`docs/reference/devtool-commands.md` (the `route` line), each under `--prefer time`, `fuel` and `quick`, and the
hikes and rides of `tools/routing-tour.txt` on the `rainier` and `montblanc` packs. A sweep part `route-sheet` in
`tools/sweep.ps1` runs devtool `route` per line with `--out` into `<sweep>/route-sheet/<name>.png` and keeps each
JSON; a line whose pack is not in `out/` is skipped by name in the part's JSON, not failed. A small
`tools/route-diff.py` pairs by name with `-Prev`, calls a route changed when distance, time, turns, `fuelIndex` or
a hash of its points moved, and writes `route-changes.png` (old and new picture side by side, the figures and
their differences under each) and `route-changes.json`; `summary.json` gets the count. A changed route only
shows; a route not found fails the part. Verify: the script's tests with two planted JSON sets; one sweep against
`out/sweep-20261114-2352` (no pairs yet, every route listed as new), the sheet viewed. Out of scope: the phone's
planner, private packs (the list holds `areas.json` names only).
