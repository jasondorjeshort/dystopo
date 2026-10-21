# 0019. The carry-back channel: a sibling-hosted branch, landed as a fresh base commit

Date: 2026-10-18
Status: accepted

Record: proposal `../proposals/0019-carry-back-channel.md` (recommendation E, changed from B after
the critic; every objection answered in its last section) and `../proposals/0019-critic-review.md`
(two blocking, five serious, five minor; all accepted, four with a change). The owner accepted E on
2026-10-18. Amends ADR 0018 ("Not covered, by design": the PR trigger fired) and ADR 0016 item 6
(the `Carry-back` column's values). Technical under `CLAUDE.md`; the owner was asked because the
first draft would have made the GitHub PR page a reading surface, and declined it.

## Context

ADR 0016 moves base changes into a sibling and leaves the reverse to a column. On 2026-10-18 the
build-phase sibling opened PR 1 in Hatchery's repository from a fresh clone (one remote at
Hatchery's URL, no hooks path, its placeholder identity, one commit on `749377e`, pushed under its
own guard, which matches the branch name and never reads the remote). Verified for the proposal:
Hatchery's scan refuses that commit scanned alone (two `identity` hits) and sees `0 commits` once
it is on `origin`; a server-side merge is no push; `refs/pull/<n>/head` is read-only and outlives
the branch (the critic's sources); a GitHub number did not survive the 2026-10-17 recreation of
this repository; `git fetch <url> <branch>` writes only `FETCH_HEAD`, so a mistaken merge of it is
outgoing to the scan; `git apply --3way` stages what it applies and fails whole on a file `main`
deleted or a binary without `--binary` (the critic's experiment). The owner's own `Waits:` on
`../../ideas/2026-10-13-github-issues-for-intake.md` stands: no issue flow until its trigger.

## Decision

1. Channels. A built change to a base-owned or mixed path (ADR 0016's map) goes by a
   `carry-back/<slug>` branch in the sibling's own repository, section 2. A request without a
   diff, a question, or a change to a rule, an agreement file, a decision or a promised surface
   (ADR 0013) goes by an idea file in Hatchery's `docs/ideas/`, filed by Hatchery's coordinator
   from the sibling's text; a rule or surface change is then a proposal here and never lands from
   a branch. No sibling pushes into Hatchery's repository; PR 1 is the last PR there.
2. One change per branch: one departure row or one bug, its spec edits included (the docs rule
   requires the hook specs or a key table to move with the behaviour; a record is edited only for
   that). A branch carrying several changes is landed as several commits or sent back. Living
   text never names the sibling (the docs rule); the landing message does.
3. Send, the `carry-back` skill's first section, run in the sibling by its coder (steps 1 to 3
   and 5), the agent its `git.commit.subagents` allows (4 and 7; the runner under the base
   policy, since rule 4 denies a coder `git commit`) and its pusher (6):
   1. `git fetch hatchery`; `B=$(git rev-parse hatchery/main)`; every later command names the sha.
   2. `git worktree add ../<repo>-wt/carry-back-<slug> -b carry-back/<slug> <B>` (the sibling's
      worktree folder; `<slug>` is kebab-case, names the path or the bug, never a person).
   3. The change in that worktree, with its spec edit and harness cases; `node
      .claude/hooks/test-hooks.js` passes there.
   4. One commit by the committing agent, from the coder's report: subject `Carry-back: <what
      changed>`, body: the reason, then the line `Carry-back-base: <B>` and the `Co-Authored-By`
      trailer of the model that wrote the change.
   5. The send check, stopping at the first refusal: `git merge-base --is-ancestor <B>
      carry-back/<slug>` holds and every commit of `git rev-list carry-back/<slug> --not <B>`
      carries the line `Carry-back-base: <B>` in its body, so the range is the step 4 commit
      alone; else `carry-back: the branch is not cut from hatchery/main; rebuild: git worktree
      remove ../<repo>-wt/carry-back-<slug> && git branch -m carry-back/<slug>
      carry-back/<slug>-wrong-base && git worktree add ../<repo>-wt/carry-back-<slug> -b
      carry-back/<slug> <B>` (the change is brought over by its own commit with `git diff <c>~1
      <c> | git apply --3way`; the `-wrong-base` branch is never pushed, its deletion the
      owner's); the path check: every entry of `git diff --name-status -M <B>
      carry-back/<slug>` is either (a) a path in `baseOwned` or `mixed` of `ownership.json` at
      `<B>` (an `R` entry: both paths), or (b) an `M` entry under `docs/design/decisions/` or
      `docs/reference/`, Hatchery's records until migration 3.4 of ADR 0016 moves them under
      `docs/hatchery/`, which is base-owned, after which (b) matches nothing and the check is
      unchanged; and at least one entry is (a). An entry that is neither: `carry-back: <path> is
      project-owned; a base change touches only the base-owned set and the record it updates`
      (an `A`, `D` or `R` under the record directories lands here: a sibling never adds, removes
      or renames a Hatchery record, item 1). Every entry (b): `carry-back: <path> is a record; a
      record moves only with the base change it describes`.
   6. `git push -u origin carry-back/<slug>` by the sibling's pusher under its own checks: rule 20
      and check 5 scan it under the sibling's lists; a hit stops the send, and the lift is the
      sibling owner's (`privacy.allow`, the never and user allow lists, from the sibling's main
      session after asking the user, ADR 0018), never the skill's. `git.push.branches` must match
      `carry-back/*` (the base default `["*"]` does; a project with a narrower list adds it).
   7. The departure row, on the sibling's own `main` after the step 4 commit exists (the cell
      names that commit's sha, so never in it): `Carry-back` becomes `carry-back/<slug> at
      <sha7>`; a bug fixed only on the branch gets a row too, so that the queue sees it.
4. The queue, a new `triage` step after the scan (step 1; the rest renumbered), run in Hatchery
   only (`SKIP` where a `hatchery` remote exists): for each directory beside this one whose
   `docs/design/decisions/` holds a `Hatchery base:` line (`grep -rlE "^Hatchery base: [0-9a-f]{7,40}" ../*/docs/design/decisions/`),
   read every `Carry-back` cell of the form `carry-back/<slug> at <sha7>`, and classify it by
   `git log main --format=%H --grep="carry-back/<slug> at <sha7>"`: a hit whose subject starts
   `Carry-back:` is `landed <sha7>`, `Carry-back declined:` is `declined`, none is `pending`. The
   report prints one line per cell, `carry-back: <dir> carry-back/<slug> at <sha7>: pending |
   landed <sha7> | declined`, then `carry-back: <n> pending`; the pending ones go to the handoff's
   Next by the coordinator. No sibling list is kept anywhere; the base line is the test.
5. Land, the skill's second section, run in Hatchery:
   1. Coordinator: a clean tree; one pending branch per brief; `U=$(git -C ../<dir> remote get-url origin)`.
   2. Coder: `git fetch "$U" carry-back/<slug>`; `B=$(git log -1 --format=%b FETCH_HEAD | sed -n 's/^Carry-back-base: //p')`.
      Refuse, with the reason reported for the sibling, when `B` is empty, `git merge-base
      --is-ancestor "$B" main` fails (the base is not on Hatchery's `main`), `git merge-base
      --is-ancestor "$B" FETCH_HEAD` fails, or a commit of `git rev-list FETCH_HEAD --not "$B"`
      lacks the line `Carry-back-base: $B` in its body (`carry-back: the range is not one
      carry-back's own`; the sibling's send check is the same test, item 3.5), or when the path
      check of item 3.5, run on `git diff --name-status -M "$B" FETCH_HEAD` with the map at `$B`,
      refuses (the same two texts). PR 1 passes it: seven `M` entries in the base-owned set and
      two `M` entries under `docs/design/decisions/` (the hook specs and the ADR 0006 key table).
   3. Coder, in Bash, never PowerShell: `git diff -M --binary "$B" FETCH_HEAD | git apply --3way`.
      Exit 1 with `U` entries: resolve per file like ADR 0016's step 5 (the sibling's hunk where it
      is the carried change, `main`'s where `main` moved on). Exit 1 with an empty `git status
      --porcelain` (a file `main` deleted, `does not exist in index`): apply per path, one `git
      diff -M --binary "$B" FETCH_HEAD -- '<path>' | git apply --3way` per entry of `git diff
      --name-status -M "$B" FETCH_HEAD`, and name each dropped path in the landing body.
   4. Reviewer: the one independent review, of `git diff --cached` and the fetched messages (`git
      log FETCH_HEAD --not main`), for Hatchery's constraints: ADR 0013 surfaces, sibling
      assumptions in fixtures and thresholds, the docs rule (a sibling's name in living text), the
      claims in the message; blocking by rule as the agent file says. Fixes go to the coder's
      working tree; nobody pushes to or force-pushes a sibling's branch.
   5. Runner: `node .claude/hooks/test-hooks.js` passes; delete every `docs/ideas/` file the change
      completes (named in the brief); one commit, subject the branch's subject, body: the branch's
      body, dropped paths if any, the line `Carry-back: <repo> carry-back/<slug> at <sha40>`, the
      branch's `Co-Authored-By` lines and the coder's when the review changed the tree.
   6. Pusher: `git push origin main`; rule 20 and check 5 scan the landing under Hatchery's policy
      like any commit, which is why the landing is a fresh commit and the sibling's author is
      never kept. A declined carry-back is one empty commit by the runner, `git commit
      --allow-empty`, subject `Carry-back declined: <repo> carry-back/<slug> at <sha40>: <reason>`.
   7. The sibling's next update (ADR 0016 procedure, step 5) takes the base's version on the rowed
      path and deletes the row; on a decline it sets the cell to `none: <reason>`. The landing or
      decline commit is in its `FROM..sha` log, so nothing is relayed by hand. PR 1 lands by steps
      2 to 6 from `origin/task/T117-integrate-and-elision` with `B=749377e`, as one commit this
      once; the pusher closes the PR with a comment naming the landing sha, written to a file and
      passed through `privacy-scan.js --text` first; the branch delete is the owner's (rule 2).
6. Column values (ADR 0016 item 6, amended; the table's template is
   `.claude/skills/adopt-hatchery/template-adr.md`): `carry-back/<slug> at <sha7>` (sent), the
   Hatchery commit that took the change (as before), `none: <reason>`, or an idea file in the
   project for a request with no diff.
7. ADR 0018, amended: no PR flow in the base by decision; text that leaves the machine through
   `gh` (the PR 1 closing comment; an issue or comment if the owner later lifts the `Waits:`) is
   written to a file and passed through `--text` before the `gh` call, a skill step and no hook
   (`gh` is outside the guard).
8. Fitness check `CB1 identities`, group `CB` in `test-hooks.js`, Hatchery only (`SKIP CB1
   identities: hatchery remote` elsewhere): every `%an <%ae>` and `%cn <%ce>` of `git log main` is
   in `privacy.identities` or `privacy.allow` of the policy; it passes from the start because the
   2026-10-17 rewrite left only the listed identity on `main`.
   `FAIL CB1 identities: <n> commits with an identity outside privacy.identities; lift:
   privacy.identities (owner) or ADR amendment (architect)`.

Out of scope: the batch critic pass PR 1's body proposes (an idea file, then a proposal); a `gh`
parser in the guard. Deferred: a Hatchery issue per carry-back as the announcement, trigger: the
owner lifts the `Waits:` on the issues idea; D's report flag (`ownership.js --pending-rows`: rows
without a landing), trigger: a sibling with more than three open rows; a bundle or patch-file
form for a sibling whose repository Hatchery cannot read, trigger: the first such sibling.

Build (one coder brief): `.claude/skills/carry-back/SKILL.md` with sections Send (item 3) and
Land (items 5 and 7); the `triage` step (item 4); `test-hooks.js` group `CB` (item 8); the
0016 update skill's step 5 sentence for the decline case (item 5.7); the departure table in
`.claude/skills/adopt-hatchery/template-adr.md` gaining the column values (item 6).

## Consequences

- Nothing foreign enters Hatchery's repository; Hatchery stays its only writer; no guard rule,
  pusher exception or policy row ships to the siblings. A sibling's mistake (a branch cut from its
  own `main`) publishes nothing new and is refused at the landing with a named rebuild.
- Blind spot: the queue finds a sibling by its `Hatchery base:` line, and a sibling that still
  lacks the line also lacks the provenance ADR that holds the table, so widening discovery to a
  `hatchery` remote would find nothing to read; its carry-backs are invisible until its ADR 0016
  migration writes both (`0016-migration.md`, per sibling), which is the trigger that closes it.
- Every carried change is read once by Hatchery's reviewer against Hatchery's constraints and
  scanned on Hatchery's push under Hatchery's policy; the sibling's author is never in the base
  history, so `CB1` holds without listing sibling placeholders.
- Costs: no GitHub diff page or review thread; a finding goes back as the refusal text in the
  handoff and the sibling's next brief. The triage step reads sibling checkouts, so a sibling not
  checked out beside Hatchery is invisible until it is. Landed and declined states are found by
  `git log --grep`, which the landing message form must keep. PR 1's commit stays at
  `refs/pull/1/head` for the life of the repository.
- Supersede when a second regular contributor or a public sibling arrives (the issue flow, or a
  fork-and-PR landing by the same steps from `refs/pull/<n>/head`), or when Claude Code hooks can
  see `gh` commands.

Predictions (the trigger scan reads these; superseding this ADR records each outcome first):
- Prediction, check by 2027-04-18: `CB1` has never failed; no commit under `refs/heads/*` of
  Hatchery's repository has an author or committer outside `privacy.identities`.
- Prediction, check by 2027-04-18: every carry-back sent before 2027-01-18 is landed or declined,
  and its departure row is gone within two updates of the landing.
