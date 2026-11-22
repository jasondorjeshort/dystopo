---
date: 2026-11-22
kind: chore
---

# Research agents fetch every source they cite

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked.

The outside-research agent for proposal 0065 cited sixteen sources from memory; on a second pass, two of the nine it fetched said something other than claimed and seven were dropped as unreachable. `.claude/skills/brief/benchmark.md` would require a fetch per citation (URL, a short quote, yes/partly/no) and a closing list of anything unfetched, marked background and not cited.

Lever: the correction round of 2026-11-22, which cost a second agent pass. Outranks the sweep-timing chore as a cheaper fix with a wider reach (every benchmark and technical survey).

**What exists already (to be checked):** `.claude/skills/brief/benchmark.md` asks to cite each source but not to fetch it.
