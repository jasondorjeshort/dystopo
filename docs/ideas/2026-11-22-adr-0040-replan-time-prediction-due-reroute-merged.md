---
date: 2026-11-22
kind: chore
---

# ADR 0040's replan-time prediction is due: the reroute task has merged

The triage scan of 2026-11-22 found the prediction in `docs/design/decisions/0040-stops-and-reroute.md`
("check at the reroute task's PR: a hike replan inside one pack with the graph file built measures under
2 s on the API 35 emulator; over 5 s means reconsider") with its trigger met and no Outcome recorded.

Plan: a Sonnet-class chore, read-only on code. Find the reroute task's measured replan time in its wave
record under `docs/tasks/` and its merge body (`git log --grep` on the task id); if no figure was taken,
measure one hike replan inside one public pack (Rainier) on the API 35 emulator through the reroute's debug
route. Deliverable: the Outcome on the prediction's line (held or failed, with the figure and its source).
If over 5 s, also a finding in `docs/findings/` and a Question to the owner on amending ADR 0040's limits;
the architect is called only then. Out of scope: any change to the reroute itself.
