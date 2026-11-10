---
date: 2026-11-10
kind: chore
related: [2026-10-13-fable-per-model-usage-gate.md, 2026-11-10-a-pushed-branch-takes-main-by-merge-never-rebase.md]
---

# The usage meter per agent, recorded by the gate hook at launch and a stop hook at stop

The build-phase sibling found its wave cost per task doubled between two waves (Dystopo's finding of 2026-11-10,
"wave cost doubled and the lane width does not explain it") and could only read the implementer's and verifier's
halves by hand from the agents' hand-backs. The readings exist at every launch: `.claude/hooks/usage-gate.js` reads
`~/.claude/usage-state/<session>.json` (written by `.claude/statusline.js`) on every `Agent` call and sees the
agent's description and model; a `SubagentStop` hook sees the stop.

- The gate hook appends one tab-separated line at launch (time, description, model, the 5-hour and 7-day
  percentages) to `~/.claude/usage-state/<session>-agents.tsv`, never in the repository; a small stop hook appends
  the matching line at stop with the agent's token counts when the hook's input carries them.
- A reader (`node .claude/hooks/queue-list.js agents`, or a line in the `wave` skill's close) sums a task's agents
  into its cost, so a project's per-task figure and its halves come free.
- Filed in Dystopo the same day as `docs/ideas/2026-11-10-the-meter-reading-per-agent-recorded-by-a-hook.md`; built
  there first, carried back when it works.

## Open

- Overlapping agents share one meter, so a difference of readings is a lane's cost, not an agent's; the token
  counts at stop are the per-agent figure and the readings the check on them.
- The gate hook's one job is to deny; the append must never make it fail (write errors swallowed, as the
  statusline does).
