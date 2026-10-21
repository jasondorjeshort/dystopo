# 0038. Adopted from Hatchery after T121: the base line, `docs/hatchery/`, the owner's identity, the departures

Date: 2026-10-21
Status: accepted

Hatchery base: f7f41af6094559ef868be56c64df3ec41ef7fcf9 (living line, bumped by update-from-hatchery; last 2026-10-21, T121)
Mechanism: Hatchery ADR 0016 (update by patch over the base-owned set, read from `.claude/hooks/ownership.json` at the base commit), its ADR 0019 (the carry-back channel), and for the adoption itself ADR 0020 here and Hatchery ADR 0004.

Record: T120 (PR #7) and T121 (PR #9) in `docs/TASKS.md` and their 2026-10-21 entries in the
`docs/PLANNING.md` log; Hatchery PR #2, merged 2026-10-21 as Hatchery's ADR 0020; the owner's
identity rule of 2026-10-21; `.claude/hooks/policy.json`. Supersedes ADR 0037 whole.

## Context

ADR 0037 recorded the adoption under Hatchery's ADR 0016 and said to supersede it when Hatchery
shipped `docs/hatchery/` (its record subtree and the numbering rule) or took the integrate entry and
the harness group. Both fired on 2026-10-21 with T121, the third `update-from-hatchery` run here
(`749377e` to `f7f41af`): the guard reads `git rebase --exec` and fails closed past depth 3, the
push scan bounds its range by what the remote confirms, `git.integrate` sits in the base's own
`git-guard.js` and `policy.js` (byte-identical to the base now, so T117's rows went), the
`carry-back` skill arrived with Hatchery's ADR 0019, and Hatchery's records arrived under
`docs/hatchery/`. ADR 0037's Decision text was stale after that (T121's Status).

One more thing in ADR 0037 was wrong from the start. Its push-scan paragraph says that
`privacy.identities` lists a placeholder identity "the owner chose on 2026-10-17". The owner did not
choose it: the 2026-10-17 update invented it. The owner's rule, stated on 2026-10-21 when the triage
commits could not be pushed, is the opposite, and Hatchery had decided the same on 2026-10-18. T120
then changed the scan for the two cases that rule exposed (attribution trailers, GitHub's web
committer after a history rewrite) and carried both back to Hatchery the same day.

ADR 0037's text stands as the record of what was believed on 2026-10-17; this record replaces all of
it. ADR 0020's adopted and kept-as-is tables and ADR 0035's sections other than Updating still stand
(ADR 0037 had superseded only ADR 0020's living line and ADR 0035's Updating section; both now point
here).

## Decision

- **Base-owned files are the base's, verbatim**, patched by the helper's command from the line
  above: the hooks, `policy.js` and its examples, `statusline.js`, the agreement files, the skills
  this project did not rewrite (`update-from-hatchery`, `triage`, `carry-back`, the `adr`, `finding`
  and `proposal` templates, `handoff/split.md`, `proposal/dependency.md`). `policy.json` and
  `settings.local.json` stay the project's (the map's excludes); `settings.json` and `fitness.json`
  are mixed, hand-merged.
- **Never taken**, `update.exclude` in `policy.json`: `.claude/agents/*`,
  `.claude/skills/adopt-hatchery/*`, `commit/*`, `decant/*`, `debug-interface/*` and `LICENSES/*`,
  for the reasons in ADR 0020 (roles and models are set per launch; implementers commit and push;
  the licence rules are this project's).
- **`docs/hatchery/` is base-owned, taken whole, never edited here.** This project's own records
  stay in `docs/design/decisions/`, `docs/design/proposals/` and `docs/findings/` and keep their
  numbering from 0020; that is a departure row, not a move. A Hatchery record is superseded for this
  project by citation from one of this project's ADRs, never by an edit under `docs/hatchery/` (the
  `adr` skill). `edit.anchorExempt` in `policy.json` names `docs/hatchery/*` beside this project's
  closed records, so the anchor check skips the base's records.
- **The owner's identity** (owner, 2026-10-21). Every pushed commit carries the owner's real
  identity, listed in `privacy.identities` exactly as Hatchery's policy lists it; there is no
  placeholder, and a clone that commits here commits as that identity, since the scan exempts only
  a listed identity in author, committer and tagger fields. Attribution trailers are allowed in
  commit messages (T120). The commits made under the 2026-10-17 placeholder and the tag on them are
  rewritten only by the owner, by the owner's hands.
- **The push scan carries two project rules from T120**, both in `privacy-scan.js` and group PV of
  `test-hooks.js` (cases PV46 and PV47 since T121): a `Claude-Session:` line whose value is one
  `claude.ai` link passes only in a commit message's trailer block, and the link anywhere else stays
  a `session` hit; GitHub's web-flow identity passes as committer, never as author or tagger, and its
  service address passes in text. Their carry-back landed as Hatchery PR #2 (its ADR 0020, merged
  2026-10-21) after the base line above, so the two rows stay until an update moves the line past
  that merge; then base wins and the rows go.
