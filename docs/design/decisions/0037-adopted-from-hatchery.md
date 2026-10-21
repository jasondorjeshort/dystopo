# 0037. Adopted from Hatchery under the base's ownership map: the base line, the departures, the agreement replaced by pointers

Date: 2026-10-17
Status: superseded by 0038 (2026-10-21)

0038 (2026-10-21, T121) holds the base line and the departures; this text is the record of 2026-10-17.
Mechanism: Hatchery ADR 0016 (update by patch over the base-owned set, read from `.claude/hooks/ownership.json` at the base commit) and, for the adoption itself, ADR 0020 here and Hatchery ADR 0004.

Record: the second `update-from-hatchery` run here (Hatchery `987265c` to `749377e`, fifty-five base
commits: its ADRs 0016 to 0018 and their hooks, skills and agreement files); ADR 0020 (the adoption by
hand, whose living base line moves here); ADR 0035 (the scripts verbatim under this project's policy;
its Updating section is replaced by this record, the rest stands); ADR 0036 (`triage` comes in).
The third run (`749377e` to `f7f41af`, T121: the guard reads `git rebase --exec`, the scan bounds
its range by what the remote confirms, `git.integrate` and the settings-command group taken by the
base, the `carry-back` skill and Hatchery's ADR 0019) brought Hatchery's own records into
`docs/hatchery/`, base-owned and never edited here.

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
histories were rewritten on 2026-10-17, so the base line `987265c` was re-recorded as `987265c`
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
- **The push scan runs from the guard and from the terminal.** The guard's rule 20 scans every push from a
  session commit by commit. `privacy.identities` lists a placeholder identity the owner chose on
  2026-10-17 (an obviously fake name and a `.invalid` address, so no real address sits in a tracked
  file; the owner knows the earlier commits carry the real one), and the repository's `user.name` and
  `user.email` are set to it, since the scan exempts only commits made as a listed identity; a commit
  made as anyone else is refused at push. `privacy.allow` is the default. `core.hooksPath` stays
  `tools/git-hooks` (this project's own privacy hooks); its `pre-push` chains the base's
  `.claude/hooks/git/pre-push` with the arguments and stdin untouched (owner, 2026-10-17), so a
  terminal push is scanned too; `--no-verify` from a terminal still bypasses it, the owner's act.
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
| `.claude/hooks/privacy-scan.js`, `.claude/hooks/test-hooks.js` (PV46, PV38 before T121) | the attribution trailer is allowed (owner, 2026-10-21): a `Claude-Session:` line whose value is one `claude.ai` link passes in a commit message's trailer block (the last paragraph, not the subject, every line a trailer); the link in an added line, a file name, a tag or any other message line stays a `session` hit (T120) | Hatchery ADR 0020 (in preparation on a Hatchery branch: ADR 0018 item 10 amended, the commit skill's trailer rule; the cases numbered PV46 and PV47 there) |
| `.claude/hooks/privacy-scan.js`, `.claude/hooks/test-hooks.js` (PV47, PV39 before T121) | every pushed commit carries the owner's real identity (owner, 2026-10-21), and a history rewrite re-sends GitHub's web merges: `GitHub <noreply@github.com>` passes as committer, never as author or tagger, and `noreply@github.com` passes in text like `noreply@anthropic.com` (a service address names no person) (T120) | Hatchery ADR 0020 (in preparation on a Hatchery branch: ADR 0018 item 10 amended, the commit skill's trailer rule; the cases numbered PV46 and PV47 there) |
| `.claude/hooks/test-hooks.js` (CB1) | the carry-back identity check skips a tree with a `Hatchery base:` line, the `adr` skill's test for a project, as well as one with a `hatchery` remote: its history carries GitHub's web-merge committer and two early commits authored as Claude, which the scan never re-reads (T121) | Hatchery: CB1's skip uses the `adr` skill's two-part test |
| `.claude/hooks/test-hooks.js` (GE22) | the long `--exec` spelling refused under this project's own `policy.json`, from a subagent and from the main session (T121) | none (the base's GE cases cover the same parse) |
| `.claude/hooks/ownership.json` | `projectOwned` names this project's modules, tooling, build files and the `docs/` files outside the listed folders, so fitness check O1 passes; the base's `$comment` target is kept (T121) | Hatchery: a project-side list in the map (a project file the update never patches), so the base's list and the project's never conflict |
| `docs/hatchery/` | taken whole and never edited; this project's own ADRs, proposals and findings stay in `docs/design/decisions/`, `docs/design/proposals/` and `docs/findings/`, as the base's `adr` skill has every tree but Hatchery do: a row, not a move (T121) | none |
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
  departure table to keep. Dropping the import lines would leave `--claude-check` failing until
  Hatchery offers a lift for an unimported section; a carry-back idea for that lift is worth filing.
- What arrived and is in use: `queue-list.js` and the marker switch in `post-edit.js` (T118 converts
  `docs/ideas/` to the grammar, ADR 0036); the `triage` skill; `ownership.js`, `imports.js` and
  `--claude-check`; the push scan (rule 20) with the paste rule in the `finding` skill and the
  Personal data section of `.claude/rules/docs.md`; the harness groups for all of them.
- The pointer shape is kept (owner, 2026-10-17, on the coordinator's recommendation: fourteen lines
  and ten one-paragraph files against a one-command update and a passing check; the alternative was
  ten standing failures until Hatchery offers an opt-out). A clone that commits here sets the placeholder identity
  (`git config user.name`, `user.email` from the policy's first entry) or its pushes are refused,
  from a session and from a terminal alike.
- ADR 0020's living line and ADR 0035's Updating section are superseded by this record; everything
  else in both stands. Supersede this record when Hatchery ships `docs/hatchery/` (the record subtree
  and the numbering rule) or takes the integrate entry and the harness group so rows can go.
