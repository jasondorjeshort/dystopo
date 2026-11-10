---
date: 2026-11-10
kind: chore
---

# ADR 0057's two predictions are due: the next wave's close has passed

The ADR 0012 scan of 2026-11-10. ADR 0057 (`docs/design/decisions/0057-one-gradle-using-agent-at-a-time.md`)
predicts, "check at the next wave's close", that free RAM stayed above 6 GB throughout and that the cost per task on
the 7-day meter stays within 0.5 points of the figure then current. Wave 30 closed on 2026-11-08 (v0.1.24-alpha;
`docs/tasks/wave-30.md`) and neither line has an Outcome. The cheaper-verification idea records wave 30 at about 2.5
points a task, "twice wave 28's", which reads as the second prediction failing.

Plan: the coordinator (architect) records both Outcomes on their lines from wave 30's readings (the free-RAM
figures taken after each merge; the meter at planning and at the close against the task count), and, if the cost
prediction failed, a finding with the figures and whether the serial lane or something else (the emulator guard,
the tour's growth) explains it; wave 31's two-lane figure is read at its own close, not here.
