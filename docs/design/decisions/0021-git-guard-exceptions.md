# 0021. git-guard with the exceptions the wave workflow needs

Date: 2026-10-12
Status: superseded by 0035
Since 2026-10-16 the scripts are the base's and read the `git` block of `.claude/hooks/policy.json`, which still encodes E1 to E5, A1 and A2: `0035-hooks-from-the-base-under-project-policy.md`.

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
| U01 to U07 | the base gate (50%, 80%, override file) | as the base | Since T94 the gate is the base's under this project's policy (Usage gate, below); this harness tests it as U01 to U15, S01 to S05 |

### How the hooks are run

Since T99 (2026-10-14) every hook in `.claude/settings.json` uses Claude Code's exec form,
`"command": "node", "args": ["${CLAUDE_PROJECT_DIR}/.claude/hooks/<name>.js"]`: no shell, and
Claude Code substitutes the placeholder with the directory the session started in (the main
checkout, or a worktree when the session starts there). The earlier `node .claude/hooks/<name>.js`
was resolved against the shell's current directory, which follows `cd`: after a `cd` into a
subfolder every hook failed with MODULE_NOT_FOUND, and git-guard's `onFailure: "block"` then
blocked every shell call of the session (2026-10-14). `test-hooks.js` runs each command from
`settings.json` as Claude Code does, from a subfolder (H01 to H03, and HS01 to HS03 under a path
with a space), with the old form as the control (H00); 136 cases on 2026-10-14, all pass. The
status line command keeps its relative form (its documentation names neither the placeholder nor
exec form); a status line that fails only lets `usage-state.json` go stale, which the gate allows
with a note.

### Usage gate

Since T94 (2026-10-13) `.claude/hooks/usage-gate.js`, `policy.js` and `.claude/statusline.js` are
the Hatchery base's (its ADR 0006), the gate's deny text aside, which points here. The status line
writes `~/.claude/usage-state.json` and the gate reads it. `.claude/hooks/policy.json` holds this
project's numbers: HOLD at 75% on each meter, deny at 100%, data older than two hours allowed with a
note. The owner lifts HOLD in two ways, never the deny line: the override file
`~/.claude/usage-gate-override.json`, `{"until": <epoch seconds>, "reason": "<the owner's words>",
"meters": ["7d"]}` (`meters` is `"5h"`, `"7d"` or both, both when absent; an unknown name lifts
nothing), written only on the owner's word and machine-wide; or `DYSTOPO_GATE_OVERRIDE=1` in the
environment of a session started for in-flight relaunches (`usage.overrideEnv`). Because deny is
100, the file can lift both limits, as the owner asked ("we should have the opportunity to remove
both limits", 2026-10-13); to follow the session meter only, it names `7d`.

The same `policy.json` carries this ADR's git exceptions: its `git` and `edit` blocks are
Hatchery's `policy.examples/task-branches.json` unchanged, which encodes E1 to E5, A1 and A2
(`task/*` pushes, no commit on `main` or `master`, lease-push and delete of `task/*` only after a
merged PR, `core.hooksPath` fixed to `tools/git-hooks`). Today only the gate and the status line
read the file; `git-guard.js` and `post-edit.js` keep these rules as constants until they are next
taken from the base, and this ADR is superseded then.

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
