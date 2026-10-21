---
verified: 2026-10-17
source: observed (machine user name in paths, ADR 0018 evidence 2026-10-17; PowerShell pipe and MSYS git show rewrite 2026-10-17; npm behaviour tested 2026-10-14; twelve waves of parallel agents on a downstream project; Windows 10 and 11, Claude Code 2.1.x, Git Bash and Windows PowerShell 5.1); hook and status line code read 2026-10-14, usage-gate grants 2026-10-15, per-session usage file 2026-10-16; robocopy and Remove-Item behaviour with junctions observed 2026-10-14
---

# Claude Code on Windows: what holds

## Processes

- A tool call's process tree ends with the call. Emulators, servers and soak scripts are started
  detached (`Win32_Process.Create` or `Start-Process -WindowStyle Hidden`) with the PID written to a
  file, and stopped later by that PID only.
- Never stop a process by name or command-line substring: with several agents on one machine that
  kills another agent's emulator or the coordinator's live server. Stop by PID, or by serial for an
  emulator (`adb -s emulator-NNNN emu kill`).
- Each parallel agent gets its own ports and its own emulator (AVD name, console port, host port)
  assigned in the brief; a shared port range in the brief is how collisions are avoided.

## Git worktrees

- One worktree per agent under `.claude/worktrees/` [no-check: a build-phase project's directory, absent in the base].
  `git worktree remove` fails once build output nests past 260 characters, and so does
  `Remove-Item` on a plain path (`DirectoryNotFoundException`). The `\\?\` prefix is the removal.
- Removal, in Windows PowerShell, with the path set once as absolute with backslashes,
  `$path = (Resolve-Path <dir>).Path`, with backslashes because .NET calls
  (`[System.IO.Directory]::Delete`) do not normalise `\\?\C:/...` and `cmd /c rmdir` likely does not
  either; PowerShell cmdlets accept it and normalise:
  1. Links first. A downstream worktree once held a junction into the main checkout, and a
     recursive delete of the worktree emptied the main checkout's `out/`; which tool deleted
     through the link was not established (`Remove-Item -Recurse -Force`, plain or prefixed, left
     a junction's target intact when tested; symbolic links untested). List reparse points with
     `Get-ChildItem -LiteralPath ('\\?\' + $path) -Recurse -Force -Attributes ReparsePoint`; the
     plain path misses a link past 260 characters. Remove each link itself with
     `cmd /c rmdir` on its `\\?\`-prefixed full name, which removes the junction, not its target.
  2. `Remove-Item -LiteralPath ('\\?\' + $path) -Recurse -Force`. The prefix is what makes a long
     path deletable; `-Force` also clears read-only files, which
     `[System.IO.Directory]::Delete('\\?\' + $path, $true)` does not. Write the prefix in single
     quotes and never pass the command through Git Bash: a shell once collapsed `\\` to `\`, and
     `\?\` fails with `DriveNotFoundException`.
  3. `git worktree prune`; `git worktree unlock` first if the harness locked it.
- Never clear a worktree with an empty `robocopy /MIR` mirror: it purges through a junction into
  the target, with `/XJ`, with `/XJD` and with no flag.
- The harness creates a worktree only when the shell's cwd is inside the repo; reset the cwd before
  launching an agent after any `cd` elsewhere.

## Shells

- Bash heredocs: any heredoc body with an apostrophe outside double quotes is rejected before bash
  runs it (`unexpected EOF while looking for matching` quote), a quoted delimiter does not help,
  and `\\` is collapsed to `\`, so `\\n` and `\\?\` break. Write such text with the Write or Edit tool. Root cause not
  confirmed (tool-side pre-processing suspected).
- Windows PowerShell 5.1: quote arguments containing dots or dashes whole when calling
  `powershell -File`; it drops empty-string arguments; `&&` and `||` do not exist; `2>&1` on a
  native command wraps stderr in error records.
- The PowerShell 5.1 pipe re-encodes a patch: `git diff ... | git apply --3way` fails every file with
  `patch does not apply` and leaves `git status` empty; in Bash it applies. The update command
  (`.claude/hooks/ownership.js --command`) is run in the Bash tool only.
- Git Bash (MSYS) rewrites the argument of `git show hatchery/main:<path>`, quoted or not, and it
  fails. `S=$(git rev-parse hatchery/main)` then `git show <sha>:<path>` works, as does
  `MSYS_NO_PATHCONV=1`. Name the sha in a skill. Evidence: finding 2026-10-17-msys-rewrites-git-show-paths.
- `gh ... --jq` with a backtick inside the expression breaks in PowerShell; run `gh` from bash.

## Working directory and git

- A `cd` to another repository inside a Bash call may not take effect (the harness resets the
  working directory between calls, and did so mid-session: a commit and push once landed in the
  other repository and were reported as done). Name the repository: `git -C <path> ...`, and start
  any commit or push command with `git rev-parse --show-toplevel` so the report shows where it ran.
- Prove a push: after `git push`, `git fetch` and compare `git rev-parse HEAD` with the remote ref;
  report both SHAs. A command's own success output is not evidence of what reached the remote.
- Never truncate a status check to its first line; `git status --short` in full.

## The machine user name is in every path

- Every absolute path on a Windows PC carries the machine user name: the profile folder, the
  session scratchpad, the project-folder slug under `~/.claude/projects/` (the drive and profile
  path with separators turned into hyphens), and a grant file's `project` scope. None of these
  belongs in committed text; write `~`, `<repo>` or `<scratchpad>`. The push scan reads every
  spelling (ADR 0018, `.claude/rules/docs.md`, "Personal data").

## npm on this PC

- The owner's `~/.npmrc` has `location=global`, so a plain `npm install` is a global install and
  `npm install --no-global` prunes the global folder. Never run a bare `npm install`, `npm ci` or
  `npm install --no-global`; `--location=project` and `npm_config_location` do not override it.
- Working form, tested: `npm_config_userconfig=<path to an empty file> npm --prefix <dir> install`.
  The empty user config hides the setting; the global folder was unchanged and `node_modules`
  appeared in `<dir>`. Evidence: observed 2026-10-14 in a downstream project.

## Shared state

- The session scratchpad is shared by every agent of the session: unique file names per agent.
- A shared download cache (environment variable pointing at one directory) is safe for concurrent
  readers; agents never delete inside it and never bump a cache version that would wipe it for the
  others.
- The Windows Recycle Bin receives files deleted through Explorer only; agent shells delete
  permanently. A folder that turns up in the bin was deleted by a person.

## Usage meters

- The status line (`.claude/statusline.js`) writes the 5-hour and weekly percentages to one cache
  file per session, `~/.claude/usage-state/<session_id>.json`, labelled with the logged-in account;
  the `usage-gate` hook (PreToolUse on `Agent|Workflow`) reads the file of the `session_id` it is
  given and refuses a new launch while either meter is at or above its HOLD line. A file labelled
  with another account than the one logged in counts as no data. Bash is never gated. ADR 0011.
- The lines are policy, not code: `usage.hold` and `usage.deny` in `.claude/hooks/policy.json`.
  `usage.fiveHour` and `usage.sevenDay` override either value for one meter, `hold` and `deny`
  inherited separately (a meter may set its own `hold` and inherit the shared `deny`). Each meter
  is judged against its own lines and the worse verdict wins. The base ships HOLD at 50 and no
  deny line; a project that wants a ceiling sets `deny`.
- Two lifts, for HOLD only, never a deny line: grant files in `~/.claude/usage-gate-overrides/`
  (one `*.json` each: until when, for which meters, why, and a scope of one session, one project
  path, or the user; the gate matches them against `session_id` and `cwd` on its stdin) and an
  environment variable named by `usage.overrideEnv`, set to `1` when the session starts, which
  lifts HOLD on both meters for that session. The legacy single file
  `~/.claude/usage-gate-override.json` is still read, as a user grant when it names no scope. Both
  lifts exist only on the owner's instruction. Format, fail modes and the reasoning:
  ADR 0006 and `../decisions/0002-hook-specs.md`.
- Claude Code sends `rate_limits` to the status line only after the session's first response.
  Until then a new session has no file, so the gate finds no data and `usage.whenStale` decides
  (base: allow); after that, a session's file keeps its last reading, which the gate uses until it
  is older than `usage.staleSeconds` (base 7200). On a new machine or after a long gap, run `/usage`
  by hand before the first launch.
