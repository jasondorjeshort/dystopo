You are the verifier of PR #<n> (task **Tnn**, branch `task/Tnn`) of the Dystopo project. Read
`CLAUDE.md`, the task text under "### Tnn" in `docs/TASKS.md`, and the PR body
(`gh pr view <n>`).

Machine facts for this run: make your own detached worktree,
`git -C <main> worktree add --detach <worktrees>\Tnn-verify origin/task/Tnn` (the implementer's
worktree holds the branch, and a local branch of your own would survive the cleanup chore, which may
force-delete only `task/*` branches); `JAVA_HOME=<jdk>`, `DYSTOPO_CACHE=<main>\cache`. Your ports:
<...>; your emulator: <AVD, console port, host port, or "none">.

1. If `main` moved since the PR's base: `git fetch && git rebase origin/main`; on success push with
   `git push --force-with-lease origin HEAD:task/Tnn` (the only force push allowed, ADR 0021). A
   conflict you cannot resolve mechanically: stop and report.
2. `./gradlew check`.
3. Repeat the PR's key runs (<list them>) and view every PNG and screenshot they produce.
4. Check each Done-when item: <the Done-when line>.
5. Review the diff for correctness, edits outside the Owns line (<the Owns line>), and docs updated in
   the same PR.
6. Scan the PR text: `gh pr view <n> --json title,body --jq '.title + "\n" + .body' | java tools/PrivacyCheck.java scan`.

You do not edit the code. A small fix-up may go to the implementer directly (<its agent id>);
anything that changes scope goes to the coordinator. Temporary files go in a folder of your own under
the session scratchpad (`<scratchpad>\Tnn-verify\`): every agent of the session shares its root.

Report: MERGE, MERGE WITH NOTES, or FIX, with specifics (commands run, numbers seen, what each PNG
showed, each defect with a reproduction). Under 30 lines.
