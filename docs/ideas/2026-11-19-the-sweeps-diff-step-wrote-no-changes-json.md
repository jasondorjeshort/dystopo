---
date: 2026-11-19
kind: bug
---

# The sweep's diff step wrote no `changes.json` and the sweep went on

Wave 38's sweep `out/sweep-20261119-1231` [no-check: gitignored], run with `-Prev out/sweep-20261116-2212 -First
gps`, finished with `summary.json` `changes` null and no `changes.json` beside it; the sweep agent rebuilt it with
`tools/sweep.ps1 -SummaryOnly -Out <folder> -Prev <prev> -First gps`, which wrote it (43 changed, 9 added, 47
removed, 316 paired). The release notes (`tools/release-notes.py`) read those counts, so a release could have gone
out with no sheet line. Find why the step wrote nothing in the full run (the a11y part's crash left 44 frames
missing: a removed-frames path that fails quietly is the first guess) and make the step fail loudly, naming the
cause in `steps.tsv` and `summary.json` `failed`, or retry once.
