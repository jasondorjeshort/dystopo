# 0015. Handoff shape at decant: chosen by writer count, not by a conflict count

Date: 2026-10-16
Status: accepted

Record: no proposal; a coordinator decision after the owner's objection to ADR 0014's trigger.
Evidence: `docs/findings/2026-10-16-handoff-split-default.md` (the finding). Supersedes in part
0014 (the Deferred switch trigger: two pusher stops in a week or two conflicts on the handoff);
every other part of 0014 stands, its other Deferred items included.

## Context

ADR 0014 kept one `docs/HANDOFF.md` as the default and let a project switch to the split shape of
`.claude/skills/handoff/split.md` only after a conflict count. The owner objected: decanted projects
run parallel sessions from day one, so the count is a delay, not a test. The finding (2026-10-16)
checked both sides. Every recorded split of a shared status file followed a single-file period and
an observed failure: a downstream build-phase project after 3.6 days (256 commits, 35 on the
handoff), two public repositories after 7 and 19 days; none split on a count fixed in advance.
Vendor guidance for parallel agents (agent teams, worktrees) partitions files per agent before any
conflict. Single-writer projects keep one file without trouble: an adopted project with one human
author made 71 handoff commits in 6 days with no conflict, and of 7 handoff conflicts replayed from
the siblings' merges, 6 were base updates and 1 a deleted file, none two sessions on one branch.
The split has costs: the downstream project loads 146 lines per launch (a 14-line map plus seven
streams of 132) against 34 to 40 for a single file, past 0014's 120 within a day with no stream
closed; and it ran 2.5 hours loading no stream on a misspelled import that nothing reported.
On the index: an `@` import map loads with documented certainty in Claude Code (nested imports
also in Copilot CLI and Gemini CLI); a README index loads in no tool, Claude Code included, which
reads a file named in words only if the agent decides to open it. The conclusion: the writer count
predicts the split, the owner knows it at decant, and where the conflict trigger would fire at all
it fires within the first week, after days of conflicts and a migration commit.

## Decision

1. The shape is chosen by writer count. The `decant` skill, at the step that writes the handoff,
   asks the owner one question, in these words: "Will more than one session work this branch at
   the same time?" Yes: decant creates the split shape, the map `docs/HANDOFF.md` exactly as
   `split.md` lays it out plus one stream file `docs/handoff/<stream>.md` named for the project's
   first workstream (the design sequence, in a decant), with `# <Stream>`, `Updated:`, `## Now`,
   `## Next`. No: the single file of four sections, as today. The answer goes in the decant
   commit's body; a shape chosen at decant is not a switch and needs no project ADR.
2. The switch trigger for a single-file project is the day a second session is opened on the same
   branch while another is still working it (a parallel session, not a successor opened after the
   previous one ended). The two-stop and two-conflict triggers of 0014 are dropped. The project
   records the switch in a one-page ADR, as 0014 says, naming the day. A pusher stop on diverged
   or a conflict on the handoff is no longer a trigger; it stays the usual way a session learns that
   a parallel session exists, so the Now bullet in the `handoff` skill and the note in the `commit`
   skill stand as evidence lines.
3. Fold-back stays as `split.md` step 6: one session alone for a month, one commit, one line in
   the project ADR.
4. The index of `docs/handoff/` stays the map's `@handoff/<stream>.md` lines; no `README.md` there.
   Why: a README loads in no tool; the import map loads in Claude Code with documented certainty,
   and Claude Code is the only tool the base's hooks, agents and skills run in; the post-edit hook
   and pusher check 7 resolve every line, which closes the silent miss the finding recorded. A fork
   on a tool that does not inline imports folds back to the single file (0014 Deferred, which stands).
5. Hatchery itself stays single-file: one writer. It switches under item 2 like any project.
6. `adopt-hatchery` asks the same question, in the same words, at the step that writes the handoff;
   an adopted project that already runs several sessions on its branch starts split.
7. The caps do not move: 40 lines per file, 120 together, aim under 15 in the split shape.

The edits, each exact; this ADR's author made them and a `reviewer` reads the diff:
- `0014-handoff-layout.md`: `Status: accepted, superseded in part by 0015`, one line under it,
  the first Deferred bullet marked replaced. Nothing else; it is the record.
- `.claude/skills/handoff/split.md`, first paragraph: the trigger sentence becomes this ADR's rule.
- `.claude/skills/decant/SKILL.md`, the handoff step: the question, the two outcomes with the map
  and the stream file in full; the verify step runs the three command lines of pusher check 7
  (`.claude/agents/pusher.md`), which hold in both shapes, in place of a bare `wc -l docs/HANDOFF.md`;
  the commit body and the report carry the answer.
- `.claude/skills/adopt-hatchery/SKILL.md`: the question at its handoff step; the same verify change.
- `docs/design/README.md`: the 0014 entry and a 0015 entry. The finding: `status: resolved`,
  `promoted_to` this file, Next trimmed to what is open; its row in `docs/findings/README.md`.

Out of scope: `post-edit.js`, `test-hooks.js`, `pusher.md`, `.claude/rules/docs.md`, the policy
files, the caps, and a parallel-session procedure for the base (0014 Deferred, second bullet).

Deferred, each with its trigger (the `triage` scan reads these):
- Whether nested imports re-expand after `/compact`, the combined instruction-file limit (no
  number is documented) and Copilot CLI's relative base for a nested `@`: the finding's Next; the
  first split project that meets each one records the answer there.
- The question's wording is human-facing text: the first owner who answers it wrongly, found by a
  first month with a single session, or a switch inside the first week, reopens the wording with the user.

## Consequences

- Easier: a parallel project never spends a week in a shape it will leave; the switch is a
  calendar fact a session can state, not a count it must keep across sessions; the owner answers
  from knowledge they already have, at the one moment every other project choice is made.
- Costs: a project decanted split pays up to 120 lines per launch from day one, three times the
  single file, and every subagent launch pays it again; the first split project exceeded that
  within a day with no stream closed. The mitigations are `split.md`'s and nothing new: aim under
  15 lines per file, close a stream in the commit that finishes its work, a stream older than a
  working week is a triage question, and the pusher refuses a push above either cap. A wrong
  answer costs one commit either way: a split project found single-writer folds back after a
  month; a single-file project with a parallel session on day two makes the migration commit the
  question was meant to spare it.
- Maintained: the question in two skills, word for word; a change to it is the owner's.
- Supersede when three projects decanted split each ran their first month with a single session, or three
  decanted single switch within their first week (the question does not sort projects), or when
  Claude Code changes import resolution (0014 says the same).

Predictions (the trigger scan reads these; an outcome is recorded on the line):
- Prediction, check by 2027-01-16: no project decanted split has had a single session for the whole of its first month.
- Prediction, check by 2027-01-16: no project decanted single has switched within its first week.
- Prediction, check by 2027-01-16: every project in the split shape indexes `docs/handoff/` by its
  map alone, with no `README.md` in that directory.
- Prediction, check when the downstream project's next `update-from-hatchery` runs: its map plus
  streams come under 120 by closing streams, with the cap unchanged.
