You are the implementer of task **Tnn** of the Dystopo project. Read `CLAUDE.md` first, then your task
text under "### Tnn" in `docs/TASKS.md` and the wave's paragraph at the top of its section, then
<specific files, PLANNING sections, ADRs, or "nothing else">.

Machine facts for this run (they override the docs): main checkout `<path>` (never edit files there);
make your worktree with `git -C <main> worktree add <worktrees>\Tnn -b task/Tnn origin/main` and work
only inside it; copy `local.properties` from the main checkout; `JAVA_HOME=<jdk>` and
`DYSTOPO_CACHE=<main>\cache` in every shell. Your ports: <host ports>; your emulator: AVD
`<name>` under `<avd home>`, console port <n>, host port <p> (or "no emulator needed"). Servers you
may read but never stop or ask to build: <the coordinator's live server, or "none">.

Task (from TASKS.md, binding): <Goal, verbatim or "as written">.

Owns: <the Owns line, plus anything resolved for this wave>. Anything else: a few lines at most,
named in the PR.

Context only this task knows: <owner's words, decisions since the task was written, or "none">.
Already ruled out: <what was tried and why it failed, with finding paths, or "nothing">.
Sequencing: <PRs that touch the same files and the order, or "none">.

Done when: <the Done-when line>, plus `./gradlew check` green and the docs your change touches
updated in the same PR (`CLAUDE.md`, Debug before you push).

Process: temporary files go in a folder of your own under the session scratchpad (`<scratchpad>\Tnn\`;
every agent of the session shares the scratchpad root, and files written there have been overwritten
by another agent); commit as you go; debug end to end with the real tooling and view the PNGs; before the PR:
`./gradlew check`, `git fetch && git rebase origin/main`, push `task/Tnn`, `gh pr create --fill`
with a body listing what was run and seen (numbers, screenshots described, public places only), every
edit outside Owns, and follow-ups. Stop only processes you started, by PID or serial; stop your Gradle
daemons before you report.

Report to the coordinator: PR number, what was measured, what is not done, anything you could not
place. Under 30 lines.
