---
date: 2026-11-22
kind: chore
---

# Recurrence across projects bubbles up to Hatchery (moonshot)

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked. Moonshot.

A process idea that comes up in two projects built on Hatchery (this one and another sibling) reaches Hatchery by itself as one idea carrying both sources, ADR 0065's recurrence extended across repositories: the triage scan already reads siblings for carry-back cells, and could read their `Came up again:` and Source lines for process ideas.

Why carry it: the base learns from its siblings without anyone relaying by hand.

**What exists already (to be checked):** Triage step 2 reads siblings' carry-back cells; nothing reads their ideas.

Question: Is cross-project recurrence a Hatchery decision to file there (it changes base-owned triage step 2 and ADR 0065's recurrence rule for every project), and should this file then go?
Recommendation: A; the mechanism lives in the base's triage scan and Hatchery's ADRs, so a Dystopo pass cannot build it, and the idea belongs in Hatchery's queue beside the ADR 0065 rule still waiting to be filed there (handoff, Waiting on user). Note the siblings beside this checkout are few, so the payoff is small until more projects exist.
Options:
A. File it as an idea in Hatchery (in the same sitting as the ADR 0065 idea), then delete this file as moved.
B. Keep it here at rest until a second Hatchery project is in active build.
C. Not worth building.

Answer:
