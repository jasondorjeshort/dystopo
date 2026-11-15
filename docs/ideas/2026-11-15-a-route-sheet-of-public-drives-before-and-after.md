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
