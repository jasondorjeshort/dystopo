---
date: 2026-11-10
status: open
area: process / wave sizing (ADR 0048, ADR 0057)
---

# The cost per task doubled between waves 28 and 30, and wave 31's two lanes cost the same per task as wave 30's one

## Question

ADR 0057 (one Gradle-using agent at a time, 2026-11-04) predicted that running tasks one after another would cost
no more on the 7-day meter than wave 28's parallel run (within 0.5 points of 1.25 a task). Wave 30's close read
about twice that and the `wave` skill's figure was revised to 2.5 (Plan and launch, step 3). Was the serial lane
the cause, and does a two-lane wave cost less per task?

## What was tried

The readings the allowance sentences collect: wave 28 (parallel, 11 points for 10 tasks, finding
`2026-11-02-wave-cost-figure-overstated-three-closes.md`); wave 30 (one lane, 27 to 72 on the 7-day meter for
eleven tasks, three releases and two critics, the dated log of 2026-11-08); wave 31 (two lanes, the owner's
widening of 2026-11-09: 2 at planning, 52 at 06:57 on 2026-11-10 with seventeen tasks merged, one release
published, one critic, five research agents, a triage and a day's idea work, and one release still to come).

## Result

- Wave 30: about 2.5 a task after the fixed costs (wave 3, releases 2.5 each), twice wave 28's.
- Wave 31: 52 minus 2 at planning, minus the wave's 3 and one release's 2.5, over seventeen tasks is about 2.6 a
  task; with the second release and the close it will read about 55, the allowance's 53 within its five-point band.
- So two lanes cost the same per task as one lane: the lane width (ADR 0057's serial order) does not explain the
  doubling from wave 28, and ADR 0057's second prediction failed for a reason outside the ADR.
- What changed between wave 28 and wave 30 that the figure does not separate: a verifier per task running the
  device tour (T246's script from wave 31; by hand in wave 30), fix rounds with a second verification, reports
  that became merge bodies (ADR 0043), and research agents per open choice (ADR 0044). Unproven which weighs most.

## Next

At wave 32's close, read the meter per task with the verifier script's own figures (its summary's seconds and the
agent's token counts the harness reports at each hand-back) against the implementer's, so the two halves of a task
are known; revise the 2.5 figure then if the band is missed. Until then the figure stands.
