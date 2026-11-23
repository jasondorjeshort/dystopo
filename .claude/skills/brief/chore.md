Goal: <one sentence: the outcome, for example "the live server runs on 8765 from the main checkout"
or "task/Tnn's worktree and branches are gone, its merge being on origin/main">.

Exact commands, in order (nothing to interpret; stop at the first failure and report it):
1. <command>
2. <command>

Directory: <absolute path; use `git -C <path>` for git, and print `git rev-parse --show-toplevel`
before any commit or push>.

A worktree removal is one command, never a hand-written delete:
`powershell -ExecutionPolicy Bypass -File <main>\tools\worktree-remove.ps1 -Path <worktrees>\Tnn -WorktreeRoot <worktrees>`
(the long-path delete, the unlock and `git worktree prune` built in). Exit 4 names the process that holds a
file: report it, stop nothing; exit 2 or 5 is a refusal to report as it stands.

Do not touch: <paths, servers, emulators, ports that belong to others>. Stop only processes you
started, by PID (record them in <file>) or emulator serial.

Waiting: wait in the foreground (`until <check>; do sleep 5; done`); start no background sleep or
monitor loop, and leave nothing of yours running when you send the report: a leftover wakes you
again after the report and the coordinator reads each wake as a new result.

Verify by: <an independent read: `git branch -r --merged origin/main`, `git ls-remote origin task/Tnn`,
`curl http://localhost:<port>/stats`, the JSON summary of a sweep>.

Report: the output of the verification step, PIDs started, anything that failed. Under 15 lines.
On a failure that needs judgment, stop; the coordinator relaunches with a Sonnet-class agent.
