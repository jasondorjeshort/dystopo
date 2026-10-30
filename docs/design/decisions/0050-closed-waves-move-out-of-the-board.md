# 0050. Closed waves move out of the board into one record each

Date: 2026-10-30
Status: accepted

Record: the idea `docs/ideas/2026-10-29-closed-waves-move-out-of-the-board.md` (the owner's, from
objection 5 of the critic review of Hatchery's proposal 0003, 2026-10-13); task T168 (the rules, wave
22) and its step B chore (the move); no proposal (a repository layout, not a format other tools read).
Amends ADR 0020's "Kept as they are" paragraph on `docs/TASKS.md`.

## Context

`docs/TASKS.md` is read from the bottom at every coordinator resume (`wave` skill, Resume step 2), yet
on 2026-10-29 it carried every wave since the first: about 1070 lines and 440 kB, exempt from the
300-line cap by `edit.capExempt` in `.claude/hooks/policy.json` (ADR 0020). A fresh session loads the
whole file to find the one open wave. The constraints, read in `tools/merge-log.py` (T168's report):
`add_status` finds `### Tnn.` on the board and refuses a task that already has a Status line, so a
task must be on the board while its wave is open and may leave once the wave closes; every `### Tnn.`
heading must stay exactly once across the board and the records; the post-edit hook warns about paths
that no longer exist in any edited doc, so a closed record needs `anchorExempt` like the proposals,
decisions and findings. The wave 21 follow-ups lived only in T149 to T157's Status lines, which a
record would take out of sight at resume (the idea's addition C).

## Decision

- **One record per closed wave** in `docs/tasks/wave-NN.md` (two-digit), the wave's section moved
  verbatim with its heading demoted to the title, its Status lines included; `docs/tasks/README.md`
  holds the format, the move recipe (a script a Haiku-class chore extracts and runs with exact checks,
  stopping on the first failure) and the index table, one line per record.
- **The board holds** the open wave (its tasks lack a Status line), Owner actions pending, `## Later`
  and the follow-up lists. It is no longer in `edit.capExempt`; `docs/tasks/*` is in `edit.anchorExempt`.
- **A wave moves at its close**, as step 5 of the `wave` skill's Close the wave: before the move the
  chore lifts every unfinished follow-up from the wave's Status lines into `## Later` as one line each
  naming the task, and the coordinator reads that list before the commit (addition C). The first move
  lifts from T148 on; older waves' follow-ups were triaged when each next wave was planned.
- **Checks before the commit** (the README's script): task headings unique and their count unchanged
  across the board and the records; each record byte-equal to the board's old section at `HEAD`; the
  board under 300 lines; a clean tree before and only the move after; `PrivacyCheck` clean on each
  record; `tools/test_merge_log.py` ok.
- **Unchanged:** `merge-log.py`'s code, `docs/PLANNING.md`, `CHANGELOG.md`, every task's text.
- **Deferred, with its trigger:** the dated log in `docs/PLANNING.md` (about 580 kB, read before
  architectural changes) gets the same treatment, one record per closed wave, when a session that
  reads it for a design change finds the tail is not enough or when the file passes 1 MB (addition D).

## Consequences

- A resume reads a board of a few hundred lines; the history is one `docs/tasks/` file per wave,
  still greppable by task id. The move is one chore per wave close, about a minute.
- `wc -l` on the board grows again during a wave and shrinks at the close; the cap check warns, not
  refuses, in between.
- A link into the board by wave section breaks once the wave moves; links by task id are found with
  `grep -r '### Tnn' docs/TASKS.md docs/tasks/`. The README says so.
- What would supersede this: a board kept in a database or an issue tracker, which ADR 0043 (no pull
  requests) and the offline-first working method argue against.

Predictions (the trigger scan reads these):
- Prediction, check at the close of wave 23: the board is under 300 lines at every resume between
  waves, and no task heading was lost or duplicated by a move (the README's checks all passed on
  every run).
- Prediction, check when the dated log passes 1 MB: the deferred move of `docs/PLANNING.md`'s log
  has been done by the same recipe, or a finding says why not.
