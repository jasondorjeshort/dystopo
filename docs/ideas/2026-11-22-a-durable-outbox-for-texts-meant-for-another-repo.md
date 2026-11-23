---
date: 2026-11-22
kind: chore
---

# A durable place for texts meant for another repository or session

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked.

The Hatchery idea drafted for ADR 0065 and three research reports lived in the session's scratchpad, which a reboot or cleanup can clear. One tracked place (a `docs/outbox/` folder listed on demand, or a handoff subsection) would hold a text until it is filed elsewhere, with the target and who files it; the handoff skill reads it at resume and the filing deletes it.

Lever: the Hatchery idea still waiting to be filed. Outranks the beta-defaults ledger, which has not lost anything yet.

**What exists already (to be checked):** Nothing; drafts go to the scratchpad. A new `docs/` folder needs its line in `docs/ARCHITECTURE.md`.

Question: Where should a text meant for another repository or a later session wait until it is filed: a new tracked `docs/outbox/` folder, a handoff subsection, or nowhere new?
Recommendation: A; the handoff's 40-line hard cap cannot hold drafts, and a folder with one file per text (frontmatter `target`, `filer`, `date`; deleted by the filing commit; the handoff skill lists it at resume) is one small pass once chosen. It adds a `docs/` folder to the layout ADR 0020 and `where-things-live.md` describe, so the shape is put to you.
Options:
A. `docs/outbox/`, one file per text, a README and a line in `docs/ARCHITECTURE.md`; the handoff skill's resume reads it (a departure row).
B. A one-line pointer per text in the handoff's Waiting on user, the text itself in a dated finding.
C. Nothing new: file each text in its target the same session.

Answer: A, a docs/outbox/ folder, one file per text, read by the handoff skill at resume (the owner, 2026-11-22, multiple choice).
