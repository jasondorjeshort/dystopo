---
date: 2026-11-22
kind: feature
related: [2026-11-08-two-channels-at-beta-alpha-untested-beta-swept.md]
---

# Ideas from users, after beta (moonshot)

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked. Moonshot.

An in-app "suggest an idea" that files into the same queue as `Source: user`, under the same bar, recurrence and duplicate handling (a user's idea that matches a file becomes a `Came up again:` entry). A new data flow: flagged, not built quietly (ADR 0031), with privacy review of what a suggestion may carry.

Why carry it: the queue's handling was built for volume, and this is where volume would come from.

**What exists already (to be checked):** Send diagnostics exists in Settings; no idea path.

Question: Keep an in-app "suggest an idea" path for after beta, a new data flow from users' phones into the repo's queue, and if so when should it be designed?
Recommendation: A; there are no outside users before beta (`2026-11-08-two-channels-at-beta-alpha-untested-beta-swept.md`), and the flow needs a proposal on where suggestions land (an issue tracker, an email, a form), what a suggestion may carry (no location unless the user adds it) and who triages it, not a pass.
Options:
A. Record at rest until the beta's planning, then a proposal (privacy, channel, moderation, the `Source: user` line).
B. A proposal now, so the beta ships with it.
C. Not worth building: users report through the release page and Send diagnostics.

Answer:
