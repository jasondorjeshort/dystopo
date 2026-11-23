---
date: 2026-11-22
kind: chore
related: [2026-11-21-one-clock-for-the-whole-app-past-and-future.md]
---

# Waits that wake up when their trigger lands

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

After the triage of 2026-11-22 about ten ideas wait on three triggers (the one clock, ADR 0060's cell downloads, T331). Nothing notices when one fires, so a merged clock would leave its five ideas asleep until someone remembers. `queue-list.js ideas --waits` groups the waiting ideas by trigger, and the triage scan lists those whose trigger has landed (a task merged, an ADR accepted, a release published) as ready to triage again.

**What exists already (to be checked):** `node .claude/hooks/queue-list.js ideas` lists blocks, not triggers; the triage skill's scan reads ADR deferrals and predictions, not idea `Waits:`. The script and skill are base-owned: a carry-back to Hatchery, or a project-side wrapper.

Use: 2026-11-22, coordinator brainstorm (Opus 5.5): the same trigger resolver reads ADR predictions paragraph by paragraph, so triage step 1 stops miscounting Outcomes written on a continuation line (`grep -v " Outcome "` misses them, ADR 0041 for one); a carry-back to Hatchery once built (accepted by the owner).

Plan: one implementer pass (Opus-class), project-side tooling; the owner's Waits wording is read, never rewritten or reformatted. Owns: `tools/ideas-waits.py` (new), `tools/test_ideas_waits.py` (new), the triage row of ADR 0038's departure table, one line in `.claude/skills/triage/SKILL.md` step 1, a row in `docs/reference/README.md`.
- `python -I tools/ideas-waits.py [--json]` lists every file ending in `Waits:`, grouped by trigger; `at rest` ones in their own group. It resolves only references it can check: a task id (`T331`: merged when `git log main --merges` names `task/T331`), an idea file named in backticks (landed when the file is gone from `docs/ideas/`), an ADR number with a stage (status line of the ADR), "in a release" (a tag after the Waits line's commit). Anything else is listed as `unresolved` with its text, never guessed.
- Output per group: trigger text, files, state `landed | waiting | unresolved`, evidence (commit or tag). Triage step 1 gains one line: run it and list `landed` groups as "ready to triage again" in the report (the owner then reopens; triage still never rewrites a Waits).
- Verify: the test uses a fixture tree with one landed T-id, one deleted idea file, one prose trigger; then run on the real tree and check the clock, ADR 0060 and T331 groups by hand.
- Out of scope: a trigger grammar for Waits lines, the stats figures (`2026-11-22-queue-list-ideas-stats.md`, separate tool, same `tools/ideas-*` pattern), a carry-back to Hatchery until it has run through two triages.
