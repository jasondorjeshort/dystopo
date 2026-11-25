---
date: 2026-11-25
kind: chore
related: [2026-11-24-one-line-says-which-session-holds-the-wave.md]
---

# Work already launched can be resumed at the hold

Source: coordinator (claude-fable-5-2), a spark during the update from the base of 2026-11-25, in the offer the owner asked for; offered and accepted by the owner; not sanity-checked.

Since the update of 2026-11-25 the usage gate (`.claude/hooks/usage-gate.js`) refuses, at the hold, a `SendMessage`
that resumes or messages an agent, as well as a new launch; only a report to `main` and a message-less notify pass.
This project's rule (`CLAUDE.md`, Wave size and the usage gate) lets verifiers and merges of work already launched
run past the line, and a FIX round is a message that resumes the task's implementer. So a fix in flight now stops at
the 5-hour hold until the reset or the owner's grant.

The idea: the gate allows a message to an agent whose launch line this session wrote before the hold was crossed.
The launch lines exist (`.claude/hooks/agent-meter.js`, the `<session>.agents.tsv` file T298 added: time,
`tool_use_id`, type, model, description, the two readings), so "launched below the hold" is a lookup, not a judgement.

Lever: a wave's fix rounds finish as the rule says, without a grant. It outranks "one line says which session holds
the wave" (a wording aid; this one stops work).

## Open

- How the gate maps a `SendMessage` target to a launch line (the agent's name or id against the `tool_use_id`).
- A base-owned hook: a departure row in ADR 0038 and a carry-back, or a policy key the base adds.

Priority: a task of wave 49 (the owner, 2026-11-25, by multiple choice: "Split by owner").
