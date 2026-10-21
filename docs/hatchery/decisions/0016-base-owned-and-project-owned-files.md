# 0016. Base-owned and project-owned files: an update touches nothing the project wrote

Date: 2026-10-17
Status: accepted

Record: proposal `../proposals/0016-base-owned-and-project-owned-files.md` (recommendation E with
the agreement half of A; the three owner questions decided 2026-10-17), `../proposals/0016-critic-review.md`
(two passes, 28 objections, all answered); evidence `../../findings/2026-10-17-base-repo-defaults-and-upstream-merges.md`.
A coder implements from the siblings [map](0016-ownership-map.md), [procedure](0016-update-procedure.md)
and [migration](0016-migration.md); all four corrected 2026-10-17 for the review's fourteen findings.

## Context

Every `update-from-hatchery` conflicted on `.claude/hooks/policy.json` and usually on `CLAUDE.md`:
both mixed base text with project values, and in a decanted project the merge also brought the
base's own state (handoff, queues, findings, proposals, index rows). The finding's survey: wherever
fewer conflicts are reported, upstream and downstream stopped writing the same file; the owner's
reading, accepted: ownership, not location. Ruled out in the proposal, not reopened: `rerere`
alone, a `default/` directory, `merge=ours` (B), a layered policy (C), the status quo (D), A's
merge-mode repair, deleting the base's `policy.json`, a disjoint number range, `<sha>:<path>` cites.

Verified 2026-10-17 from the official docs (https://code.claude.com/docs/en/memory, "import
additional files" and "instructions seem lost after compact"; `/docs/en/hooks`,
"InstructionsLoaded"): the project-root `CLAUDE.md` and its imports are re-read and re-injected
after compaction; imports nest four hops, resolve from the importing file, code spans skipped; an
`InstructionsLoaded` hook fires per loaded file with `load_reason` including `include` and
`compact`, observation only; a subagent's re-read after its own compaction is not documented
(Deferred). Verified by the critic (its tables): every git, guard, `policy.js` and MSYS fact in the
proposal's Constraints and the sibling counts; by the review's fixtures: the rename form below.

What each earlier record still covers. ADR 0004: adoption as a recorded copy, the adoption ADR and
its base line, the licence choice, preconditions; superseded: its practice-set table, the fold,
the two-mode step 1, the owner's 2026-10-12 merging sentence. ADR 0008: nothing stands. ADR 0009:
the line wins over `merge-base` and the join is a project's departure; the tie-break is moot and
the `git show hatchery/main:` read is replaced. ADR 0010: the line decides and the join stays a
departure; patch is the only mode, so the line sets `FROM` and nothing else. Superseded in part,
not amended (the `adr` skill amends living tables only): 0013, every row stands, item 7 adds rows
and re-records one; 0006, its 150-line constant sentence (the living key table is edited in
place); 0002, the layout gains `docs/hatchery/`, `CLAUDE.md` changes shape; 0007, the queue
READMEs and `docs/design/README.md` are project-owned. The rest of each stands.

## Decision

