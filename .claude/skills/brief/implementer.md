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
named in your report.

Context only this task knows: <owner's words, decisions since the task was written, or "none">.
Already ruled out: <what was tried and why it failed, with finding paths, or "nothing">.
Sequencing: <branches that touch the same files and the order; the task an **Interacts with:** line
names, with the mechanism and whether its branch has merged; or "none">.

Choices in doubt (ADR 0039, ADR 0044): <the research already done for this task and its answers, or
"none">. A choice the task, an ADR or the owner leaves open is not yours to make from taste: launch a
read-only research agent from the `brief` skill's `benchmark.md` (Sonnet-class, one question) and cite
it; where it shows users or apps split, build a setting with the researched default (synced key, row,
help text, debug route) and say why it is one.

Done when: <the Done-when line>, plus `./gradlew check` green and the docs your change touches
updated on the same branch (`CLAUDE.md`, Debug before you push).

Process: temporary files go in a folder of your own under the session scratchpad (`<scratchpad>\Tnn\`;
every agent of the session shares the scratchpad root, and files written there have been overwritten
by another agent); commit as you go; debug end to end with the real tooling and view the PNGs; before you hand over:
`./gradlew check`, `git fetch && git rebase origin/main`, push `task/Tnn`. There are no pull requests
(ADR 0043): your report to the coordinator is the record and becomes the merge commit's body. Stop only processes you started, by PID or serial; stop your Gradle
daemons before you report. Wait in the foreground (`until <check>; do sleep 5; done`); start no
background sleep or monitor loop, and leave nothing of yours running when you report (a leftover
wakes you again and the coordinator reads each wake as a new result).

Report to the coordinator: the branch's head SHA, what was run and seen (numbers, screenshots
described, public places only), the research or decision behind each open choice you faced and the
reason for each setting you added (ADR 0044; the report, not the wave paragraph, is the record), every
edit outside Owns, follow-ups, what is not done, anything you could not place. Under 40 lines; it is
pasted into the merge commit.
