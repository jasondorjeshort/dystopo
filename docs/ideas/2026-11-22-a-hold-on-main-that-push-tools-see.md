---
date: 2026-11-22
kind: chore
related: [2026-11-22-waits-that-wake-up-when-their-trigger-lands.md]
---

# A hold on `main` that the push tools can see

Source: coordinator (claude-opus-5-5), after the triage of 2026-11-22 (the second offer, after proposal 0065 and ADR 0065); offered and accepted by the owner; not sanity-checked.

On 2026-11-22 the coordinator nearly pushed 31 commits under another clone's running closing sweep; the only guard was a sentence in `docs/HANDOFF.md` ("no commit to `main` ... while it runs") that happened to be read. The sweep (and a release) would set a hold, for example a `hold/main` ref on origin naming the session and the reason; `tools/push-verified.sh` refuses while it exists, and its refusal names who set it, why, and the lift (the setter clears it at the end, or the owner deletes the ref).

Lever: the near-miss of 2026-11-22 with two coordinator sessions in two clones. Outranks the waking-Waits idea: this one prevents damage.

**What exists already (to be checked):** `tools/push-verified.sh`, the `sweep` and `release` skills; the handoff's sentence is the only hold today.

Question: Which shape should the hold take, given that `policy.json` lets subagents push only `task/*` and
`carry-back/*` (so a sweep agent cannot set an origin ref today) and that `push-verified.sh` is the only gate?
Recommendation: A, because the coordinator (main session, allowed any push) already launches every sweep and release,
so it can set and clear the hold itself with no change to the git policy; only `push-verified.sh` (refuse on `main`
while `origin/hold/main` exists, naming its message) and the `sweep`, `release` and `wave` skills change, one pass.
Options:
A. A `hold/main` branch on origin, set and cleared by the coordinator around a sweep or release; `push-verified.sh` refuses.
B. As A, plus a policy change letting agents push `hold/*`, so the sweep agent sets and clears it (ADR 0035 amendment).
C. A local lock file only (no origin ref): cheaper, but a second clone does not see it.
D. Not worth building: keep the handoff's sentence.

Answer: A, origin/hold/main set and cleared by the coordinator; push-verified.sh refuses while it exists (the owner, 2026-11-22, multiple choice).