1. Ownership is data. `.claude/hooks/ownership.json`, base-owned, holds four lists of literal
   paths: `baseOwned` (`.claude`, `LICENSE`, `LICENSES`, `docs/hatchery`), `baseExcluded` (base
   paths that are the project's: `.claude/hooks/policy.json`, `.claude/settings.local.json`),
   `mixed` (`.claude/settings.json`, `.claude/hooks/fitness.json`) and `projectOwned`, naming files
   and directories one by one, never `docs/**`. An unlisted path is project-owned, and so is any
   path the base does not ship at `<sha>`, whatever directory holds it; one set for every tree, no
   mode. The helper `node .claude/hooks/ownership.js` (`--command`, `--per-path`, `--report`, each
   `--ref <sha>` with `--exclude`; `--check`) reads the map at `<sha>` (`--check`: the tree's)
   and never defaults; the tree's own `policy.js` validates the policy.
2. One update for every project: `git diff -M --binary <FROM> <sha> -- <base-owned set, excludes>
   | git apply --3way`, printed by the helper when the diff is not empty, run verbatim in the Bash
   tool by whichever agent runs the skill. `FROM` is the base line; a project without one uses
   `git merge-base HEAD <sha>` once and writes the line in its provenance ADR in that update.
   Merging ends for decanted projects. Conflicts: base wins (`git checkout <sha> -- <path>`) unless the departure table has a
   row (`git checkout HEAD -- <path>`; a base rename the project edited: `git show HEAD:<old> >
   <new>` then `git add <new>`); a mixed file is hand-merged. A whole failure goes per path, each
   dispositioned under the name `git diff --name-only` lists (a rename by its new path); the base
   line is bumped last, only when every failed path has a disposition.
3. The agreement is base-owned. Each rule section of today's `CLAUDE.md` (every `##` but `Phase`)
   moves verbatim to `.claude/agreement/<section>.md`, ten files named in the migration's template,
   each from its heading, with no project value (thresholds are the policy file, the branch the
   `Branch:` line, the licence `LICENSE`, the decisions directory the tree's). The project's
   `CLAUDE.md`: title, provenance line, `@docs/HANDOFF.md`, `## Phase`, `Branch: <name>`, one
   `@.claude/agreement/<section>.md` line per file, `## Project rules`; at most 40 lines, loading
   at most 190 at any hop, the handoff chain excepted. A departure replaces a section's line with
   `@.claude/project/<section>.md`, a project-owned file naming the replaced file in backticks and
   the project's ADR; every replacement is a departure row. Overflow goes to `docs/reference/`,
   `.claude/rules/`, a skill or `## Project rules`, never to a free `@` import.
4. Checks. `.claude/hooks/imports.js` is the one parser of `@` imports (line start or after
   whitespace, outside code spans and fences, four hops, each relative to its file). `post-edit.js`
   at the edit and `node .claude/hooks/policy.js --claude-check`, a new pusher check 7 line, print
   the same `FAIL` lines, each counted over every file the chain reaches at any of the four hops:
   a section neither imported nor replaced, imported twice, a replacement with no section, the 40,
   the 190, an unresolved import; an import outside the repository root is listed, never read or
   counted. Only `edit.capExempt` naming `CLAUDE.md` exactly lifts the 40 and the 190, together;
   the caps live in `policy.js` beside `docCap`. The agreement's own total is at most 150 (`A1`,
   Hatchery only). `.claude/rules/docs.md` loads for both directories.
5. Hatchery's records ship as a base-owned subtree at the same path in every tree, Hatchery
   included: `docs/hatchery/README.md`, `docs/hatchery/decisions/NNNN-*.md` with their index (the
   "Decisions so far" list), `docs/hatchery/reference/claude-code-on-windows.md` with its README;
   nothing else of Hatchery's `docs/` ships. Hatchery numbers there from `0016` up, no ceiling; a
   project numbers its own `docs/design/decisions/` from `0001` or the next free number, proposals
   included. The `adr` skill's one test: no `hatchery` remote writes in `docs/hatchery/decisions/`,
   every other tree in `docs/design/decisions/`; `triage` and `handoff` scan the same directory. A
   project never writes in `docs/hatchery/`: it supersedes a record by citation from its own ADR
   (`supersedes docs/hatchery/decisions/0001-licensing.md for this project`, which then holds ADR
   0001's living table). In base-owned files a bare `ADR NNNN` is Hatchery's record; across the
   boundary a citation carries the path. Each tree adds `docs/hatchery/*` to `edit.anchorExempt`
   in its own `policy.json` (the `policy.js` default is moot: every policy file sets the key).

   Amended 2026-10-18: the one test misfired. `git clone` copies only `origin`, and the `hatchery`
   remote is added by the update skill the first time it runs, so a sibling freshly cloned on a
   second machine had no `hatchery` remote, passed as Hatchery, and would have had `adr` write in
   `docs/hatchery/decisions/` and `triage` and `handoff` scan Hatchery's records (the reviewer
   verified it on the real checkouts). The test is two-part, one line shared by the three skills;
   a tree is Hatchery when it succeeds, every other tree writes in `docs/design/decisions/`:
   `! git remote | grep -qx hatchery && ! grep -rqE "^Hatchery base: [0-9a-f]{7,40}" docs/design/decisions/`.
   The base line is tracked and travels with every clone (ADRs 0004, 0009, 0010; the ADR 0013
   row), so a clone that has never updated is caught by the second half; a project that has the
   remote but no base line yet (one adopted sibling today) is caught by the first. Hatchery has
   neither, by construction: its `docs/design/decisions/` holds no provenance ADR.
6. The departure table is a living table (path, reason, carry-back) in the project's provenance
   ADR (`NNNN-adopt-hatchery.md` or `NNNN-decanted-from-hatchery.md`, one template); the project
   wins on a rowed path until Hatchery has the change, then base wins and the row goes. The policy
   key `update.exclude` (patterns, default `[]`) holds paths the project never wants the base's
   version of; a departure from a hook or skill is a row, never an exclude.
7. Promised surfaces, rows ADR 0013's table takes by this record (surface; carrier; readers): the
   ownership map (the four lists, their meaning, the base-owned set; `ownership.json`, the helper's
   flags; the three skills, the harness: moving a path a project may have written into the
   base-owned set is a major choice, a new base path or a move toward project-owned is free); the
   agreement (the file names under `.claude/agreement/`, their import lines, the replacement path
   `.claude/project/<section>.md`; the loader, `imports.js`, `--claude-check`, `post-edit.js`:
   renaming, splitting or removing a section is incompatible, its migration naming the
   replacement files it orphans); the `Branch:` line (`^Branch: <name>$` in `CLAUDE.md`; the git
   section, `decant`, `adopt-hatchery`); the departure table (columns `Path`, `Reason`,
   `Carry-back`; the agent at a conflict, the migration); the record paths
   `docs/hatchery/decisions/NNNN-*.md` and `docs/hatchery/reference/*.md` (every citation across
   the boundary: renaming or removing one is incompatible, its migration leaving a pointer file).
   The idea-grammar row's record is now ADR 0007 and `.claude/rules/docs.md` (Queues);
   `docs/ideas/README.md` is the project's copy, named by a grammar change's migration.
   `update.exclude` is an addition under the policy row.
8. Debug interface: `docs/hatchery/reference/agreement-loading.md` (verified, observed): the
   sentinel procedure (`claude -p`, one sentinel word per agreement file, main session and a
   subagent, then `--resume`) and the compaction check (interactive `/compact`, a one-time
   `InstructionsLoaded` hook logging `include` and `compact`); a mismatch with `imports.js` is a parser fix.
9. Out of scope: the queue and proposals directories' per-file indexes (proposal 0017); here they are project-owned.

Deferred, each with its trigger: a subagent losing the agreement after its own compaction (a
sentinel lost under a forced compaction: a finding, then a new ADR); a per-sentence override key
(a project needs one sentence changed, not the section; the replacement file is the stopgap);
per-path application as the default (`--3way` fails whole on most updates); the per-file 40
dropped for the 190 alone (overflow routed through an `@` import that replaces no section); the
records not shipping, cited by `<sha>:<path>` (projects edit `docs/hatchery/` routinely); a
per-user pool file (the pool key added by hand at every decant and adoption); merge mode as option
A corrected (the owner wants base commits in a project's `blame`, or a cherry-pick carried back).

## Consequences

- Nothing the project wrote is touched by an update: the command cannot carry a path outside the
  list, the base's own state never enters a project; one code path, allowed for every caller.
- Costs: shared history for base files ends at the switch (`blame` names the update commit; the
  base's is one hop away in `git log hatchery/main -- <path>`); `--3way` fails whole, mitigated
  per path; the base line is bumped by hand; each sibling pays one migration and Hatchery one
  citation pass. To maintain: the map and helper, groups `O` and `A`, `imports.js`,
  `--claude-check`, the provenance template, three subtree indexes.
- The owner has not yet seen the `docs/hatchery/` subtree move. It is built last: every subtree
  and numbering edit (item 5, the `adr` test, the citation pass) sits in the migration's section
  3.4 and nowhere earlier; a veto there costs nothing built and reopens objection 15 in a new ADR.
- ADR 0004's owner sentence of 2026-10-12 is superseded, reported by the coordinator; ADR 0008
  before its trigger (a next number of 0020) fired. Supersede this record when the loader drops or
  requires approval for an in-tree `@` import (then `.claude/CLAUDE.md`), or on a Deferred trigger.

Predictions (the trigger scan reads these):
- Prediction, check by 2027-04-17: each sibling's next three updates conflicted only on paths in
  its departure table or in `mixed`.
- Prediction, check by 2027-04-17: after each of those updates `git diff --name-only <FROM> <sha>
  -- <the base-owned set>` equalled the paths taken plus those dispositioned (a rename by its new path).
- Prediction, check by 2027-04-17: no project has written in `docs/hatchery/`; every supersession
  of a Hatchery record is a citation from a project ADR.
