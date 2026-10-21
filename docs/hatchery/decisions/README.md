# Decision records

One file per decision: `NNNN-short-title.md`.

Template:

```
# NNNN. Title

Date: YYYY-MM-DD
Status: proposed | accepted | superseded by NNNN | superseded in part by NNNN

## Context
## Decision
## Consequences
```

Decisions so far:

- [0001 Licensing](0001-licensing.md): GPL-3.0-or-later by default, LGPL for linkable components.
- [0002 Repository layout](0002-repository-layout.md): where knowledge, state, procedures,
  rules, and hooks live; skills, permissions, and continuity by `@docs/HANDOFF.md` import, superseded
  in part by 0014 (handoff), 0016 (the Hatchery records subtree; `CLAUDE.md` as a 40-line
  importing file) and 0017 (the three queue directories have no index). Companion [hook specifications](0002-hook-specs.md) for the
  hooks, and [section 3, the usage gate and status line](0002-hook-specs-usage.md).
- [0004 Adopting the Hatchery practice](0004-adopt-hatchery.md): an existing project takes
  the practice as files from `hatchery/main`, records the base commit in its own ADR, and updates by
  three-way patch; merging stays the path for decanted projects. (0003 and 0005 are proposals in
  Hatchery not yet promoted; the numbers stay theirs.) Superseded in part by 0016 (the merging
  sentence, the practice-set table, the fold, the two modes).
- [0006 Hook policy file](0006-hook-policy-file.md): each project's choices for the hooks
  (who commits and pushes, deletable branches, usage lines, cap exemptions) live in
  `.claude/hooks/policy.json`, not in the scripts; the guard fails closed, the usage gate open.
  Superseded in part by 0016 (the `CLAUDE.md` cap constant; `update.exclude` in the living table)
  and 0017 (the queue index switch is a README line, not a key).
- [0007 Queues, triage and one review](0007-queues-triage-and-one-review.md): `docs/ideas/`
  and `docs/operator/` as delete-when-done queues with triage blocks; a `reviewer` agent and one
  independent review per change; amends ADR 0002 where it names `backlog.md`. Superseded in part
  by 0016 (the queue READMEs and `docs/design/README.md` are project-owned) and 0017 (no index
  line per idea).
- [0008 ADR numbers, and what a decant keeps](0008-adr-numbers-and-what-decant-keeps.md):
  Hatchery uses `0001` to `0019` and a decanted project starts at `0020`; a decant keeps every
  Hatchery ADR and the Windows reference, deletes proposals, findings, ideas and operator entries;
  resolves the deferral in ADR 0004 and amends ADR 0002 where it says only 0001 and 0002 are kept.
  Superseded by 0016.
- [0009 ADR numbers when both tests pass](0009-adr-numbers-when-both-tests-pass.md): a
  tree that is both joined to the base and holds a `Hatchery base: <sha>` line numbers as adopted; the
  line wins. Amends 0008, Numbers. Superseded in part by 0016 (the tie-break is moot).
- [0010 Update mode when both tests pass](0010-update-mode-when-both-tests-pass.md): the
  anchored `Hatchery base: <sha>` line means patch mode from that sha whatever `merge-base` says;
  a join merge is a project's recorded departure, not base procedure. Amends 0009 and 0004.
  Superseded in part by 0016 (one mode; the line decides `FROM` only).
- [0011 Usage cache per session, pool advice at HOLD](0011-usage-cache-per-session-and-pool-advice.md):
  the status line caches the meters per `session_id` with the account as a label, the gate treats a
  changed login as stale, and at HOLD it reports the pool account with room through `usage.pool`;
  rotation happens at session boundaries. Amends 0006 and the hook specs, section 3
  ([usage companion](0002-hook-specs-usage.md)).
- [0012 Decision practices for long-lived software](0012-decision-practices-for-long-lived-software.md):
  each project names its promised surfaces (additions free, an incompatible change a major
  choice); dependencies by checklist, a copy under a page keeps its licence; fitness checks with a
  skip entry as the lift; ADR triggers, dated predictions and fitness lines scanned by `triage`,
  the architect called on a hit; four lines in `CLAUDE.md`, the rest in the file that applies.
  Amends the living table of ADR 0001.
