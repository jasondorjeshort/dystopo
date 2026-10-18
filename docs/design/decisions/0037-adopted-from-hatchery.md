# 0037. Adopted from Hatchery under the base's ownership map: the base line, the departures, the agreement replaced by pointers

Date: 2026-10-17
Status: accepted

Hatchery base: 749377e6939e5a0d5cf6f58244eb994d691d90f1 (living line, bumped by update-from-hatchery; last 2026-10-17)
Mechanism: Hatchery ADR 0016 (update by patch over the base-owned set, read from `.claude/hooks/ownership.json` at the base commit) and, for the adoption itself, ADR 0020 here and Hatchery ADR 0004.

Record: the second `update-from-hatchery` run here (Hatchery `987265c` to `749377e`, fifty-five base
commits: its ADRs 0016 to 0018 and their hooks, skills and agreement files); ADR 0020 (the adoption by
hand, whose living base line moves here); ADR 0035 (the scripts verbatim under this project's policy;
its Updating section is replaced by this record, the rest stands); ADR 0036 (`triage` comes in).
Hatchery's own records are read in its tree (`docs/design/decisions/0016-*.md` there, four files),
not copied here.

## Context

ADR 0020 adopted the base by hand and ADR 0035 ran the first patch update over `.claude` and
`CLAUDE.md`, which failed whole whenever the base changed a file this project had never taken (the
agents, `commit`, `decant`, `adopt-hatchery`). Hatchery's ADR 0016 makes ownership data: a map of
base-owned, excluded, mixed and project-owned paths, a helper that prints the one patch command, a
departure table in the project's provenance ADR for the base-owned files the project edits, and the
base's working agreement split into ten files under `.claude/agreement/` that a project imports into
its `CLAUDE.md` or replaces one by one under `.claude/project/`. Its ADR 0017 lists queue directories
on demand (`queue-list.js`, the README marker, the post-edit switch); its ADR 0018 scans every push for
personal data (`privacy-scan.js` behind the guard's rule 20, a pre-push hook, the pusher). Both
histories were rewritten on 2026-10-17, so the base line `c50b0da` was re-recorded as `987265c`
through the rewrite kit's commit map before the diff could be taken.

This project's `CLAUDE.md` carries none of the base's rule text (ADR 0020): every one of the ten
sections contradicts it somewhere (roles and models set per launch rather than agent files,
implementers who commit and push their own branches, a permissive-only licence rule, the board and
the planning log, the 75% gate with in-flight work past it). Hatchery's migration note for this
project says as much: most sections become replacement files and the Rules section stays.

## Decision

- **Base-owned files are the base's, verbatim**, patched by the helper's command from the line above:
  the hooks, `policy.js` and its examples, `statusline.js`, the agreement files, the skills this
  project did not rewrite (`update-from-hatchery`, `triage`, the `adr`, `finding` and `proposal`
  templates, `handoff/split.md`, `proposal/dependency.md`). `policy.json` and `settings.local.json`
  stay the project's (the map's excludes); `settings.json` and `fitness.json` are mixed, hand-merged.
