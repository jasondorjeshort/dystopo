---
name: handoff
description: Rewrite docs/HANDOFF.md so the next session can resume from it alone. Use at the end of every coordinator session, before any stop (usage gate, a long pause, the owner leaving), after a merge or a re-plan changes what is next, or when asked for a handoff.
---

# Handoff

`docs/HANDOFF.md` is imported into every session and every subagent by `CLAUDE.md`, so it is the one
file that is guaranteed to be read. It is rewritten, not appended. Hard cap 40 lines. From the Hatchery
base; the resume pointers are this project's.

## When (rule in `CLAUDE.md`)

- At the end of every coordinator session, and before any stop: the usage gate (75%), the owner
  pausing, a context that is about to run out.
- After a merge, a launch or a re-plan that changes Now or Next.

## Steps

1. Gather: `git -C <main checkout> status -sb`, `git log --oneline -8`, `gh pr list`, the open rows of
   `docs/findings/README.md`, the agents still running (their ids, worktrees, emulators, ports), the
   PIDs of servers you had started for you (`out/coordinator-pids.txt`), and what this session knows
   that is not yet in a file.
2. Rewrite the file with the Write tool, keeping exactly these sections:

   ```
   # Handoff

   Coordinator state. Subagents follow their brief, not this file. Format from the Hatchery base.
   Updated: YYYY-MM-DD, at commit <short sha>, by <main session | agent name>
   Resume: <the reference files a fresh session needs, normally docs/reference/machines.md and the
   wave skill; the open wave in docs/TASKS.md>

   ## Now
   1-4 bullets: what is in flight, which agent has it (id, worktree, emulator, ports), exact state.
   The account the session ran on (the status line's account segment) and, if the gate named
   another account with room, which one.

   ## Next
   Ordered, 3-7 items, each one brief's worth. Name the kind of agent when it is obvious.

   ## Waiting on user
   Questions or actions for the owner (anything humans see or touch, accounts, devices), or "none".

   ## Open findings
   Paths of findings with status open, one line each, or "none".
   ```

3. Anything that does not fit: a task or follow-up to `docs/TASKS.md`; evidence to a finding
   (`finding` skill); durable facts to `docs/reference/`. The handoff links, it never explains.
4. Check `wc -l docs/HANDOFF.md` is 40 or less.
5. Commit and push it from the main session (the coordinator's own doc edit, ADR 0026) with
   `bash tools/push-verified.sh`, and report the VERIFIED line, not the command's output. At the gate
   too: the handoff commit is the one thing the coordinator does with its own hands before stopping.
6. Report in one paragraph what moved to findings or the board.

## Rules

- Never write procedure, rationale or history here. The log in `docs/PLANNING.md` has the history;
  ADRs have the why; skills have the steps.
- Public places only; local pack names are aliases (`CLAUDE.md`, Privacy at the coordinator too).
- A fresh session must be able to resume from this file and the files it names: if you needed to
  know something to continue, it is here or one pointer away.
