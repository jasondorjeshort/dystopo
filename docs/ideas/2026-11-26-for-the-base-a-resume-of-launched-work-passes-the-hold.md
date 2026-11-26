---
date: 2026-11-26
kind: chore
---

# For the base: a resume of work launched below the hold passes the usage gate

Source: T376's implementer (claude-opus-5-5), a report to the base the owner asked for on 2026-11-26 ("for pushbacks to hatchery we want to report them there then a hatchery agent does the research and integration"); not sanity-checked by the base.

At HOLD the base's `.claude/hooks/usage-gate.js` refuses every `SendMessage` that resumes or messages an agent, so a
fix round of work the gate itself let start stops at the line. This project changed that in its own copy (task
T376, commit 5cadb95c; the owner's rule "Resumes pass, new agents wait"), recorded as a row of its departure table
(ADR 0038) and an amendment of its ADR 0035:

- At HOLD only, never at the deny line, a `SendMessage` passes when its `to` is an agent id (`a` and 16 hex digits,
  checked before it enters a path; a name is refused), the target's `agent-<id>.meta.json` in the session's
  `subagents/` folder (found from the payload's `transcript_path`, `<project>/<session>.jsonl`) gives the launch's
  `toolUseId`, exactly one `launch` line of the session's `agents.tsv` carries it by column, and that line's two
  readings were under their holds (an empty reading counts as under when the other is present: the status line had
  no live window for that meter) and no older than `staleSeconds`.
- Every gap or fault (no file, a broken meta file, an unreadable `agents.tsv`, another session's agent, a launch at or
  over a hold, under a grant included) is the base's refusal, text unchanged. An allowed resume writes a stderr note
  and a `resume` line to `agents.tsv`. `Agent` and `Workflow` calls are unchanged.
- It rests on this project's per-agent cost lines (`agent-meter.js`, already offered to the base by the T298 row).

What the base could take: the rule behind a policy key in its schema (for example `usage.resumeLaunchedBelowHold`,
off by default), so a project turns it on without forking the gate; this project used no key because a key needs
the base's `policy.schema.json`. The 22 cases (group RS of `test-hooks.js`) and the folder layout they copy are the
evidence to start from.
