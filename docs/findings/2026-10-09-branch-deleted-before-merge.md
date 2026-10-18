---
date: 2026-10-09
status: resolved
area: process / merge sequence
promoted_to: .claude/hooks/git-guard.js (ADR 0021, E4 and E5), .claude/skills/wave/SKILL.md
---

# Deleting a task branch before its merge had succeeded closed the pull request

## Question

After a merge attempt in wave 3 or 4, a pull request was closed and unmerged. Why?

## What was tried

The coordinator's merge sequence merged with `gh pr merge <n> --squash` and then deleted the task
branch. The branch was deleted although the merge had not succeeded; GitHub closes a pull request
whose head branch is deleted. The same rule text records that PowerShell 5.1 splits arguments on
double quotes, which is why merge bodies are quote-free (the commit, `4a3a399`, does not say whether
that was the failure that day).

## Result

Recovered by recreating the branch at the recorded SHA and `gh pr reopen <n>`. Rule since 2026-10-09
(commit `4a3a399`): only on a successful merge delete the remote branch, then fast-forward `main`,
remove the worktree and delete local branches. Since T84 the git guard enforces it: a `task/*` branch,
remote or local, is deleted only when `gh pr list --head <branch> --state merged` shows its pull
request merged (ADR 0021). Sent to the Hatchery base as part of proposal 0005 there.
