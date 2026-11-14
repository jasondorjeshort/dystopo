---
date: 2026-11-10
kind: chore
related: [2026-11-08-cheaper-verification-by-script-and-small-model.md]
---

# The usage meter per agent, recorded by a hook at launch and at stop

The finding `docs/findings/2026-11-10-wave-cost-doubled-and-the-lane-width-does-not-explain-it.md` says wave 31
cost about 2.6 points of the 7-day meter per task, as wave 30 did and twice wave 28, and that the lane width is not
the cause; the plan is to read each task's implementer and verifier halves by hand at wave 32's close. The numbers
exist at every launch: `.claude/hooks/usage-gate.js` already reads `~/.claude/usage-state/<session>.json` on every
`Agent` call, and `.claude/settings.json` runs `emulator-stop.js` on every `SubagentStop`. The owner, 2026-11-10:
"Sure."

- The gate hook appends a line (time, the agent's description and model, 5-hour and 7-day used) at launch; the stop
  hook appends the same at stop with the agent's token counts if the hook's input carries them; one tab-separated
  file per session under `~/.claude/usage-state/`, never in the repository.
- `tools/merge-log.py` or a small `tools/agent-cost.py` [no-check: proposed] sums a task's lines (implementer,
  verifier, chores) into the log entry's cost line, so the halves come free for every task.
- The wave skill's close reads the file instead of the hand-backs.

2026-11-14, the close of wave 32: the hand reading was made once (implementers 262 to 499 thousand tokens,
verifiers 103 to 212 thousand; the `wave` skill's figure went from 2.5 to 1.0 a task) and the finding is resolved.
Each agent's completion notice already carries its token count, tool uses and duration, so the hook at stop has
the per-agent figure without a meter read. The owner, on keeping the figure by script: "Sure."

## Open

- Overlapping agents share the meter, so a difference of readings attributes a lane's cost, not an agent's; the
  token counts at stop are the per-agent figure and the readings the check on them.
- Base-shaped: the two hooks are Hatchery's (filed there as an idea the same day; a carry-back once it works here).

Plan: one no-Gradle pass in `.claude/hooks/` (base-owned files: each change a row in ADR 0037's departure table and
a carry-back once it works here). First read what a `SubagentStop` hook is handed: one run that dumps the keys of
its input says whether the agent's token count, tool uses and duration are there. Then `usage-gate.js` appends a
line at every `Agent` launch (the time, the description, the model, the 5-hour and 7-day readings) and the stop
hook (`emulator-stop.js`'s event) appends one at stop (the same readings, and the counts where the input has them)
to one tab-separated file per session under `~/.claude/usage-state/`; `tools/agent-cost.py` [no-check: new file]
sums a task id's lines into the log entry's cost line; the `wave` skill's close reads the file. Done when:
`node .claude/hooks/test-hooks.js` green with cases for both lines and for a missing state file (never a refusal,
never a throw); one real launch and stop recorded and summed. If the stop input has no counts, the report says so
and the readings alone are kept.
