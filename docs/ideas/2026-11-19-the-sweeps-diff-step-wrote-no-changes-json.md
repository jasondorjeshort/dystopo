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

Plan: reproduce on the two folders (rerun `tools/sweep-diff.py <prev> <out> --first gps` as the full run called it,
and read `out/sweep-20261119-1231`'s `steps.tsv` and `summary.json` for the `diff` line [no-check: gitignored]) to
find whether the step did not run, ran and exited 0 without writing, or wrote and was overwritten by the summary.
Then make silence impossible: `tools/sweep.ps1` checks after the call that `changes.json` exists and is newer than
the call's start, and otherwise records `diff` failed with a named reason in `steps.tsv` and `summary.json`
`failed`, retrying the call once; `tools/sweep-diff.py` exits non-zero on any path that writes no `changes.json`,
removed frames included. Verify: a fixture test of the removed-frames path in the script's tests and one
`-SummaryOnly -Prev` run on a folder with frames missing. Out of scope: the before-and-after sheet's layout.
