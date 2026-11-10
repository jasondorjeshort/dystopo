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

## Open

- Overlapping agents share the meter, so a difference of readings attributes a lane's cost, not an agent's; the
  token counts at stop are the per-agent figure and the readings the check on them.
- Base-shaped: the two hooks are Hatchery's (filed there as an idea the same day; a carry-back once it works here).
