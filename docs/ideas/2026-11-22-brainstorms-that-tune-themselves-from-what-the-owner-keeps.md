---
date: 2026-11-22
kind: chore
related: [2026-11-22-queue-list-ideas-stats.md]
---

# Brainstorms that tune themselves from what the owner keeps (moonshot)

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked. Moonshot.

With Source lines naming the model and the trigger, measure which offers the owner keeps (moonshots at wave closes, near-term after triage, one model against another) and shift the 3/3/3 mix and the bar from that, not from the doubling table alone.

Why carry it: it turns the owner's "log scaling" from a judgement into a measurement.

**What exists already (to be checked):** Needs the stats idea and a few months of Source lines.

Question: Keep this moonshot, which needs `2026-11-22-queue-list-ideas-stats.md` built and a few months of Source lines before it can measure anything, and if so on what footing?
Recommendation: A; nothing can be measured until the stats tool exists and ADR 0065's first prediction check (2027-01-31) has passed, and the bar is guidance the owner already overrides by hand.
Options:
A. Record at rest until the stats tool is built and ADR 0065's 2027-01-31 prediction is checked; then a proposal amending ADR 0065 decision 6.
B. Build a first read-only report (kept rate per trigger, per model, per tier) as soon as the stats tool lands, no change to the bar yet.
C. Not worth building: the owner's own judgement sets the mix.

Answer: A, at rest until the stats tool exists and ADR 0065's 2027-01-31 prediction is checked, then a proposal (the owner, 2026-11-22, multiple choice).
