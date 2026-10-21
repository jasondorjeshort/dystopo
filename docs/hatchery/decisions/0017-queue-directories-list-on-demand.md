# 0017. Queue directories list on demand: no hand-kept index in their READMEs

Date: 2026-10-17
Status: accepted

Record: proposal `../proposals/0017-queue-directories-list-on-demand.md` (recommendation A as
revised; every objection answered in its last section) and `../proposals/0017-critic-review.md`
(five serious, five minor, none blocking; all ten accepted, six with a change). Evidence:
`../../findings/2026-10-17-queue-directory-indexes.md`. Contract: [the sibling](0017-queue-list.md).
Technical under `CLAUDE.md`: the READMEs are read by agents and the owner, who asked for this shape.

## Context

`docs/ideas/`, `docs/operator/` and `docs/findings/` hold one file per item and are deleted-when-done
queues (ADR 0007). Each README ended in a hand-kept table with one row per file, and the rule file,
the post-edit hook and four skills read or wrote those rows. The owner: in these three "we most
likely should not put an index of the files into the readme. The entire point of using files for
this is to not have conflicts on that one file constantly"; `reference/` and `design/` "serve as a
documentation file" and may differ. Measured (the finding, section 5; recounted by the critic):
commits to the ideas and findings READMEs changed rows only in 16 of 17 and 13 of 14 here, 17 of 20
and 37 of 40 in `../regenesis`; status drifted in both directions (a regenesis finding `resolved` in
the file and `open` in its row; two rows there outliving their files; proposals 0003 and 0005 here
saying `draft` after a critic review); every one-file-per-item tool surveyed lists on demand, and
the ones that commit an index do so for a site build this repository does not have. `merge=union`
and marker sections are ruled out by experiment (union resurrects deleted rows; forges ignore it).
ADR 0016 made the three READMEs project-owned and never patched, which ends the base-against-project
conflicts seen so far; what a table still costs is the parallel-session conflict ADR 0015 expects,
the second copy of status, and one shared-file edit per add, resolve and delete. The critic kept
`docs/design/proposals/README.md` indexed: its rows are curated summaries no listing can print.

## Decision

1. Scope. `docs/ideas/`, `docs/operator/` and `docs/findings/` carry no per-file index. Each
   README keeps its format rules, loses its table, and gains the marker line (item 3). Status,
   kind and triage block are read from the item file, nowhere else. `docs/design/README.md`,
   `docs/design/proposals/README.md`, `docs/reference/README.md` and the `docs/hatchery/` READMEs
   stay written indexes, one line per file, curated.
2. The listing. `.claude/hooks/queue-list.js`, Node built-ins only (`fs`, `path`), under fitness
   check `F` by its path, exporting its per-file reading for the hook. Command, columns, filters,
   `--json` fields, the `?` rule, stderr and exit codes: the sibling, sections 1 to 3. In one line:
   `node .claude/hooks/queue-list.js <ideas|operator|findings> [--status <v>] [--untriaged] [--json|--markdown] [--check]`,
   one row per file, `?` where a value is outside the grammar with a stderr line naming field and
   allowed set, exit 0 unless `--check` and a `?` (1) or a usage error (2). It reads files, never
   rows, so it works in a tree that still has its tables.
3. The switch. A README whose line reads exactly `Listed on demand: node .claude/hooks/queue-list.js <dir>`
   (`<dir>` its own directory name, one of the three) turns the post-edit index check off for that
   directory and on comes the parser warning: an edited file that would list as `?` is told which
   field and the allowed set (the sibling, section 4). A README without the marker is checked as
   today. No policy key; the choice lives in the project-owned file it is about (ADR 0016), so no
   sibling changes behaviour until it edits its README. A malformed `Listed on demand:` line warns
   on the README edit and counts as indexed.
4. The rule file, the skills and the map change as the tester brief says (item 8): a directory is
   either indexed (its README names every file) or listed on demand (its README carries the marker
   and the format, never a file name); every skill line that wrote or read a row either runs the
   command or is conditional on the README carrying a table, and the hook, not the skill, says
   which case a tree is in.
5. Promised surfaces, rows ADR 0013's table takes by this record (surface; carrier; readers): the
   listing CLI (the path `.claude/hooks/queue-list.js`; the mode words `ideas`, `operator`,
   `findings`; `--status`, `--untriaged`, `--json`, `--check`; the `--json` array and field names
   `file`, `title`, `status`, `kind`, `block` with `?` as the literal `"?"`; exit 0, 1 and 2 as
   above; the first word of a finding `status:` as its status; the queue READMEs, the `docs/README.md`
   map and split-shape handoff maps that name the command, the `handoff`, `triage`, `decant`,
   `adopt-hatchery` and `finding` skills, the hook) and the marker line (its exact text; every
   queue README that carries it; `post-edit.js`). Not promised: the tab-separated text, `--markdown`,
   stderr wording, the `read` export, harness ids. Agents parse `--json`. The findings frontmatter
   leaves ADR 0013's not-promised list for `status:` only: the trigger ("the first program that
   reads them") has fired; `date`, `area` and the optional keys stay unpromised.
6. What the amended ADRs keep and lose. `0002-repository-layout.md`: "every directory under
   `docs/` has a `README.md` index, one line per file", the `PostToolUse` row's "missing from its
   index" and "every new file costs one index line" now except the three; the rest stands.
   `0002-hook-specs.md`: the index-check row and three new rows, edited by the coder. ADR 0006:
   "only exemptions are policy" gains the one exemption that is a README line; the key table gains
   nothing. ADR 0007: "every idea costs a file and an index line" and the nagging sentence; the
   grammar, the blocks and the review rule stand. ADR 0013: the rows of item 5; the rest stands.
