---
date: 2026-11-10
kind: chore
related: [2026-11-10-the-meter-reading-per-agent-recorded-by-a-hook.md]
---

# A pushed branch takes `main` by merge, never by rebase: the rule the force-push ban implies

`.claude/agreement/git.md` says "Never force-push" (ADR 0018) and the guard refuses it, but the same file and the
build-phase sibling's rules tell an implementer to `git fetch && git rebase origin/main` before pushing. The two
collide once the branch is pushed: in Dystopo's wave 31 (2026-11-10) a verifier asked an implementer to take `main`
after it moved, the implementer rebased its pushed task branch, and the push was refused for good, since no force
push works there; the fix round cost a merge of `origin/main` into the branch and a second push, and the wave's
plan now says "merging `main` in, never rebasing a pushed branch" for every file several tasks append to.

- One sentence in `git.md`'s amend bullet: a rebase is for the unpushed branch; once pushed, a branch takes `main`
  by `git merge origin/main`, and the merge commit is what the verifier and the coordinator read.
- The `commit` skill's `git pull --rebase origin <branch>` is the main session's own branch and stays.
- Optional: the guard could warn on `git rebase` when the branch has an upstream (a read of `@{upstream}`), as it
  warns on a push that is not on a line of its own.

## Open

- Whether the sibling's `CLAUDE.md` line ("`git fetch && git rebase origin/main`") should read "before the first
  push" in the base's own text, so decanted projects start with the distinction.
