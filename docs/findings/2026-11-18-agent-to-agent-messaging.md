---
date: 2026-11-18
status: open
area: agent orchestration / messaging
---

# Agent-to-agent messaging: should agents talk directly, or through the coordinator

Related idea: `docs/ideas/2026-11-18-agents-message-each-other-directly.md`.

## Question

Should agents message each other directly instead of every report going through the coordinator?
What do the Claude Code docs allow, what does published practice say, and what do solo practitioners
do?

## What was tried

Three research agents read the docs and the web (no code run, no experiment) on 2026-11-18; the
coordinator condensed their reports. Claude Code docs were read at changelog 2.1.327.

## Result

### A. What Claude Code allows

- Subagent `tools:` may list Agent and SendMessage. Subagents nest up to three layers
  (`CLAUDE_CODE_MAX_SUBAGENT_SPAWN_DEPTH`; 1 disables). https://code.claude.com/docs/en/sub-agents.md
- Named subagents with SendMessage can message siblings by name; the startup roster lists them. A
  resumed agent reports to whoever resumed it. The tools reference describes SendMessage targets only
  as "a subagent it resumes"; the docs are not reconciled with each other.
  https://code.claude.com/docs/en/tools-reference.md
- Agent teams: experimental, off by default (`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1`), interactive
  only. While on, any subagent Claude names launches as a teammate; teammates do not get the
  definition's skills, inherit the lead's permission mode, have no worktree isolation, and do not
  survive `/resume`. The docs say teams suit research and review, not sequential work or same-file
  edits; about 7x tokens in plan mode. https://code.claude.com/docs/en/agent-teams.md,
  https://code.claude.com/docs/en/costs.md
- A message from another agent is marked as not from the user; it cannot approve a prompt, change
  config or CLAUDE.md, or relay a denied action.
  https://code.claude.com/docs/en/cross-session-messaging.md
- Cross-session messaging reaches other live local sessions (ListAgents, SendMessage,
  notify_when_idle).
- Workflows: a script runs agents in the background; intermediate results stay out of the caller's
  context; it pauses at the plan limit, not at a project threshold.
  https://code.claude.com/docs/en/workflows.md
- No documented pattern for a review-fix loop between agents.

### B. Published practice

- Pass artefacts by reference, return condensed summaries (1-2k tokens), keep identifiers.
  Anthropic, multi-agent research system (2025-06-13)
  https://www.anthropic.com/engineering/multi-agent-research-system ; context engineering
  (2025-09-29) https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
- Anthropic's research system: workers do not coordinate directly; "LLM agents are not yet great at
  coordinating and delegating to other agents in real time".
- An evaluator-optimizer loop needs clear criteria and a stopping condition.
  https://www.anthropic.com/engineering/building-effective-agents
- A supervisor that paraphrases workers loses accuracy and tokens ("telephone game"); forwarding
  verbatim improved it by nearly 50%. LangChain (2025-06-10)
  https://www.langchain.com/blog/benchmarking-multi-agent-architectures
- Centralized coordination held error amplification to 4.4x against 17.2x for independent agents;
  peer coordination helped only on web navigation. Google Research (2026-01-28)
  https://research.google/blog/towards-a-science-of-scaling-agent-systems-when-and-why-agent-systems-work/
- MAST failure taxonomy (1,642 traces): step repetition 15.7%, unaware of termination 12.4%,
  derailment 7.4%; weak verification is common. https://arxiv.org/html/2503.13657v3
- Iterated refinement degrades: +37.6% critical vulnerabilities after 5 rounds (arXiv 2506.11022);
  43.7% of 10-round chains worse than baseline (arXiv 2603.08520).
- Peer exposure erodes independence (debate conformity, arXiv 2509.05396); evaluators favour their
  own text (arXiv 2404.13076).
- Cognition (2026-04-22): coder and reviewer iterate directly; the reviewer starts with a clean
  context; the coder owns all writes and filters findings against the user's instructions; without
  that, loops run endlessly and drift. About 2 bugs per PR, 58% severe.
  https://cognition.com/blog/multi-agents-working
- Claude Code best practices: a fresh-context reviewer; "a reviewer asked to find problems will
  always find something"; scope the reviewer. https://code.claude.com/docs/en/best-practices
- Disagreement: Cognition and LangChain lean peer/handoff; Anthropic and Google lean hub. Task shape
  explains most of it. No source measures direct against relayed for a coding review-fix loop.

### C. Solo practitioners

- Worth considering: a wave-lead subagent running coder, reviewer, fix and runner and returning one
  verdict; workflow scripts for repeatable shapes (sibling propagation); report-to-file plus a short
  verdict; cross-session messaging between sibling project sessions; a reviewer from another model
  family; report-only scheduled or end-of-day agents (Hashimoto,
  https://mitchellh.com/writing/my-ai-adoption-journey); hard round caps and per-role write scopes
  ("The joining tax", https://hackernoon.com/the-joining-tax-a-year-of-governing-claude-code).
- Overhyped: Gas Town as a whole, the claude-flow/Ruflo hive mind, 10-20 parallel writers, agent
  teams for implementation. Review, not agent count, is the bottleneck (Willison, Hashimoto,
  Cognition).
- Unverified report: subagents blocked from writing `.md` files named report, summary, findings or
  analysis; other names work.

### D. Conclusion

- High confidence: relaying by reference (the reviewer writes findings to a file; the coordinator
  resumes the coder with the path) beats the coordinator rewriting reports.
- Medium: a bounded direct coder-reviewer loop (blocking findings only, a fresh reviewer per round,
  about 2-3 rounds, the coder may decline with a reason, one summary to the coordinator) beats
  relaying by reference alone. One production report plus principles; no controlled study.
- Low: the right round cap.

### E. Follow-up already done

- The gate's SendMessage hole (resuming a finished agent got past HOLD) is closed: the usage gate
  now matches SendMessage (commit `0e2d52c`). A subagent's hook
  payload carries the parent session_id (observed, not documented).

## Next

- Write the proposal that this feeds (`proposal` skill), as the owner scoped it: a wave-lead
  subagent that runs coder, reviewer, capped fix rounds and the commit and returns one verdict, with
  findings passed by file path. It must settle how the gate treats launches from inside running work
  and the one-review wording for a lead.
- Still open: agents launched inside a running workflow or by a running subagent past HOLD; the
  one-review wording for a wave lead; background sessions' auto-push against `git-guard` (out of
  scope unless adopted).
- Measure the round cap on a real review-fix loop before fixing a number.
