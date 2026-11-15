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

Plan: three small parts. (1) The coordinator: one sentence in `.claude/skills/brief/implementer.md` (the Done when
line) and in `.claude/skills/wave/SKILL.md` (Plan and launch, step 1, and the critic's check in step 2): a task
that adds a screen, a row or a card line names the sweep step that opens it, new or existing. Both files come from
the base, so each is a departure row in ADR 0037 and a carry-back. (2) One implementer, `tools/sweep-diff.py`:
`--first` names are matched against every frame of both sweeps, not only the changed pairs
(`matches_first`, used at the changed-pair loop today); a name matching no frame at all prints it and exits
non-zero, a name matching only unchanged frames is listed in `changes.json` as `firstUnchanged`; `tools/sweep.ps1`
records the diff's failure as a failed step instead of only logging it. Tests beside the script's others.
(3) The same implementer, the board's Later line: a `drive` step in `part_features` of `tools/android-ui.py` on
the `rainier` pack: Settings, Navigation and tracking, Plan drives for and its help captured, then a drive planned
under each of the three values with the card captured (the fuel line where the two plans differ). Verify: a sweep
whose `ui/features` holds the new frames; `-First "settings,drive"` matches. Out of scope: the route sheet.
