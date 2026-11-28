---
date: 2026-11-28
kind: chore
related: [2026-11-17-a-cost-budget-in-every-brief-from-past-tasks.md]
---

# A subagent's hand-back is held while a command it started still runs

Source: coordinator (claude-fable-5-2), a wave close; offered and accepted by the owner; not sanity-checked.

In wave 49 five agents (three verifiers, two sweep agents) ended their turn while a long command of theirs still
ran: a sweep of two hours, a verifier script of forty minutes. Each hand-back reached the coordinator as a report
with no result, and each cost a resume by id with the same instruction again. Their briefs already said to wait
in the foreground with bounded loops and to hand back once; the sentence did not hold.

The idea: the `SubagentStop` hook (`.claude/hooks/emulator-stop.js` runs there today) refuses the stop while a
process the agent started is alive, with a line the agent reads: which command, its PID, and "wait for it with a
bounded foreground loop, then report". The agent goes on instead of yielding. What counts as "started by the
agent" needs a record the hook can read (the emulator lock already names its owner; a sweep's or a verifier
script's PID file could do the same).

Lever: a cost paid five times in one wave, and the reason the finding
`docs/findings/2026-11-01-stop-hook-kills-the-emulator-of-an-agent-that-yields-its-turn.md` exists at all.

## Open

- Whether a stop hook may hold an agent without a limit; a ceiling (the command's own timeout) and what happens
  at it.
- Whether this belongs to the base's hooks; then it is a report for Hatchery, not a change here.
