---
date: 2026-10-31
kind: chore
---

# ADR 0050's wave 23 prediction is due

`docs/design/decisions/0050-closed-waves-move-out-of-the-board.md` predicts, to be checked at the close of wave 23,
that the board is under 300 lines at every resume between waves and that no task heading was lost or duplicated by a
move (the checks of `docs/tasks/README.md` passing on every run). Wave 23 is closing (its ideas were recorded at the
close on 2026-10-31, the post-wave sweep is running) and the line carries no Outcome. Found by the triage scan of
2026-10-31.

Plan: [no-check: the wave 23 record is written by the close's move] after the close's move of wave 23 into
`docs/tasks/wave-23.md`, the coordinator (the most capable model) reads
`git log -p -- docs/TASKS.md` from ADR 0050's step B (537c24a) to that move, records the board's line count at each
commit that opened a coordinator session (the handoff rewrites mark them), re-runs the README's checks over the wave 22
and wave 23 moves (every task heading once across the board and the records, each record byte-equal to the board text
it came from), writes the Outcome on the prediction's line (held or failed, with the highest count seen), writes a
finding if it failed, and deletes this file in the same commit.
