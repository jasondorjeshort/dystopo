---
date: 2026-11-15
kind: chore
---

# ADR 0062's two merge predictions are due: T281 and T282 merged

The ADR 0012 scan of 2026-11-15. `docs/design/decisions/0062-drive-turn-costs-on-directed-edge-states.md` predicts,
"check when the task that fills the table merges": the owner's shape routes along the main road, and no public-area
drive's time rises by more than 5 percent beyond its own turn seconds; and "check when the task that builds the
search merges": between 2 and 3 directed drive edges per drive node on the public packs. Both tasks merged in wave
33 (`docs/tasks/wave-33.md`). T281 measured 1.99 and 2.01, so one pack sits a hair under the range; T282's record
gives 5 to 37 s of turn time on six drives and two Mont Blanc drives that changed road, and the rise beyond the
turn seconds has not been set against the 5 percent. The third prediction (by 2027-01-15) is not due.

Plan: the coordinator, as the architect here, reads the merge bodies of T281 and T282 for the per-drive times,
writes the Outcome on each of the two lines (held, held in part, or failed with a finding by the `finding` skill;
1.99 against "between 2 and 3" is held in part and says so), and deletes this file in the same commit.
