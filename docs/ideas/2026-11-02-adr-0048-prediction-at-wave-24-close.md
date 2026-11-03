---
date: 2026-11-02
kind: chore
---

# ADR 0048's wave 24 prediction is due

`docs/design/decisions/0048-size-a-wave-by-its-close-out-cost-and-interacts-with-lines.md` predicts,
to be checked when wave 24 closes, that the per-task figure of the `wave` skill (Plan and launch,
step 3) has been revised at least once from the recorded closes, and that no closed wave missed its
figure by more than five points twice in a row. Waves 24, 25 and 26 have closed (2026-11-01 and
2026-11-02) and the line carries no Outcome. The skill still carries the starting figures read off
waves 20 and 21, and wave 26's close read 32 on the 7-day meter against 47 budgeted. Found by the
triage scan of 2026-11-02.

Plan: the coordinator (the most capable model) reads the allowance sentences of the wave 22 to 26
records in `docs/tasks/`, writes the Outcome on the prediction's line (held or failed, each clause
with its readings), revises the per-task and per-wave figures in the `wave` skill from those readings
in the same commit when they warrant it, writes a finding if the prediction failed, and deletes this
file in that commit.
