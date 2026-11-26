---
date: 2026-11-22
kind: feature
---

# A questions page the owner answers from the phone

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked.

The owner answered about forty multiple-choice questions in chat on 2026-11-22. All open Questions on one page (an artifact page with a small database), each with its options, the recommendation and an answer field; a chore writes the answers back into the idea files as `Answer:` lines in the owner's words. Answers sit a median 3.8 days and come in sittings; the owner follows sessions from other devices.

Lever: the measured answer lag. Outranks the why-is-this-blank route for owner time.

**What exists already (to be checked):** Questions live in `docs/ideas/` and are answered in chat or by editing the file.

Use: 2026-11-25, coordinator (claude-fable-5-2): the page also lists the offer Questions (`Question: offered`, a line of a brainstorm offer the owner did not answer) with their four answers (record, record at rest, fold into a file, no); the listing's `offered` field finds them.

Question: Do you want open Questions served as a private claude.ai artifact page you answer from any device, with a chore writing your answers back into the idea files as `Answer:` lines?
Recommendation: A, tried once at the next triage before it becomes routine; it meets the measured lag (median 3.8 days, sittings of 11 to 37) and the ideas are public in the repo anyway, but it is a new place your words are entered and an agent transcribes them, which is yours to accept.
Options:
A. One artifact page with a small database: each blank Question with its options and recommendation, a choice plus a free-text field; a chore writes `Answer:` lines quoting you and commits.
B. A read-only page (generated after each triage) listing the Questions; you still answer in chat.
C. Keep answering in chat and in the files.
D. Not worth building.

Answer: A, an artifact page with a database plus the write-back chore, tried once at the next triage (the owner, 2026-11-22, multiple choice).
