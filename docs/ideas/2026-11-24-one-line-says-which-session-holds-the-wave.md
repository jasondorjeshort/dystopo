---
date: 2026-11-24
kind: chore
related: [2026-11-22-a-durable-outbox-for-texts-meant-for-another-repo.md]
---

# One line says which session holds the wave

Source: coordinator (claude-opus-5-5), after the ideas triage of 2026-11-24; offered and accepted by the owner; not sanity-checked.

On 2026-11-24 wave 48 was planned and launched from a side session in a second clone while the main session began an
ideas triage; the main session found four of its ten untriaged files already blocked only after a fetch, and learned
who held the wave from a cross-session message. Nothing tracked said it.

The idea: `docs/HANDOFF.md` (or `.claude/hooks/policy.json`) carries one line naming the session or clone that
coordinates the current wave, set when a wave launches and cleared at its close. The `wave` and `triage` skills read
it first: another holder means no planning, no launches, and a fetch before any write to `docs/ideas/`, the board or
the handoff; the `usage-gate` or `git-guard` hook could warn on a board or handoff commit from a non-holder. Lever:
it prevents the clash, where the durable outbox idea only carries the message after it.

## Open

- Where the line lives (the handoff, which both sessions already fetch, or policy, which a hook can read).
- Whether a hook warns or refuses.
