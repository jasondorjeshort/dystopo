---
date: 2026-11-16
kind: bug
related: []
---

# The a11y baseline misses a known line when its size reads in kB

The closing sweep of wave 34 (`out/sweep-20261115-2123`) was red on one part, `a11y`, for one finding it called
new: `online-card:1.0`, contrast, "Viewed earlier: # kB of # GB". `tools/a11y-baseline.json` lists that same
line as "Viewed earlier: # MB of # GB" at both font scales: the key folds the digits and keeps the unit, so the
known finding reads as new whenever the viewed-earlier store is under a megabyte when the screen is captured.
The part rerun alone was green with nothing new (`out/sweep-20261115-2123-a11y`), the store having grown.

## Steps

1. A debug install whose viewed-earlier store holds under 1 MB; `python -I tools/android-a11y.py` over the
   `online-card` screen.
2. `new` lists the contrast finding with "kB"; with over 1 MB viewed it is matched by the baseline.

## Open

- Fold the size unit in the key (`tools/android-a11y.py`, where "30 MB" and "3.4 MB" already key alike), or
  list the kB lines in the baseline with the reason in the log. The first needs no new baseline line.
- The finding itself (the line's contrast on the online card) stays the baseline's.
