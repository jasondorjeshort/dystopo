---
date: 2026-11-22
kind: chore
related: [2026-11-21-one-clock-for-the-whole-app-past-and-future.md]
---

# Waits that wake up when their trigger lands

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

After the triage of 2026-11-22 about ten ideas wait on three triggers (the one clock, ADR 0060's cell downloads, T331). Nothing notices when one fires, so a merged clock would leave its five ideas asleep until someone remembers. `queue-list.js ideas --waits` groups the waiting ideas by trigger, and the triage scan lists those whose trigger has landed (a task merged, an ADR accepted, a release published) as ready to triage again.

**What exists already (to be checked):** `node .claude/hooks/queue-list.js ideas` lists blocks, not triggers; the triage skill's scan reads ADR deferrals and predictions, not idea `Waits:`. The script and skill are base-owned: a carry-back to Hatchery, or a project-side wrapper.