- [0013 Hatchery's promised surfaces](0013-hatchery-promised-surfaces.md): the policy
  file, grant files, the idea grammar, the base line, the usage cache and the hook seams, with
  what is not promised; the reference fitness check (hooks require only Node built-ins) in the
  harness, group `F`. Proposed: the owner declined to confirm the list (2026-10-18); observed as if accepted. Superseded in part by 0016 (five
  rows added by its item 7; the idea-grammar row's record), 0017 (the listing CLI and the
  marker line rows) and 0018 (the privacy keys, the never and user allow lists, the scan CLI and
  the pre-push hook path).
- [0014 Handoff layout](0014-handoff-layout.md): one
  `docs/HANDOFF.md` by default, no hash, the edit rides in the work commit; a per-stream split
  (`@handoff/<file>.md` lines, 40 each, 120 together, no README, no fixed files) the tooling
  accepts and a project switches to at a stated trigger; every `@` line resolved by the hook
  and the pusher. Skill text in [the sibling](0014-handoff-skill.md). Supersedes in
  part 0002; adds a row to 0013. Superseded in part by 0015 (switch trigger).
- [0015 Handoff shape at decant](0015-handoff-shape-at-decant.md): the shape is chosen
  by writer count, one question to the owner at decant or adoption (yes: the map plus one stream
  from the first commit; no: the single file); a single-file project switches the day a second
  parallel session opens, not at a conflict count; the map stays the index and the stream
  directory has no README; Hatchery stays single-file; the caps do not move. Supersedes in part 0014.
- [0016 Base-owned and project-owned files](0016-base-owned-and-project-owned-files.md):
  ownership as data (`.claude/hooks/ownership.json`, one set for every tree); one patch-only update
  from the base-owned set for decanted and adopted projects alike, printed by a helper and run
  verbatim, conflicts base-wins unless a departure-table row, whole failures per path; the rule
  sections as base-owned `.claude/agreement/<section>.md` imported by a 40-line project `CLAUDE.md`
  (loads at most 190; `edit.capExempt` naming `CLAUDE.md` lifts both; `imports.js` and
  `--claude-check`); Hatchery's records in the base-owned subtree `docs/hatchery/`, Hatchery
  numbering from 0016 up, projects from 0001; `update.exclude`. Siblings: [the map](0016-ownership-map.md),
  [the procedure](0016-update-procedure.md), [the migration](0016-migration.md).
  Supersedes 0008; supersedes in part 0002, 0004, 0006, 0007, 0009, 0010, 0013. Amended by 0019
  (item 6 and the map sibling: the `Carry-back` column values).
  [no-check: the map and the subtree exist once the ADR is implemented]
- [0017 Queue directories list on demand](0017-queue-directories-list-on-demand.md):
  `docs/ideas/`, `docs/operator/` and `docs/findings/` keep no per-file index; `node
  .claude/hooks/queue-list.js <mode>` lists them from the files (`--status`, `--untriaged`,
  `--json`, `--check`; `?` for a value outside the grammar), the CLI and the marker line
  `Listed on demand: ...` promised; the marker in a README switches the post-edit index check off
  and the parser warning on for that directory, no policy key, so no sibling changes until it
  edits its README; `docs/design/`, `proposals/` and `reference/` stay written indexes. Contract
  and briefs in [the sibling](0017-queue-list.md). Supersedes in part 0002, 0006, 0007,
  0013. [no-check: the script exists once the ADR is implemented]
- [0018 Personal data never leaves the machine](0018-personal-data-never-leaves-the-machine.md):
  one scan, `.claude/hooks/privacy-scan.js`, read by guard rule 20 on every push from every
  caller, by pusher check 5 and by a `pre-push` git hook for terminal pushes; every outgoing
  commit (author, committer, message, added lines, added file names), never the net diff; classes
  email, home path in every spelling including the project-folder slug, host, the machine's own
  user and host name, an identity outside `privacy.identities`, session link, transcript, secret;
  `privacy.allow` for what is not a person; the repository identity set at decant and adopt from
  the policy; roles and `~` in committed text; the `Claude-Session:` trailer dropped; the history
  rewrite and the repository's visibility are the owner's separate steps. Contract, harness group,
  briefs and migration in [the sibling](0018-privacy-scan.md). Amends 0006 (key table);
  adds rows to 0013. Amended by 0019 (the PR trigger fired). [no-check: the script exists once the ADR is implemented]
- [0019 The carry-back channel](0019-carry-back-channel.md): a sibling sends a base
  change as a `carry-back/<slug>` branch cut from a named `hatchery/main` sha and pushed to its
  own repository under its own scan; Hatchery fetches it by URL, applies `-M --binary` with
  `--3way`, reviews the staged result, commits under its own identity and pushes through its own
  scan, so nothing foreign enters Hatchery's repository and no sibling pushes into it; requests
  without a diff and rule changes go by idea file; a `triage` step lists pending rows across the
  sibling checkouts by their base line; landed and declined states are commits on `main` found by
  `git log --grep`; a `carry-back` skill (send and land), fitness check `CB1`; the issue flow
  deferred to the owner's `Waits:`. Amends 0016 (item 6, the column values) and 0018 ("Not
  covered").
