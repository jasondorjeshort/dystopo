---
date: 2026-11-22
kind: chore
---

# Short worktree paths: every cleanup in wave 46 hit "Filename too long"

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

Task worktrees live in a `dystopo-wt` folder beside the checkout, one per task (`docs/reference/machines.md`), and Gradle's build folders nest deep enough that `git worktree remove` fails with "Filename too long" in nearly every verifier's cleanup; the fallback is a PowerShell delete with the long-path prefix, which each brief now spells out. Twice in wave 46 a folder then stayed for hours because a Gradle daemon nobody may stop by name still held a file in it.

What it would take: a short worktree root (a few characters, on the same drive), `core.longpaths` set for the clones, and the cleanup chore's delete as one script that knows the prefix, so briefs stop carrying it. For the held files: worktrees built with `--no-daemon` only, or a daemon per worktree stopped by its own PID at hand-over.

## Open

- Whether the long paths come from the worktree root or from the package path itself (the root can be shortened, the package path cannot).
- `docs/reference/windows-and-shells.md` is where the rule and the script would be written down.

Question: How far should the fix go, given that a new worktree root is a folder on the owner's disk and that
`core.longpaths` is not set in this clone today (Git for Windows then handles long paths in `git worktree remove`
on its own)?
Recommendation: [no-check: the script is proposed, not built] A, because `core.longpaths` plus one cleanup script (`tools/worktree-remove.ps1`, the `\\?\` delete and
`git worktree prune` built in) may end the failures with no move; measure the longest build path first and move the
root only if removes still fail. The held-file half conflicts today: `windows-and-shells.md` says `./gradlew --stop`
in the worktree, `CLAUDE.md` forbids stopping daemons you did not start; the script should stop only its own by PID.
Options:
A. `core.longpaths` and a cleanup script now; a shorter root only if removes still fail.
B. All at once: a short root on the same drive (the owner names it), `core.longpaths`, the script.
C. Only the script; leave paths and config as they are.
D. Not worth building: briefs keep spelling out the prefix.

Answer: A, set core.longpaths and add one cleanup script now; a shorter root only if removes still fail (the owner, 2026-11-22, multiple choice).
