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

Plan: one implementer pass (Opus-class), project-side tooling, no change to the base-owned `queue-list.js` (a `--stats` flag there would be a later carry-back once the figures prove useful). Owns: `tools/ideas-stats.py` (new), `tools/test_ideas_stats.py` (new, the `tools/test_*.py` pattern), a row in `docs/reference/README.md` and a short `docs/reference/ideas-stats.md` (as `agent-cost.md` documents `agent-cost.py`).
- `python -I tools/ideas-stats.py [--since <date>] [--json]` reads `node .claude/hooks/queue-list.js ideas --json` for the current blocks and `git log --diff-filter=A/D --name-only -- docs/ideas/` for files added and removed per ISO week; per file it parses the Source line (source, generating model, trigger, `Moonshot.`), counts `Came up again:` (distinct sources) and `Use:` lines, and the block kind and age.
- Output: one JSON object: added/removed per week, open count by block (Plan, Question blank, Question answered, Waits, Waits at rest, untriaged), kept-and-built rate per source, per model and per tier (moonshot or not), recurrence counts, and the three ADR 0065 prediction figures (weekly additions averaged over four weeks; rest-review sittings left as null; share of `Came up again:` naming a triage session where readable).
- Removal reason is read from the deleting commit's subject only where it matches a known form; else "unknown" (no regex guessing beyond that, counted).
- Verify: the test runs against a fixture directory of five idea files and a stub log; then run on the real tree and spot-check three figures by hand against `git log`. No Gradle.
- Out of scope: the waking-Waits listing (its own idea and tool), tuning the bar, any edit to `.claude/`.
