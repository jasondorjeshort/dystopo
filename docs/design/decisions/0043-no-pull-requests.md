# 0043. No pull requests: the coordinator merges verified task branches and pushes `main`

Date: 2026-10-24
Status: accepted (owner's decision)

Record: the owner's words of 2026-10-24 ("using PRs is not the way I want to do THIS project, but
it is a good system"); the coordinator's note of 2026-10-21 on the refused merges; wave 18, whose
ten merges the owner typed from the terminal (`docs/HANDOFF.md` of 2026-10-24).

## Context

Since wave 1 every task branch ended in a pull request: the implementer ran `gh pr create`, the
verifier read the PR body for the key runs, the coordinator merged with `gh pr merge --squash` and
deleted the branch once GitHub showed the merge (ADR 0021, E4 and E5; kept as
`git.branchDelete.requireMergedPr` in `.claude/hooks/policy.json`, ADR 0035). The PR body was the
record of what was run and seen, and the one text the privacy hooks never saw.

On 2026-10-21 Claude Code's auto-mode classifier, the layer that approves a command when the
session runs without prompting, refused `gh pr merge` as "Merge Without Review": a pull request no
human had approved. It refused the same in Hatchery. Nothing in this project refuses it: the guard
was run directly on 2026-10-24 and allows `gh pr merge`, as it allows the main session's `git fetch`,
`git merge --no-ff`, `git commit`, `git branch -d` and `git push origin main`; only `git branch -D`
and `git push origin --delete task/*` were held, and only by `requireMergedPr`. A subagent runs
under the same classifier, so delegating the merge changes nothing (ADR 0026 keeps it the
coordinator's in any case). So wave 18's merges were lines the owner typed.

The Hatchery base's git agreement is a single-branch model (the runner commits, the pusher pushes);
the pull-request wave flow is Hatchery's proposal 0003 and its `policy.examples/task-branches.json`,
not an accepted base rule. The owner wants pull requests in other projects, not in this one.
Outside contributors can only arrive by pull request (`CONTRIBUTING.md`); that is unchanged.

## Decision

1. **No pull requests for the project's own work.** The implementer pushes `task/Tnn` and reports to
   the coordinator. The report replaces the PR body: what was run and what was seen (numbers,
   screenshots described, public places only), every edit outside the Owns line, follow-ups. The
   coordinator passes it to the verifier in its brief; the verifier reads the branch itself
   (`git log origin/main..origin/task/Tnn`, the diff) and still rebases and lease-pushes when `main`
   moved.
2. **The merge is a true merge by the coordinator in the main checkout:** `git fetch origin`, then
   `git merge --no-ff origin/task/Tnn` with a message whose subject is `Tnn: <title>` and whose body
   is the report, scanned first with `java tools/PrivacyCheck.java scan`; then
   `bash tools/push-verified.sh`, whose VERIFIED line is the only evidence of the merge. Not a
   squash: a merge commit keeps the branch reachable from `main`, so git's own `--merged` check
   replaces GitHub's merged-PR check, and the implementer's commits keep their attribution trailers.
   `git log --first-parent main` reads one commit per task, as the squash history did.
3. **Branch deletion** no longer asks GitHub: `requireMergedPr` is `false`. The cleanup chore
   deletes the local branch with `git branch -d`, which refuses unmerged work, and the remote one only
   after `git branch -r --merged origin/main` lists `origin/task/Tnn`. `git branch -D` is for a task
   the coordinator abandons, on its word in the brief.
4. **The log entry and the Status line** read `Tnn merged: <title>` without a PR number;
   `tools/merge-log.py` keeps its optional PR argument for the entries that have one.
5. **Older records are read, not rewritten.** Where an ADR or the board says "the PR" (ADR 0026's
   "merging a verified PR", ADR 0031, 0033 and 0039's "shown in the PR", the board's header), read
   "the task's report and its merge commit".
6. **Outside contributions** still arrive as pull requests, which the owner merges from the terminal.
7. **Out of scope:** an allow rule for `gh pr merge` (not needed here; it is the route for a project
   that keeps pull requests), a subagent merging or pushing `main` (ADR 0026 and ADR 0036 stand).

Deferred: should the classifier also refuse the main session's merge commit or push of `main`, the
owner runs that one line from the terminal and this record gains the permission-rule option; the
trigger is the first merge of wave 19.

## Consequences

- A merge needs nobody at the keyboard again; the wave closes in the coordinator's session.
- Privacy improves: the report becomes a commit message, which the pre-push scan and the guard's
  rule 20 read, where PR text was never scanned and GitHub kept its edit history visible.
- Lost: the PR page as the browsable record and the `(PR #n)` link in the log; the record is the
  merge commit and the dated log entry. `main` gains merge commits beneath each task's first-parent
  commit.
- Hatchery keeps the pull-request flow for projects that want it (proposal 0003); it should learn
  two things from here, carried by a Hatchery-rooted session: the classifier refuses an unapproved
  `gh pr merge` in auto mode, so that flow needs an allow rule or the owner's terminal; and this
  push variant as a per-project choice of the same workflow.
- Changed with this record: `CLAUDE.md` (Debug before you push, Working in parallel, Coordinating a
  wave, Decisions, Rules), the `wave` skill's merge sequence, the `brief` skill's three templates,
  the `handoff` skill's gather step, `.claude/project/git.md`, `one-review.md` and
  `major-decisions.md`, `policy.json`, the board's header, `docs/reference/windows-and-shells.md`,
  `tools/merge-log.py`'s usage text.

- Prediction, check when wave 19's first merge lands: the merge commit and `push-verified.sh` run
  from this session without the owner typing a line. Outcome 2026-10-24: held, checked early on the
  owner's word with the T0 doc branch (merge d2a5f6fd): branch push, `--no-ff` merge and the push of
  `main` all ran from the session; the guard asked only that each push be a call of its own.
- Prediction, check by 2026-12-01: no task branch is deleted before its merge is on `origin/main`
  (`git branch -d` and the `--merged` listing hold without GitHub's check).
