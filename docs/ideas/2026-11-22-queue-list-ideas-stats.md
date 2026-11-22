---
date: 2026-11-22
kind: chore
related: [2026-11-22-waits-that-wake-up-when-their-trigger-lands.md]
---

# `queue-list.js ideas --stats`: the queue's own figures

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked.

Files added and removed per week, origin read from the Source line, build rate per trigger and per model, recurrence counts. The measuring agent of 2026-11-22 spent minutes on regex guesses over commit subjects; with Source lines the figures become exact, and ADR 0065's three predictions need exactly these. `queue-list.js` is base-owned, so it is a carry-back or a project-side wrapper.

Lever: ADR 0065's predictions have no checker. Outranks the waking-Waits idea, which would use the same tool.

**What exists already (to be checked):** `node .claude/hooks/queue-list.js ideas` lists files and blocks; no figures.