7. Out of scope and deferred, each with its trigger: a `proposals` mode (the owner asks for
   proposals listed; an addition under the row); a fourth queue directory (a project adds one: the
   marker is per README already, but the mode list is the three until an ADR adds a name); the
   pusher running `--check` (an unparsable status should block a push: check 8, no shape change);
   a `grep` fallback (Node missing from a tool shell where the hooks still run); dated or
   collision-free `NNNN` numbering for ADRs and proposals (a parallel-session number collision).
8. Briefs. Implementation starts after ADR 0016's code has landed, since both edit `post-edit.js`,
   `decant`, `adopt-hatchery`, `update-from-hatchery` and `.claude/rules/docs.md`.
   Coder (code, one commit): `queue-list.js` to the sibling, sections 1 to 3; `post-edit.js` to
   section 4, the index check untouched for every other path; group `Q` in `test-hooks.js` to
   section 5; the `0002-hook-specs.md` rows of section 4, in the same commit. Verify: harness green
   with `Q` and `F` present; `node .claude/hooks/queue-list.js <mode> --check` exits 0 for all three
   modes on the real tree once the tester has landed; the `P` fixtures unchanged.
   Tester (docs and skills, one commit, after the coder): `docs/ideas/README.md`,
   `docs/operator/README.md`, `docs/findings/README.md`: the `## Index` table and its sentence out,
   the marker line in, the findings line "Open items are marked in the Status column" replaced by
   the command with `--status open`. `.claude/rules/docs.md`, Indexes: the indexed-or-listed rule
   of item 4, the marker as the hook's switch, "`docs/findings/README.md` shows status per file"
   gone, the one-list rule kept; Frontmatter: a finding status is read by its first word.
   `finding` skill: Record step 3, Resolve step 3 and the Supersede sentence become "if
   `docs/findings/README.md` carries an index table, update its row; otherwise the post-edit warning
   is the check (the file must list without `?`)". `triage` skill: step 2 becomes the `--untriaged`
   call, the report names the stderr `?` files as not classified, the Bounds line about index rows
   becomes "and their rows where the README carries a table". `handoff` skill step 1: "the Open
   column of `docs/findings/README.md`" (a column it never had) becomes the `--status open` call;
   `split.md` and the `decant` split template: `Open findings: node .claude/hooks/queue-list.js findings --status open`.
   `decant` steps 7 and 8: delete files only, no table reset. `adopt-hatchery`: copy the base
   READMEs only where the project lacks them, no table reset, so an adopted project keeps its own
   tables and warnings. `update-from-hatchery`: any surviving "their READMEs are the base's" or
   "drop their index rows" sentence goes. `docs/README.md`: the three queue rows' Loaded cell names
   the command beside the README. Proposals 0003 and 0005: `Status: under review`. Verify: the
   post-edit hook prints no index warning on a queue file and no malformed-marker warning on the
   three READMEs; `--check` exits 0 for all three modes; `grep -rn "Open column" .claude/skills` empty.
9. Siblings, each in its own session after its 0016 update brings the hook and the script:
   `../regenesis` (18 ideas, 28 findings, 1 operator file; every value reads cleanly): three
   tables out, three markers in, one commit; its drifted and stale rows go with the tables.
   `../broodforge` (14 ideas, 10 findings, 5 operator files): the same, one commit.
   `../droplet-management` (4 ideas, 3 findings, no `docs/operator/`): the same for two READMEs.
   `../dystopo` (19 ideas in its own grammar, `status:` and `pipeline:` beside `kind:`; 18
   findings): the findings README may switch at once; the ideas README keeps its table and its
   Triage section (no marker, hook unchanged) until it chooses, in its own session, between moving
   to the promised grammar (delete the `done` files, `Plan:` blocks, drop the two keys) and then
   adding the marker, or keeping its grammar for good and reading the `?` lines as its own.

## Consequences

Easier: no file that every add, resolve and delete edits, so parallel sessions conflict on nothing
in these directories by construction; one copy of status, checked at the edit by the same reading
that lists; "what is open" is one parseable command for the coordinator, the skills and a reader.
Harder: a forge page shows file names, not status (the date prefix and the slug carry most of it;
the single-file handoff links the open findings; the split map names the command); a Node start
per listing (about 0.07 s). Costs: one script, one harness group, one promised surface with
readers in every tree (a rename or a changed field is a proposal from now on), a magic line the
hook reads, and a `?` that a foreign grammar (dystopo) sees on every call until it chooses.
Exit: `--markdown` prints rows a tree pastes under `## Index`, it removes the marker, and the hook
checks the directory as before; each tree is its own commit. Superseded when the owner wants
status on a forge page without a terminal (option B of the proposal, for findings), or when
either prediction fails.

Predictions (the trigger scan reads these; superseding this ADR records each outcome first):
- Prediction, check by 2027-04-17: `node .claude/hooks/queue-list.js <mode> --check` exits 0 for
  all three modes in Hatchery, and no work merge in any sibling that added the marker has
  conflicted on `docs/ideas/README.md`, `docs/operator/README.md` or `docs/findings/README.md`.
- Prediction, check by 2027-04-17: no tree that added the marker has re-added a per-file table
  under it.
