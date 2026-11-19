---
date: 2026-11-18
kind: question
---

# Agents message each other directly, not always through the coordinator

In a build-phase project the coordinator spends much of its context relaying: a reviewer's report
comes in, the coordinator rewrites it as a fix brief for the coder, the coder's report comes back,
and every round grows the most expensive context in the session. The harness has a message channel
between agents (`SendMessage`, teammates, `ListAgents`); the base's agent definitions do not list it,
and `.claude/agreement/delegation.md` and `.claude/agreement/one-review.md` route every fix through the coordinator.

What to find out before a proposal:

- What the harness allows: whether an agent definition can list `SendMessage`, whether a subagent
  can address a sibling subagent or only teammates can, and how a message from another agent is
  weighed (information, not instruction).
- Which loops are mechanical enough to run direct: reviewer to coder on blocking findings, tester
  to coder on a failing case, coder to runner for a commit. Which are not: anything that widens
  scope, files an idea, overrides a finding or touches what humans see stays the coordinator's.
- Bounds: rounds per loop, who reports to the coordinator at the end (each agent, one summary),
  how the one-review rule holds when the reviewer re-reads its own findings' fixes.
- A measure for a pilot in one build-phase project: coordinator tokens and wall-clock per landed
  change, against fixes made direct that the coordinator would have declined or deferred.

A change to the agreement (`.claude/agreement/delegation.md`, `.claude/agreement/one-review.md`) and to the agent definitions, so it
goes through a proposal and an ADR if the research supports it.

Question: run the research (an architect pass over the harness docs and one pilot), or wait until a
build-phase project reports the relay cost as its bottleneck?
Answer: Research done 2026-11-18 (`docs/findings/2026-11-18-agent-to-agent-messaging.md`); the
usage gate now covers `SendMessage`. Next is a proposal for a wave-lead subagent that runs coder,
reviewer, capped fix rounds and the commit, and returns one verdict, with findings passed by file
path, not rewritten. It must settle: how the gate treats launches from inside running work so
work finishes before the cap rather than being cut off; and the one-review wording for a lead.
