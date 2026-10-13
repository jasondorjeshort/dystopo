---
verified: 2026-10-12
source: observed on this machine (Windows 11, Windows PowerShell 5.1.26100, Git Bash); findings linked below
---

# Windows and shells

What holds on the development machine for every agent. The rules these facts support are in
`CLAUDE.md` (Working in parallel); Hatchery keeps the same facts for the base in its
`docs/reference/claude-code-on-windows.md`.

## Shells

- Two tools: Git Bash (POSIX) and Windows PowerShell 5.1. In 5.1 `&&` and `||` do not exist (use
  `A; if ($?) { B }`), and `2>&1` on a native command wraps stderr in error records.
- PowerShell 5.1 splits a native command's arguments on double quotes: `gh pr merge` bodies are
  quote-free, and arguments containing dots or dashes passed to `powershell -File` are quoted whole.
- `gh ... --jq` with special characters in the expression breaks in PowerShell; run `gh` from Git Bash.
- Bash heredocs: no apostrophes, no `\n` sequences; write such text with the Write or Edit tool. The
  same for text that mentions a destructive git command (the `git-guard` hook reads the command text).
- If `java` is not on PATH in a shell, set `JAVA_HOME` (path in `machines.md`); the git hooks need it.

## Working directory and git

- A `cd` to another repository inside a Bash call may not take effect. Name the repository:
  `git -C <path> ...`, and start any commit or push meant for another repository with
  `git -C <path> rev-parse --show-toplevel` (finding `../findings/2026-10-11-reported-push-that-never-happened.md`).
- Prove a push from the remote: `bash tools/push-verified.sh` (push, fetch, compare SHAs, print
  VERIFIED); `gh pr view --json mergedAt` for a merge; `gh release view` for a release.
- The git hooks (`tools/git-hooks`, `git config core.hooksPath tools/git-hooks`, once per clone) run in
  worktrees too.

## Worktrees

- Gradle and Android build output exceeds the 260-character path limit, so `git worktree remove`
  fails. Delete the directory with the long-path prefix, in PowerShell, single-quoted:
  `Remove-Item -LiteralPath ('\\?\' + $path) -Recurse -Force` (also removes read-only files), or
  `[System.IO.Directory]::Delete('\\?\' + $path, $true)` (fails on a read-only file), then
  `git worktree prune`. Without the prefix `Remove-Item` fails with "Could not find a part of the
  path"; with one backslash missing (`\?\`) it fails with "A drive with the name '\?\C' does not
  exist". Do not pass the prefix through Git Bash, which collapses `\\`. Fallback: an empty
  `robocopy <empty> <dir> /MIR`, then delete. A worktree the harness locked needs
  `git worktree unlock` first (finding `../findings/2026-10-12-windows-long-path-delete.md`).
- Stop your Gradle daemons (`./gradlew --stop` in the worktree) before reporting, or a held directory
  survives the delete.
- Never create a junction or symlink inside a worktree that points at the main checkout: a recursive
  delete of the worktree follows it (finding `../findings/2026-10-09-junction-into-main-checkout.md`).

## Processes

- A tool call's process tree ends with the call. Servers, emulators and soak scripts are started
  detached with the PID written to a file, and stopped later by that PID only.
- Never stop a process by name or command-line substring (finding
  `../findings/2026-10-10-kill-by-command-line-substring.md`). Stop by PID, or by serial for an
  emulator (`adb -s emulator-NNNN emu kill`, or `android-emu.ps1 -Stop`).

## Shared state

- The session scratchpad is shared by every agent of a session: unique file names per agent.
- The shared download cache (`DYSTOPO_CACHE`) is safe for concurrent readers; agents never delete
  inside it and never bump a cache version that would wipe it for the others.
- Agent shells delete permanently; a folder in the Recycle Bin was deleted by a person.
