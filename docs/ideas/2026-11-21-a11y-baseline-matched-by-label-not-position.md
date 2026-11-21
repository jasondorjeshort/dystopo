---
date: 2026-11-21
kind: chore
---

# The a11y baseline matched by screen, kind and label first, position only to tell twins apart

`tools/android-a11y.py` matches a finding to a baseline line by screen, kind, label and bounds within a tolerance
(`matches()`, `MATCH_DP` and `MATCH_Y_DP`). When a card grows a row, every line below it moves past the tolerance, so
the same, already-accepted findings read as new and the old lines as gone. Wave 43's sweep went red on exactly this
(the long-press card gained "Sky from here"; six accepted contrast lines moved about 49 dp), and T318, a whole
implementer, verifier and merge, was rebaselining churn with no code change.

The owner, 2026-11-21: record it and do it basically right away, next pass perhaps.

Plan: in `matches()`, a finding matches a baseline line with the same screen, kind and label regardless of position
when that (screen, kind, label) occurs once on each side; position (the nearest bounds) only pairs lines when the same
label occurs more than once on a screen (the two "‹ Settings" lines of `settings-data:1.0`). A finding whose label is
new, or a kind that changed (contrast to overlap), is still new. Report a moved match as `moved` (count and list) so a
reviewer can still see layout change without a red part. Tests: `tools/` has the a11y tool's tests or a new
`test_a11y_match.py` with the wave 43 case (the long-press lines at y 810 matched against y 761), a twin pair, and a
kind change. Verify: run against `out/sweep-20261121-w43/a11y.json` with the pre-T318 baseline: the long-press lines
match as moved, Choose from server and the first-run:2.0 overlap still new. Docs: the sweep skill's T270 paragraph.
Out of scope: rewriting existing baseline lines.
