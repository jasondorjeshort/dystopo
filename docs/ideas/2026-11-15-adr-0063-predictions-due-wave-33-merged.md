---
date: 2026-11-15
kind: chore
---

# ADR 0063's two merge predictions are due: T284 and T285 merged

The ADR 0012 scan of 2026-11-15. `docs/design/decisions/0063-least-fuel-as-a-second-drive-metric.md` predicts,
"check when the task that builds the metric merges": fewer than half of the six public drives differ between least
time and least fuel; and "check when the phone task merges": the second plan adds less than the first plan's time
again on the emulator's public drive. Both merged in wave 33 (`docs/tasks/wave-33.md`). T285's record says "the
second plan is slower than the first when cold", which reads as the second prediction failing at least when cold;
the count of differing drives is in T284's merge body and has not been set against the first. The label
prediction (by 2027-01-15) is not due.

2026-11-16: T291's finding (`docs/findings/2026-11-15-a-drive-as-a-speed-profile.md`) has the first prediction's
figure on wave 34's packs: two of the six public drives take another route under least fuel (the two Mont Blanc
valley town drives) and four the same one, so fewer than half. The same finding makes the ADR's turn fuel the
error, and its amendment is the first routing work after the build-process wave; the two Outcome lines are best
written in the commit that amends it.

Plan: the coordinator, as the architect here, reads the merge bodies of T284 and T285, writes the Outcome on each
of the two lines (held, held in part, or failed), files a finding by the `finding` skill if the second plan's
time failed it (the measured cold and warm times, and what it means for the idea
`2026-11-15-the-card-offers-the-other-route.md`, which would run a second plan for every drive), and deletes this
file in the same commit.
