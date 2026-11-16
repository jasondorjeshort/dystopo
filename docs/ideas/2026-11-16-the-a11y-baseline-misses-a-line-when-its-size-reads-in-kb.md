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

Plan: one small pass in `tools/android-a11y.py` and `tools/test_android_a11y.py`, no device needed. `key_label`
also folds a byte unit after a folded number (`B`, `kB`, `KB`, `MB`, `GB`, `TB`) to one token, `# B`, so "Viewed
earlier: # kB of # GB" and "# MB of # GB" key alike as "Viewed earlier: # B of # B"; the two `online-card` lines
of `tools/a11y-baseline.json` are rewritten to that key in the same commit, the reason in the commit message.
Tests: kB, MB and GB forms key alike; a label with no unit is unchanged; the existing digit cases still pass;
`python -I tools/test_android_a11y.py` green. Verify: an `a11y` part run lists no new `online-card` finding (with
under 1 MB viewed earlier if it can be arranged). Out of scope: the contrast finding itself, other folds.
