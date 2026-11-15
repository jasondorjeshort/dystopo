---
date: 2026-11-15
kind: chore
---

# A user-visible change is done when the sweep opens it, and -First fails when it matches nothing

Wave 33 added a Settings row (Plan drives for), its help and a line on the drive's route card, and no part of the
sweep or of the release run opens any of them: the before-and-after sheet of v0.1.30-alpha shows clock text and
map redraws, and the sweep's `-First "settings,route"` matched no changed frame without saying so (the log's entry
of 2026-11-15; the board's Later line). Wanted: the `brief` skill's implementer brief and the `wave` skill's plan
step ask of every task with a new screen, row or card line that a sweep step opens it (`tools/android-ui.py`
`features`, the a11y screens), as part of Done when; and `tools/sweep-diff.py` exits with an error when a `--first`
name matches no frame at all. The owner, 2026-11-15: sure.

## Open

- A drive in `features` on a public pack is the first case (the three values, the fuel line).
