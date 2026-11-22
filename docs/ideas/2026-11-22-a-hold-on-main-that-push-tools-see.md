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
