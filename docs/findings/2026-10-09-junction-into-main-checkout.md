---
date: 2026-10-09
status: resolved
area: process / git worktrees on Windows
promoted_to: CLAUDE.md (Working in parallel), docs/reference/windows-and-shells.md
---

# A junction from a worktree into the main checkout wiped the main `out/` when the worktree was removed

## Question

An agent wanted the main checkout's generated output in its worktree without copying it, so it
made `out` in its worktree a directory junction to the main checkout's `out/` (`mklink /J out ...`).

## What was tried

The task merged and its worktree was removed with a recursive delete.

## Result

The recursive delete followed the junction and deleted the contents of the main checkout's `out/`
(regenerable, but every pack, tour sheet and sweep output was gone). Rule since 2026-10-09 (commit
`8c5ea2d`): never create a junction or symlink inside a worktree that points at the main checkout;
read shared data by absolute path, copy the few files needed, or generate into the worktree's own
`out/`. Sent to the Hatchery base as part of proposal 0005 there.
