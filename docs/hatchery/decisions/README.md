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
  importing file), 0017 (the three queue directories have no index) and 0023 (the 300-line cap is a
  threshold). Companion [hook specifications](0002-hook-specs.md) for the
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
  and 0017 (the queue index switch is a README line, not a key), and 0021 (the role keys' base
  values; who writes a HOLD grant), 0022 (a session-meter HOLD is waited out) and 0025 (a listed
  agent type passes HOLD up to a finish line a project sets; withdrawn by 0027).
- [0007 Queues, triage and one review](0007-queues-triage-and-one-review.md): `docs/ideas/`
  and `docs/operator/` as delete-when-done queues with triage blocks; a `reviewer` agent and one
  independent review per change; amends ADR 0002 where it names `backlog.md`. Superseded in part
  by 0016 (the queue READMEs and `docs/design/README.md` are project-owned), 0017 (no index
  line per idea), 0021 (one review per change for the full lane only), 0025 (a led change's fix
  rounds are checked; withdrawn by 0027) and 0026 (what `Waits:` means and who writes it; when a pass
  builds and deletes).
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
  ([usage companion](0002-hook-specs-usage.md)). Superseded in part by 0021 (who lifts the HOLD deny).
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
  the pre-push hook path), 0021 (role defaults, `privacy.identities` as extras, two keys) and 0026
  (the idea-grammar row's record, five listing fields, the two Deferred items). Amended by 0025
  (three usage keys; the compatibility check's policy-schema half).
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
  0013. Amended by 0026 (five `ideas` fields, two placement warnings, cases Q14 to Q18).
  [no-check: the script exists once the ADR is implemented]
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
  adds rows to 0013. Amended by 0019 (the PR trigger fired); superseded in part by 0020
  (the trailer, the noreply recommendation) and by 0021 (item 4's identity rules; item 6's pusher).
  [no-check: the script exists once the ADR is implemented]
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
- [0020 Attribution trailers and the web committer](0020-attribution-trailers-and-the-web-committer.md):
  the owner allows attribution trailers; a `Claude-Session:` line passes the scan only in a
  message's trailer block with one `claude.ai` link as its value, and stays a hit everywhere else;
  GitHub's web-flow identity passes as committer only and its noreply address in text; decant and
  adopt take the owner's real identity unless the owner says otherwise and never invent a
  placeholder; rule 20 judging with the main checkout's scan is a stated limit. Supersedes 0018 in
  part (item 10, item 6's trailer sentence, item 4's recommendation).
- [0021 Guardrails bound damage, not who may act](0021-guardrails-bound-damage.md): rulesets
  on `main` and `v*` tags (an owner step); any session or agent commits and pushes work branches,
  the role split opt-in; stateless guard rules by damage (non-fast-forward pushes, rewriting pushed
  commits, deleting unmerged remote branches or tags, repointing remotes, `--no-verify`; a
  discarding command only over a dirty tree; a `gh` tripwire); the scan accepts the repository's
  own identity, `privacy.identities` lists extras; light (`.md` under `docs/` and `.claude/skills/`)
  and full lanes by `lane.js`, a post-hoc audit by a reviewer; `usage.mode: "warn"` lets the
  coordinator write its own HOLD grant with a reason; narrow allow lines and deny lines on the live
  hooks. Migration per sibling. Supersedes in part 0006, 0007, 0011, 0013, 0018.
  [no-check: the script exists once the ADR is implemented]
- [0022 A session-meter HOLD is waited out](0022-session-meter-hold-is-waited-out.md): a HOLD on the
  session meter alone is waited out with one scheduled wake-up after the reset, after the handoff,
  commit and a report to the owner. Supersedes in part 0006 and 0011. Superseded in part by 0025
  (its HOLD row gains the lead's sentence; withdrawn by 0027).
- [0023 The 300-line doc cap becomes a threshold](0023-length-threshold-not-cap.md): 300 lines on a
  `docs/**/*.md` is where the writer asks what the doc is made of (cut inventories and settled
  deliberations; move rationale to reference or the ADR; never split one procedure's steps); a file
  over 300 carries `[length: <reason>]` in its first 10 lines, the post-edit hook warns without it,
  pusher check 7 FAILs only without it, the reviewer judges the reason; `SKILL.md` over 500 warns,
  nothing fails; `edit.capExempt` and the proposals exemption stay; the handoff and `CLAUDE.md` caps
  stay hard. Supersedes in part 0002.
- [0025 A wave lead runs one change](0025-wave-lead.md): a `wave-lead` subagent (Sonnet; Bash, Agent
  and SendMessage only) runs one reviewed change from a lead directory the coordinator prepared and
  decides nothing: `.claude/hooks/lead.js` derives the one permitted step from the files of
  `.lead/<slug>/` and the repository, and its PreToolUse hook refuses any other launch, send or shell
  command, a `model` on a launch and a second attempt at a step; writer, the runner's commit, a review
  of the unpushed commit, at most two fix rounds each checked by a fresh reviewer that never sees the
  writer's response, a verdict whose `Next:` line names each stop's lift; the pusher refuses while a
  led change is open; the coordinator writes the two briefs as files and alone overrides a finding;
  additive policy keys, a per-meter `finish` (off by default; 75 on Hatchery's 5-hour meter, none on
  the weekly one) and `usage.finishAgents`, let a listed type pass HOLD up to that line, a grant
  lifting both; the policy-schema compatibility check (`policy.js --schema`, `policy.schema.json`,
  group `PS`); three preconditions observed in a shakedown before routine use; a six-change pilot with
  fixed thresholds that can withdraw it. Texts, the script's contract, harness cases and templates in
  [the sibling](0025-lead-contract.md). Supersedes in part 0006, 0007 and 0022; amends 0013. (0024 is a
  parked draft proposal; the number stays its.) Superseded in part by 0027 (the lead withdrawn; the keys and
  the schema check stand). [no-check: the script is removed by ADR 0027]
- [0026 The brainstorm offer](0026-brainstorm-offer-and-rest-pile.md): a base-owned `brainstorm`
  skill, named by `triage` and `handoff`, offers the owner at most nine ideas (after a triage, at a
  wave close or a release, on a spark, on request; at HOLD too, not at STOP); record, record at
  rest, fold or no, and a line left unanswered becomes an idea file ending in `Question: offered`;
  entries `Came up again:`, `New:` and `Use:` as one physical line above the block; a file with a
  `New:` line or an offer Question is not built, a landed file with a `Use:` line is kept; `Waits:`
  widened to `Waits: at rest`, written by the answered session from the owner's quoted words; the
  rest review on request, at a release and by one line after a triage, at most weekly;
  `queue-list.js` gains five `--json` fields and two placement warnings; one record line per offer;
  the carried `triage` text lands with seven edits. Formats, reader contract, build order and the
  migration per tree in [the sibling](0026-brainstorm-contract.md). Supersedes in part 0007 and
  0013; amends 0017. [no-check: the skill exists once the ADR is implemented]
- [0027 The wave lead is withdrawn](0027-wave-lead-withdrawn.md): the pilot failed K1 on every reading and
  the owner withdrew the lead; the `wave-lead` agent, its two hook entries, its sentences, its script with
  group `WL`, the gate's finish-line branch and the status line's ` finish <n>` go, in two steps (text by a
  `tester`, then code by a `coder`), each listed file by file; the keys `finish` and `finishAgents` stay
  promised, validated and read by nothing; `policy.js --schema`, the recorded schema and group `PS` stand;
  the relay by review-file path and the wrap-up commit by pathspec stay; a migration row per tree; the idea
  files it makes moot are named for the owner's word. Supersedes in part 0025; withdraws 0025's parts of
  0006, 0007 and 0022.