- **Never taken**, `update.exclude` in `policy.json`: `.claude/agents/*`, `.claude/skills/adopt-hatchery/*`,
  `commit/*`, `decant/*`, `debug-interface/*` and `LICENSES/*`, for the reasons in ADR 0020 (roles and
  models are set per launch; implementers commit and push; the licence rules are this project's).
- **The agreement is replaced, not imported.** Each of the ten sections has a one-paragraph file
  `.claude/project/<section>.md` naming the replaced file in backticks and pointing at the section of
  `CLAUDE.md` that holds this project's rule; `CLAUDE.md` imports the ten replacements and keeps its own
  text, so `node .claude/hooks/policy.js --claude-check` passes and nothing an agent reads changed but
  ten pointer lines. `edit.capExempt` names `CLAUDE.md` exactly, which lifts the base's 40-line and
  190-loaded caps together (the rule here stays under 130 lines, ADR 0020); `edit.anchorExempt` gains
  `docs/hatchery/*` for the base's record subtree, which Hatchery has not shipped yet.
- **The push scan runs, the pre-push hook does not.** The guard's rule 20 scans every push from a
  session commit by commit. `privacy.identities` lists a placeholder identity the owner chose on
  2026-10-17 (an obviously fake name and a `.invalid` address, so no real address sits in a tracked
  file; the owner knows the earlier commits carry the real one), and the repository's `user.name` and
  `user.email` are set to it, since the scan exempts only commits made as a listed identity; a commit
  made as anyone else is refused at push. `privacy.allow` is the default. `core.hooksPath` stays
  `tools/git-hooks` (this project's own privacy hooks); the base's `.claude/hooks/git/pre-push` is in
  the tree and inert until the owner chains it from `tools/git-hooks/pre-push` or declines it.
- **Updating.** The `update-from-hatchery` skill as the base writes it, from this line. The helper's
  command is run with `--exclude '.claude/skills/brief/template.md'` (a deleted file the base still
  edits; a skill file is a row, never an exclude). A conflict on a rowed path keeps the project text
  with the base's new lines folded in by hand; any other conflict is base wins; the line is bumped
  last, only when every failed path has a disposition.

## Departures

A living table: one row per base-owned path this project edits and per agreement section it
replaces. The project wins on a rowed path until Hatchery has the change; then base wins and the row
goes. Carry-back is an idea file in Hatchery, the Hatchery commit that took the change, or `none`.

| Path | Reason | Carry-back |
|---|---|---|
| `.claude/hooks/test-hooks.js` | this project's settings-command group (H, HS and SL cases) spliced before the base's CLI group at each update; the pusher-agent case (the cap-check command in `.claude/agents/pusher.md`) skipped when that file is absent, since the base's harness crashes on it here | T117's pull request on Hatchery carries the group and the skip |
| `.claude/rules/README.md` | this project's rules table (`kotlin.md`, `trackdb.md`) | none |
| `.claude/rules/docs.md` | this project's exemptions, indexes, places rule and ideas line; the base's new lines folded in by hand | none |
| `.claude/settings.json` | mixed by the map: exec-form hooks, this project's permissions (ADR 0035) | none |
| `.claude/skills/adr/SKILL.md` | numbering from 0020, the index in `decisions/README.md`, the PLANNING pointer | none |
| `.claude/skills/brief/SKILL.md`, `brief/template.md` (deleted) | rewritten around the implementer, verifier and chore templates; the base's template is not taken | none |
| `.claude/skills/finding/SKILL.md` | the board as a destination, evidence in `out/` | none |
| `.claude/skills/handoff/SKILL.md` | resume pointers, `push-verified.sh`, no process ids (owner, 2026-10-17), the single-file shape | the no-process-ids rule, for Hatchery's queue |
| `.claude/skills/proposal/SKILL.md`, `brief-critic.md` | this project's constraints and the task-driven flow | none |
| `LICENSE` | this project's licence; the base's GPL text is never taken | none |
| `.claude/project/this-agreement.md` | replaces `this-agreement`: the Process base paragraph of `CLAUDE.md` | none |
| `.claude/project/where-things-live.md` | replaces `where-things-live`: `docs/ARCHITECTURE.md` is the map, the board and the planning log exist | none |
| `.claude/project/delegation.md` | replaces `delegation`: ADR 0026, no agent files | none |
| `.claude/project/debug-interface.md` | replaces `debug-interface`: "every feature agent-testable", no design doc 05 | none |
| `.claude/project/major-decisions.md` | replaces `major-decisions`: the Decisions section, ADR 0031 and 0033 | none |
| `.claude/project/usage-gating.md` | replaces `usage-gating`: 75% with in-flight work past the line | none |
| `.claude/project/model-routing.md` | replaces `model-routing`: model per kind of work, set per launch | none |
| `.claude/project/one-review.md` | replaces `one-review`: the verifier per PR | none |
| `.claude/project/git.md` | replaces `git`: implementers commit and push `task/*`, ADR 0036 integration | none |
| `.claude/project/licensing.md` | replaces `licensing`: permissive dependencies only, no SPDX headers | none |

## Consequences

- An update is one command from the helper plus the hand merges on the rowed paths above; nothing
  the project wrote is touched, and the whole-failure of ADR 0035's procedure on files never taken is
  gone (the excludes). Cost: ten pointer files and ten import lines that say nothing new, and a
  departure table to keep; the owner may veto the pointer shape, in which case the import lines go
  and `--claude-check` fails until Hatchery offers a lift for an unimported section.
- What arrived and is in use: `queue-list.js` and the marker switch in `post-edit.js` (T118 converts
  `docs/ideas/` to the grammar, ADR 0036); the `triage` skill; `ownership.js`, `imports.js` and
  `--claude-check`; the push scan (rule 20) with the paste rule in the `finding` skill and the
  Personal data section of `.claude/rules/docs.md`; the harness groups for all of them.
- Owner's items: whether to chain the base's pre-push hook into `tools/git-hooks`; the pointer shape
  above. A clone that commits here sets the placeholder identity (`git config user.name`,
  `user.email` from the policy's first entry) or its pushes from a session are refused.
- ADR 0020's living line and ADR 0035's Updating section are superseded by this record; everything
  else in both stands. Supersede this record when Hatchery ships `docs/hatchery/` (the record subtree
  and the numbering rule) or takes the integrate entry and the harness group so rows can go.
