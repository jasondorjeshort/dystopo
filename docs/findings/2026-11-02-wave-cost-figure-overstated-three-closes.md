---
date: 2026-11-02
status: resolved (the wave skill's figure revised in the same commit)
area: process / wave sizing (ADR 0048)
---

# The wave cost figure of 3 points a task and 5 a wave overstated three closes by two to four times

## Question

ADR 0048 predicted, to be checked when wave 24 closed, that the per-task figure of the `wave` skill
(Plan and launch, step 3) would have been revised at least once from the recorded closes, and that
no closed wave would miss its figure by more than five points twice in a row. Did it hold?

## What was tried

The triage scan of 2026-11-02 read the allowance sentences of the wave 22 to 26 records in
`../tasks/` (the 7-day meter at planning, the cost assumed, the meter at the close) and the skill's
step 3 text.

## Result

Failed on both clauses.

| Wave | Tasks | Assumed (3 a task + 5) | Read at the close | Per task, fixed cost included |
|---|---|---|---|---|
| 22 | 18 | 56 | 24 | 1.3 |
| 23 | 14 | 47 | 25 | 1.8 |
| 24 | 9 | 32 | not comparable (the weekly reset fell inside the wave) | |
| 25 | 3 | 14 | not comparable (read after the reset, 8) | |
| 26 | 6 | 23 | about 5 | under 1 |

The figure was never revised: wave 26's close said "the per-task figure stays at 3 until a second wave
on this plan confirms it (one reading is not a model)", although waves 22 and 23 had already read
under half of it. Every comparable close missed by more than five points, all on the low side, so the
allowance never bound a launch but would have refused a wave that in fact fit; the skill's "revised at
each close" had no owner step, so no close did it.

Resolution in the same commit: the figures become 1.5 points a task and 3 a wave (over waves 22 and 23
that gives 30 and 24 against 24 and 25 read; wave 26 would still read under, which the owner's RAM
rule and the shorter tasks of that wave explain in part), and the close step of the `wave` skill now
revises the figure in the commit that writes the close's reading, so the check cannot be deferred
again. The next two closes test the new figure; a miss over five points twice in a row revises it again.

## Next

None: resolved. ADR 0048's prediction line carries this outcome.
