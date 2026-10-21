# ADR 0014 companion: the `handoff` skill text

The two files a `tester` writes from ADR 0014, items 1 and 2, with the Write tool (backticks and
apostrophes). Section A replaces `.claude/skills/handoff/SKILL.md` whole, frontmatter included;
section B is the new `.claude/skills/handoff/split.md`. Each fenced block is one file; copy it
without the fence. Living text afterwards is the skill's, not this record's: a later change to
the skill does not come back here.

## A. `SKILL.md`

````
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
   distance from `HEAD` and from today is the staleness), the Open column of
   `docs/findings/README.md`, and what this session knows that is not yet in a file.
   `grep -rh "^- Prediction, check by" docs/design/decisions/ | grep -v " Outcome " | grep -oE "[0-9]{4}-[0-9]{2}-[0-9]{2}" | sort | head -1`;
   a date on or before today, or a `FAIL` or `SKIP` fitness line in the last test run, puts
   "triage (the scan)" in Next.
2. Rewrite the file with the Write tool, keeping exactly these sections:

   ```
   # Handoff

   Coordinator state. Subagents follow their brief, not this file.
   Updated: YYYY-MM-DD by <main session | agent name>

   ## Now
   1-3 bullets: what is in flight, which agent had it, exact state (files, failing test, blocker).
   The account the session ran on (the status line's account segment, the email local part), and the one the gate named, if it did.
   A pusher stop on behind or diverged, or a conflict on this file, as one bullet (ADR 0014, Deferred).

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
````

## B. `split.md`

````
# Handoff, split shape

For a project whose coordinators run parallel sessions on one branch and have crossed the trigger
in ADR 0014 (Deferred), recorded in a project ADR of one page that cites the evidence. Nothing
under `.claude/` and nothing in the policy file changes: the base accepts both shapes. Claude Code
only: a reader that does not inline `@` imports sees a map of inert lines. Whether nested imports
re-expand after `/compact` is unverified; a session that has compacted re-reads the map.

## Layout

- `docs/HANDOFF.md` is the map, aim under 15 lines, cap 40: the title; `Coordinator state.
  Subagents follow their brief, not this file.`; `Updated: YYYY-MM-DD by <who>`; one line naming
  this shape and this file; `Waiting on user: the Question: blocks in docs/ideas/ and each
  stream's Waiting line.`; `Open findings: the Status column of docs/findings/README.md.`; then
  one bare line `@handoff/<stream>.md` per open stream, nothing else on it. An import resolves
  from the importing file's directory, so `@handoff/`, never `@docs/handoff/`: that spelling
  loads `docs/docs/handoff/` and nothing says so.
- `docs/handoff/<stream>.md`, one per workstream, named for its source (a plan, an ADR rollout, an
  idea's Plan block): `# <Stream>`, `Updated: YYYY-MM-DD by <who>`, `## Now`, `## Next`, and an
  optional `Waiting: <question>` line for an owner question not yet filed as an idea. Cap 40 per
  file, aim under 15; the map plus every stream at most 120 together, the pusher refuses above
  either. Closing a stream is the usual fix.
- No `README.md` in `docs/handoff/`: the map is the index, and the post-edit hook warns about a
  stream the map does not import. No fixed files from the base: a project that needs one
  (deployed state, say) adds it by its own ADR, imports it like a stream, and owns its conflicts.

## Procedure

1. A session edits only the stream it is working on, in the commit of the work. The map changes
   only to open or close a stream, or to bump its Updated line.
2. Open: write the stream file and add its `@handoff/<stream>.md` line to the map, in the work
   commit. Close: `git rm` the file and delete its line, in the commit that finished the work.
3. Wrap-up: step 1 of `SKILL.md`, then rewrite each stream this session touched; the map's Updated
   line only when the map changed. Step 3, 5 and 6 of `SKILL.md` as written.
4. Staleness per stream: `git log -1 --format=%cs -- docs/handoff/<stream>.md`. A stream not
   updated in a working week is a question for triage: close it, or name who has it.
5. A conflict on a stream file means two sessions share a stream, which is the signal that
   matters: split the stream or hand it to one session, by hand, in the resolving commit.
6. Fold back when one session has run alone for a month: rewrite `docs/HANDOFF.md` with the four
   sections of `SKILL.md`, `git rm -r docs/handoff/`, one commit, one line in the project ADR.
````
