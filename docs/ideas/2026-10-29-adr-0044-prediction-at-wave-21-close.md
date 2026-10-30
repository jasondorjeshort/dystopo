---
date: 2026-10-29
kind: chore
---

# ADR 0044's wave 21 prediction is due

`docs/design/decisions/0044-research-when-in-doubt-and-leave-settings.md` predicts, to be checked
when wave 21 closes, that every wave 20 and 21 report names the research behind each open choice
(user-facing or technical) or the decision that settled it, and that every setting added says why it
is a setting. Wave 21 closed on 2026-10-28 and the line carries no Outcome. Found by the triage scan
of 2026-10-29.

Plan: the coordinator reads the wave 20 and 21 merge commits (`git log --merges`, whose bodies are
the task reports) for the research citations and the settings' reasons, writes the Outcome on the
prediction's line (held or failed, with the counts of reports and settings checked), writes a finding
if it failed, and deletes this file in the same commit.
