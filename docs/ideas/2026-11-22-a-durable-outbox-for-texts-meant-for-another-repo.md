---
date: 2026-11-22
kind: chore
---

# A durable place for texts meant for another repository or session

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked.

The Hatchery idea drafted for ADR 0065 and three research reports lived in the session's scratchpad, which a reboot or cleanup can clear. One tracked place (a `docs/outbox/` folder listed on demand, or a handoff subsection) would hold a text until it is filed elsewhere, with the target and who files it; the handoff skill reads it at resume and the filing deletes it.

Lever: the Hatchery idea still waiting to be filed. Outranks the beta-defaults ledger, which has not lost anything yet.

**What exists already (to be checked):** Nothing; drafts go to the scratchpad. A new `docs/` folder needs its line in `docs/ARCHITECTURE.md`.
