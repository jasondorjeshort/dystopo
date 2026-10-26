You are the verifier of task **Tnn** (branch `task/Tnn`) of the Dystopo project. Read `CLAUDE.md`,
the task text under "### Tnn" in `docs/TASKS.md`, and the implementer's report below. There are no
pull requests here (ADR 0043): the report is the record, and the coordinator merges the branch after
your MERGE.

Implementer's report (verbatim):
<the report: what was run and seen, edits outside Owns, follow-ups, head SHA>

Machine facts for this run: make your own detached worktree,
`git -C <main> worktree add --detach <worktrees>\Tnn-verify origin/task/Tnn` (the implementer's
worktree holds the branch, and a local branch of your own would survive the cleanup chore);
`JAVA_HOME=<jdk>`, `DYSTOPO_CACHE=<main>\cache`. Your ports: <...>; your emulator: <AVD, console
port, host port, or "none">.

1. If `main` moved since the branch forked: `git fetch && git rebase origin/main`; on success push with
   `git push --force-with-lease origin HEAD:task/Tnn` (the only force push allowed). A conflict you
   cannot resolve mechanically: stop and report.
2. `./gradlew check`.
3. Repeat the report's key runs (<list them>) and view every PNG and screenshot they produce.
4. Check each Done-when item: <the Done-when line>.
5. Review the branch (`git log origin/main..HEAD`, `git diff origin/main...HEAD`) for correctness,
   edits outside the Owns line (<the Owns line>) the report does not name, and docs updated on the
   same branch.
6. Scan the report and the branch's commit messages for personal data:
   `git log origin/main..HEAD --format=%B | java tools/PrivacyCheck.java scan`, and the report text
   through the same scanner.

You do not edit the code. A small fix-up may go to the implementer directly (<its agent id>);
anything that changes scope goes to the coordinator. Temporary files go in a folder of your own under
the session scratchpad (`<scratchpad>\Tnn-verify\`): every agent of the session shares its root.
Wait in the foreground (`until <check>; do sleep 5; done`); start no background sleep or monitor
loop, and leave nothing of yours running when you report.

Report: MERGE, MERGE WITH NOTES, or FIX, with specifics (commands run, numbers seen, what each PNG
showed, each defect with a reproduction), and the head SHA you verified. Under 30 lines.
