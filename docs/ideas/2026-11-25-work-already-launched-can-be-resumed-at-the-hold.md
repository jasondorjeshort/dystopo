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

Plan: one pass on the hooks, built here as an extension of ADR 0038's existing `usage-gate.js` (the launch line) row,
since the owner put it in wave 49. Checked at triage: `harmlessSend` in `.claude/hooks/usage-gate.js` passes only a
report to `main`, an empty message and shutdown messages; the launch line carries `tool_use_id` and both readings,
and `agent-meter.js`'s stop line joins `agent_id` to that `tool_use_id`. (1) `agent-meter.js` gains
`launchedBelow(sid, to, holds)`: the latest stop line whose `agent_id` equals the `SendMessage` target, its launch line
by `tool_use_id`, and true only when both of that launch's readings were under their own holds (`meterThresholds`);
any gap (no file, no stop line, no launch line, a reading missing) is false. (2) In `usage-gate.js`, at HOLD only and
only for `SendMessage`, a true answer allows with a stderr note naming the agent and its launch readings; the deny
line, new launches and Workflow are unchanged, and no new `policy.json` key is added (a key would need the base's
schema). Cases in `.claude/hooks/test-hooks.js` beside AM01 to AM12 under a scratch HOME: below-hold launch resumed at
HOLD allowed; launched at or over the hold denied; unknown target denied; a deny-line reading still denied. Docs: the
ADR 0038 row's reason and carry-back cell, `.claude/project/usage-gating.md`, `docs/reference/agent-cost.md`. Verify:
`node .claude/hooks/test-hooks.js` all green; one hand run with `--input <file> usage-gate --home <dir>`. Out of scope:
messaging a still-running agent that has no stop line yet (gated as today), the carry-back's landing in Hatchery.
