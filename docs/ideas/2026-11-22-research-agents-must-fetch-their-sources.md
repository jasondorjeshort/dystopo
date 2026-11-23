---
date: 2026-11-22
kind: chore
---

# Research agents fetch every source they cite

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked.

The outside-research agent for proposal 0065 cited sixteen sources from memory; on a second pass, two of the nine it fetched said something other than claimed and seven were dropped as unreachable. `.claude/skills/brief/benchmark.md` would require a fetch per citation (URL, a short quote, yes/partly/no) and a closing list of anything unfetched, marked background and not cited.

Lever: the correction round of 2026-11-22, which cost a second agent pass. Outranks the sweep-timing chore as a cheaper fix with a wider reach (every benchmark and technical survey).

**What exists already (to be checked):** `.claude/skills/brief/benchmark.md` asks to cite each source but not to fetch it.

Plan: one chore pass (Sonnet-class), docs only. Owns: `.claude/skills/brief/benchmark.md`, the brief row of the departure table in `docs/design/decisions/0038-adopted-from-hatchery-after-t121.md`.
- In `benchmark.md`, replace "cite each one" with the rule: every cited source is fetched in this run; the report's source column gives the URL, a quote of at most two lines, and whether it supports the claim (yes / partly / no); anything not fetched or unreachable goes in a closing "Background, not cited" list and carries no weight in the recommendation. Apply it to both the app and the technical variant, and keep the 60-line report cap (the quote column counts).
- `benchmark.md` is under base-owned `.claude/` but has no Hatchery counterpart; extend the existing `.claude/skills/brief/` departure row to name it; no carry-back unless Hatchery later adopts benchmark.md (say so in the row).
- Verify: the file reads as one brief template under 40 lines; `node .claude/hooks/policy.js --cap-check` passes; `node .claude/hooks/test-hooks.js` green.
- Out of scope: the proposal and wave skills (they already route research through `benchmark.md`), re-running any past survey.
