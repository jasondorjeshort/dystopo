---
name: handoff
description: Rewrite docs/HANDOFF.md so the next session can resume without asking. Use when wrapping up, when the usage gate reports HOLD or STOP, before a long pause, after finishing or re-planning a chunk of work, or when asked for a handoff.
---

# Handoff

`docs/HANDOFF.md` is imported into every session and every subagent by `CLAUDE.md`, so it is the
one file that is guaranteed to be read. It is rewritten, not appended. Hard cap 40 lines; the
pusher refuses a push above it. A project whose `docs/handoff/` directory exists is in the split
shape: `split.md` beside this file replaces steps 2 and 4 and the first rule below; the rest holds.

## Steps

1. Gather: `git status -sb`, `git log --oneline -8`,
   `git log -1 --format='%h %cs' -- docs/HANDOFF.md docs/handoff/` (the last handoff change; its
   distance from `HEAD` and from today is the staleness), the open findings
   (`node .claude/hooks/queue-list.js findings --status open --json`), and what this session knows that is not yet in a file.
   Not process ids, ports, emulator serials or agent ids: they die with the session and are one
   machine's; a gitignored pid file is their only record, and the handoff says at most that a
   server or device is wanted.
   With `D=docs/hatchery/decisions` where `! git remote | grep -qx hatchery && ! grep -rqE "^Hatchery base: [0-9a-f]{7,40}" docs/design/decisions/`, else `D=docs/design/decisions` (the `adr` skill's test):
   `grep -rh "^- Prediction, check by" $D/ | grep -v " Outcome " | grep -oE "[0-9]{4}-[0-9]{2}-[0-9]{2}" | sort | head -1`;
   a date on or before today, or a `FAIL` or `SKIP` fitness line in the last test run, puts
   "triage (the scan)" in Next. A claim about the state of another repository or checkout (for
   example "<sha> in `../<dir>` is held, not pushed") is re-checked against that repository in the
   same sitting before it is written: `git ls-remote --heads origin` there, compared with
   `git merge-base --is-ancestor <sha> <remote-sha>`, or `git branch -r --contains <sha>` after noting
   when it last fetched (it answers as of that fetch; do not fetch, ADR 0002 reserves it for the
   pusher). The Updated line dates the file, not the claims it carries forward.
2. Rewrite the file with the Write tool, keeping exactly these sections:

   ```
   # Handoff

   Coordinator state. Subagents follow their brief, not this file.
   Updated: YYYY-MM-DD by <main session | agent name>

   ## Now
   1-3 bullets: what is in flight, which agent had it, exact state (files, failing test, blocker); no process ids, ports or serials.
   The account the session ran on (the status line's account segment, the email local part), and the one the gate named, if it did.
   A pusher stop on behind or diverged, or a conflict on this file, as one bullet: it is how a session learns a parallel session exists (ADR 0015).

   ## Next
   Ordered, 3-7 items, each actionable in a single brief. Name the agent when it is obvious.

   ## Waiting on user
   Questions needing a human decision (front end, wording, scope), or "none".

   ## Open findings
   Paths of findings with status open, or "none".
   ```

3. Anything that does not fit: later work to `docs/ideas/` (one dated file per item; only the owner adds a `Waits:` block);
   evidence to a finding (`finding` skill); durable facts to `docs/reference/`. The handoff links,
   it never explains.
4. Check `wc -l docs/HANDOFF.md` is 40 or less.
5. Commit. While the session goes on, the edit rides in the next work commit: the runner's brief
   lists `docs/HANDOFF.md` with the work's files (`commit` skill). A wrap-up handoff has nothing to
   ride with and is its own commit: below HOLD brief the runner; at HOLD or STOP the main session
   commits itself, `git add docs/HANDOFF.md` plus any WIP files, with a message starting `WIP:` if
   work is unfinished, ending with the Co-Authored-By line for the model doing the work.
6. Report in one paragraph what moved to ideas or findings.

## Rules

- In this shape the handoff has exactly the four sections above. Other skills say "the handoff's
  Waiting on user" or "Open findings"; in the split shape `split.md` says where each lives.
- Never write procedure, rationale, or history here. Git log has the history; ADRs have the why.
- If the file is missing (a decanted project may have deleted it), recreate it from the template
  rather than skipping the step.
- The chain is checked, not assumed: `/context` lists `docs/HANDOFF.md` (and each stream file)
  under Memory files; the post-edit hook and pusher check 7 resolve every `@` line from the
  importing file's directory. An `@` line that does not resolve loads nothing and says nothing.
