---
date: 2026-10-12
status: resolved
area: tooling / deleting worktrees on Windows
promoted_to: docs/reference/windows-and-shells.md
---

# Deleting a worktree with long Gradle paths: the `\\?\` prefix is what matters, and CLAUDE.md lost a backslash

## Question

Gradle and Android build output in a worktree nests below the 260-character path limit, so
`git worktree remove` fails. `CLAUDE.md` said to delete with `Remove-Item -LiteralPath \?\<path>
-Recurse -Force`; the coordinator reported (T84 brief, 2026-10-12) that `Remove-Item` fails on long
Gradle paths and `[System.IO.Directory]::Delete('\\?\' + path, $true)` works. Which is right?

## What was tried

On this machine (Windows 11, `LongPathsEnabled` = 0, Windows PowerShell 5.1.26100), a tree of eight
45-character directories (504-character path) with one file, built through the `\\?\` prefix in the
session scratchpad, then deleted five ways:

| Command | Result |
|---|---|
| `Remove-Item -LiteralPath <path> -Recurse -Force` (no prefix) | fails: `DirectoryNotFoundException`, "Could not find a part of the path" |
| `Remove-Item -LiteralPath ('\?\' + <path>) ...` (the form printed in `CLAUDE.md`) | fails: `DriveNotFoundException`, "A drive with the name '\?\C' does not exist" |
| `Remove-Item -LiteralPath ('\\?\' + <path>) -Recurse -Force` | works, also with a read-only file in the tree |
| `[System.IO.Directory]::Delete('\\?\' + <path>, $true)` | works; fails on a read-only file ("Access to the path 'f.txt' is denied") |
| `robocopy <empty> <dir> /MIR`, then delete (Hatchery's finding) | works (not re-run here) |

## Result

Plain `Remove-Item` fails on long paths, and the `CLAUDE.md` line had lost one backslash of the
`\\?\` prefix (most likely in an edit through a shell that ate it), so anyone following it got a
"drive not found" error; that matches the coordinator's report. With the full `\\?\` prefix both
`Remove-Item -Recurse -Force` and `[System.IO.Directory]::Delete(..., $true)` work; `Remove-Item
-Force` also removes read-only files, `Directory.Delete` does not. Quote the prefix in single quotes
in PowerShell, and do not pass it through Git Bash (`\\` collapses). Then `git worktree prune`.
Promoted to `docs/reference/windows-and-shells.md`; sent to Hatchery as
`docs/findings/2026-10-12-windows-long-path-delete.md` there.
