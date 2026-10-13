# 0021. git-guard with the exceptions the wave workflow needs

Date: 2026-10-12
Status: accepted

Record: Hatchery's `git-guard.js` and its specification (Hatchery ADR 0002 companion "hook
specifications", section 1); Hatchery proposal 0003 item 2 (rules 3 and 4 keyed off the phase or
the branch); this project's wave workflow (`CLAUDE.md`, Working in parallel and Coordinating a
wave; the `wave` skill).

## Context

Hatchery's guard denies destructive git for everyone and lets only the `pusher` agent push and
only the `runner` agent commit. That fits one branch and agents that leave a clean tree. Here five
to eight implementers each work in their own worktree on `task/Tnn`, commit as they go, push their
branch and open a PR; a verifier rebases the branch onto `main` when `main` moved and pushes it
back; the coordinator (the main session) commits and pushes its own doc edits to `main` and merges
verified PRs with `gh pr merge --squash`; worktree and branch cleanup after a merge is delegated to
a chore agent (`CLAUDE.md`, the coordinator's own hands). Squash merges leave the task branch
"unmerged" in git's eyes, so cleanup needs `git branch -D`. Two rules that bit this project are
mechanical enough for the guard: never `--no-verify` (the privacy hooks in `tools/git-hooks` must
run), and never delete a branch before its merge succeeded (it closed a PR once, 2026-10-09).

What the hook can know: the command text, `agent_type` (present only inside a subagent; every
agent here is launched with a general type, so the hook cannot tell an implementer from a verifier),
and the working directory. It can ask git for the current branch and `gh` for merged PRs.

## Decision

`.claude/hooks/git-guard.js` is Hatchery's script with these changes. It runs on the `Bash` and
`PowerShell` tools (`.claude/settings.json`; the base matches `Bash` only, and agents here use both
shells). Rules 5 to 12 (reset, clean, whole-tree checkout and restore, interactive rebase, stash
loss, history rewrite) are unchanged.

| Id | Base rule | Here |
|---|---|---|
| E1 | 3: only the pusher pushes | A subagent may push when every branch the push updates is `task/*`: explicit refspecs, or the current branch when none is given (`HEAD` resolved too). Tags, `--all`, `--tags`, `main` or any other branch: denied. The main session pushes anything not denied by another rule. Prefix `agents push only task/* branches`. |
| E2 | 4: only the runner commits | A subagent may commit except when the repository's current branch is `main` or `master` (resolved in the directory the command runs in: the tool call's `cwd`, then `cd <dir>` earlier in the command, then `git -C <dir>`; an unreadable branch allows). Prefix `agents do not commit on main`. |
| E3 | 1: no force push | `--force`, `-f`, a cluster with `f`, `+refspec`: denied for everyone, as in the base. `--force-with-lease` (any form) is allowed when every branch it updates is `task/*`, for anyone (the verifier after a rebase, or the implementer after rebasing before its PR). |
| E4 | 2: no remote deletion | `push --delete` or `:ref` is allowed when every deleted branch is `task/*` and GitHub shows a merged PR from it (`gh pr list --head <branch> --state merged`, run in the command's directory; any failure denies). `--mirror` and `--prune` stay denied. |
| E5 | 9: no `branch -D` | Allowed when every named branch is `task/*` with a merged PR on GitHub, as in E4. |
| A1 | (new) 13 | `--no-verify` on `commit`, `push`, `merge`, `rebase`, `am`, `cherry-pick` or `revert`, and `git commit -n`, are denied for everyone. Prefix `hooks bypass`. |
| A2 | (new) 14 | `git -c core.hooksPath=...`, and `git config` that unsets `core.hooksPath` or sets it to anything but `tools/git-hooks`, are denied for everyone. Prefix `hooks path override`. |

The merged-PR check has a test seam: `GIT_GUARD_MERGED` (comma-separated branch names) replaces
the `gh` call; `test-hooks.js` sets it, normal use never does. The variable lives in Claude Code's
environment, which a command cannot change.

### Tests

`node .claude/hooks/test-hooks.js` keeps every case of the base harness and adds the exception
cases (113 cases on 2026-10-12, all pass). Base cases whose expectation differs carry the
exception id in a comment. Run against Hatchery's own unmodified harness (copy the base
`test-hooks.js` next to these hooks), 12 of its 72 cases differ, all recorded:

| Base case | Base expects | Here | Why |
|---|---|---|---|
| G03 `git push` from `pusher` | allow | deny, rule 3 | E1: an agent pushes only `task/*` |
| G04 `git push` from `runner` | deny, prefix "only the pusher agent pushes" | deny, prefix of E1 | E1 wording |
| G08, GX08 commit from `coder` | deny rule 4 | allow | E2: the base harness runs outside any repository, so no branch is read; in a repository on `main` it is denied (this harness, G08) |
| P01, PX06 cap message | "... the pusher will refuse to push." | "... Split by topic before the PR." | No pusher agent here |
| P02 handoff cap message | "... docs/backlog.md ..." | "... a finding or docs/TASKS.md." | No backlog file here (ADR 0020) |
| U01 to U07 | the base gate (50%, 80%, override file) | allow | This project's gate is its own: 75%, `usage.json`, `DYSTOPO_GATE_OVERRIDE`; this harness tests it (U01 to U07 here) |

### Not covered, by design

- `gh pr merge --delete-branch` deletes the remote branch outside git; the `wave` skill merges
  without it and deletes afterwards through git, where E4 checks the merge.
- The hook cannot tell a verifier from an implementer, so both may lease-push a task branch; the
  verifier brief says when (after a rebase only).
- `git worktree remove --force` is not guarded; worktree removal is documented in
  `docs/reference/windows-and-shells.md`.
- Pushes from scripts (`tools/push-verified.sh`) are not seen: the hook reads the command text,
  not what a script runs. The script is the coordinator's.

## Consequences

- A subagent can no longer push `main`, push a tag, commit on `main`, bypass the privacy hooks, or
  delete a branch whose PR has not merged; each of these needs the coordinator or the owner.
- Deleting a branch needs `gh` logged in and the network; offline, cleanup waits.
- A command that only mentions such git text (an `echo`, a heredoc) is denied too, as in the base;
  write such text with the Write tool.
- Supersede when Hatchery decides proposal 0003 and its guard gains a phase or branch setting that
  covers E1 to E3; then take the base script back and keep only what differs.
