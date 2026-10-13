Goal: <one sentence: the outcome, for example "the live server runs on 8765 from the main checkout"
or "task/Tnn's worktree and branches are gone after PR #n merged">.

Exact commands, in order (nothing to interpret; stop at the first failure and report it):
1. <command>
2. <command>

Directory: <absolute path; use `git -C <path>` for git, and print `git rev-parse --show-toplevel`
before any commit or push>.

Do not touch: <paths, servers, emulators, ports that belong to others>. Stop only processes you
started, by PID (record them in <file>) or emulator serial.

Verify by: <an independent read: `gh pr view <n> --json mergedAt`, `git ls-remote origin task/Tnn`,
`curl http://localhost:<port>/stats`, the JSON summary of a sweep>.

Report: the output of the verification step, PIDs started, anything that failed. Under 15 lines.
On a failure that needs judgment, stop; the coordinator relaunches with a Sonnet-class agent.
