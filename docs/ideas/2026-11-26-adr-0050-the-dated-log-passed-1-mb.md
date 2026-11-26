---
date: 2026-11-26
kind: chore
---

# ADR 0050's deferred log move is due: `docs/PLANNING.md` passed 1 MB

The triage scan of 2026-11-26 found the deferred item and its prediction in
`docs/design/decisions/0050-closed-waves-move-out-of-the-board.md` with the trigger met: "the dated log in
`docs/PLANNING.md` ... gets the same treatment, one record per closed wave, ... when the file passes 1 MB" and
"Prediction, check when the dated log passes 1 MB: the deferred move of `docs/PLANNING.md`'s log has been done by the
same recipe, or a finding says why not". `wc -c docs/PLANNING.md` read 1,067,528 bytes on `main` at 64007414 (995 kB
at the scan of 2026-11-22).

Plan: the architect is the coordinator (the most capable model), the move a chore. (1) The coordinator settles the
shape in a short amendment to ADR 0050: where a closed wave's log entries go (appended to its record
`docs/tasks/wave-<nn>.md` under a `## Log` heading, or a sibling `docs/tasks/wave-<nn>-log.md`), which part of the
`### Phase 0 log` stays in `docs/PLANNING.md` (the open wave and the last closed one), and that `tools/merge-log.py`
keeps inserting into `docs/PLANNING.md` unchanged. (2) A Sonnet-class chore moves the entries by a script added to
`docs/tasks/README.md` beside the board's, with the same checks: each entry byte-equal to its old paragraph at `HEAD`,
the entry count unchanged across the files, no record over the 300-line cap (else split as the amendment says),
`PrivacyCheck` clean on each record, `python -I tools/test_merge_log.py` ok. Deliverable: the move, the Outcome on the
prediction's line, and the deferred item marked done. If the amendment finds the move not worth it, a finding says why
and the Outcome cites it. Out of scope: the design sections of `docs/PLANNING.md` above the log.
