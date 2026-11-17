---
date: 2026-11-17
kind: chore
related: [2026-11-14-the-sweep-reruns-only-parts-whose-inputs-changed.md]
---

# A cost budget in every brief, set from the costs of past tasks

T298 records every agent's real tokens (`tools/agent-cost.py <Tnn>`). Wave 35 read T295 at 1.9M fresh tokens over
four agents and T297 at 30k, and the wave cost 38 points of the 7-day meter against about 16 figured. With the
records, a task's brief could carry a budget taken from past tasks of its kind (a device part, a script, an app
screen) and its Owns line, and the agent reports at the budget instead of running on; the coordinator decides
whether to extend. Summed over a wave's tasks, the same table predicts the wave's cost before it is launched (the
`wave` skill's Plan and launch, step 3). Anchors: `tools/agent-cost.py`, `~/.claude/usage-state/<session>.agents.tsv`,
the `brief` skill's `implementer.md`.

## Open

- An agent cannot read its own token count today; the budget may have to be enforced by a hook (`agent-meter.js`)
  rather than by the agent.
- Recorded from conversation, not triaged or checked against the code.