- **The scan runs from the guard and from the terminal.** The guard's rule 20 scans every push from
  a session commit by commit, with the main checkout's copy of the scan (so a change to the scan's
  own contract is pushed from a terminal until it is on `main`, T120). `privacy.allow` is the
  default. `core.hooksPath` stays `tools/git-hooks`; its `pre-push` chains the base's
  `.claude/hooks/git/pre-push` with the arguments and stdin untouched, so a terminal push is scanned
  too; `--no-verify` from a terminal still bypasses it, the owner's act.
- **The agreement is replaced, not imported** (unchanged from ADR 0037). Each of the ten sections
  has a one-paragraph file `.claude/project/<section>.md` naming the replaced file and pointing at
  the section of `CLAUDE.md` that holds this project's rule; `CLAUDE.md` imports the ten and keeps
  its own text, so `node .claude/hooks/policy.js --claude-check` passes. `edit.capExempt` names
  `CLAUDE.md` exactly, which lifts the base's 40-line and 190-loaded caps (the rule here stays under
  130 lines, ADR 0020).
- **Updating.** The `update-from-hatchery` skill as the base writes it, from the line above, run
  with `--exclude '.claude/skills/brief/template.md'` (a deleted file the base still edits; a skill
  file is a row, never an exclude). A conflict on a rowed path keeps the project text with the base's
  new lines folded in by hand, both sides kept (T121: keeping HEAD whole on the scan would have
  dropped the base's range fix); any other conflict is base wins; the line is bumped last, only when
  every failed path has a disposition. A row whose change the base has taken is deleted in the same
  update.

## Departures

A living table: one row per base-owned path this project edits and per agreement section it
replaces. The project wins on a rowed path until Hatchery has the change; then base wins and the row
goes. Carry-back is an idea file in Hatchery, the Hatchery commit or ADR that took the change, or
`none`. Carried forward from ADR 0037 as it stood after T121 (T117's rows had gone in T121). Pending
carry-backs: CB1's skip and the project-side list in the ownership map (both through the
`carry-back` skill), and the handoff skill's no-process-ids rule. The two PV rows' carry-back has
landed in Hatchery (above); they stay until the base line passes it.

| Path | Reason | Carry-back |
|---|---|---|
| `.claude/hooks/privacy-scan.js`, `.claude/hooks/test-hooks.js` (PV46, PV38 before T121) | the attribution trailer is allowed (owner, 2026-10-21): a `Claude-Session:` line whose value is one `claude.ai` link passes in a commit message's trailer block (the last paragraph, not the subject, every line a trailer); the link in an added line, a file name, a tag or any other message line stays a `session` hit (T120) | Hatchery ADR 0020 (PR #2, merged 2026-10-21 as 51d29f9: ADR 0018 item 10 amended, the commit skill's trailer rule; the cases PV46 and PV47 there); the row goes when the base line passes that merge |
| `.claude/hooks/privacy-scan.js`, `.claude/hooks/test-hooks.js` (PV47, PV39 before T121) | every pushed commit carries the owner's real identity (owner, 2026-10-21), and a history rewrite re-sends GitHub's web merges: `GitHub <noreply@github.com>` passes as committer, never as author or tagger, and `noreply@github.com` passes in text like `noreply@anthropic.com` (a service address names no person) (T120) | Hatchery ADR 0020 (PR #2, merged 2026-10-21 as 51d29f9: ADR 0018 item 10 amended, the commit skill's trailer rule; the cases PV46 and PV47 there); the row goes when the base line passes that merge |
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

- An update is one command from the helper plus the hand merges on the rowed paths; nothing the
  project wrote is touched. Cost: ten pointer files and ten import lines that say nothing new, and a
  departure table to keep.
- One base line only: `ownership.js` stops on two different `Hatchery base:` lines under
  `docs/design/decisions/`, and the skill reads the first one it finds, so ADR 0037's line no longer
  carries that prefix once this record is in. The pointers that named ADR 0037 as the provenance
  record (`CLAUDE.md`, the `adr` skill, the skills README, `.claude/rules/docs.md`, the `$comment` of
  `policy.json`, ADR 0020's status note) point here in the same change.
- The identity rule puts the owner's real name and address in a tracked file (`policy.json`) and in
  every commit's metadata; that is the open-source norm and the owner's choice, and the scan still
  refuses any unlisted identity in commit metadata and any address in text but the exempt ones. The threat specific to this project, location,
  stays guarded by the privacy hooks and `CLAUDE.md` › Rules.
- Supersede this record when Hatchery's ADR 0021 (its guardrails decision, proposal 0021 in review
  on 2026-10-21) lands, which changes the hooks' contract and comes in a second base update; or when
  the pending carry-backs land so their rows can go.
