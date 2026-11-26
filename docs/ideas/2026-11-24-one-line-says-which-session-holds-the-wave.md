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

Plan: one pass, docs and the project's own skill only, no hook. The line lives in `docs/HANDOFF.md`, which every
resume and both clones already fetch: `Holder: <clone> (<the coordinator's model>), wave <n>, since <date>` under the
`Updated:` line, or `Holder: none` between waves. The `wave` skill (`.claude/skills/wave/SKILL.md`, project-owned:
the base has no `wave` skill) sets it in the launch commit, clears it in the close's last commit, and its Resume step
reads it after `git fetch`: another holder means no planning, no launch, and a fetch before any write to `docs/ideas/`,
the board or the handoff. The `handoff` skill keeps the line when it rewrites the file (one more sentence on that
skill's existing departure row in ADR 0038). Verify: the handoff stays under its 40 lines
(`node .claude/hooks/policy.js --cap-check` over a list naming it); a dry read of the wave skill's Resume by a fresh
agent. Out of scope: a hook that warns or refuses (a base-owned file and a departure of its own: a later idea if the
line proves ignored), the `triage` skill (base-owned; a coordinator's triage brief names the holder instead).
