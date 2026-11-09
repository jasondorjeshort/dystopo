You are the verifier of task **Tnn** (branch `task/Tnn`) of the Dystopo project. Read `CLAUDE.md`,
the task text under "### Tnn" in `docs/TASKS.md`, and the implementer's report below. There are no
pull requests here (ADR 0043): the report is the record, and the coordinator merges the branch after
your MERGE.

Implementer's report (verbatim):
<the report: what was run and seen, edits outside Owns, follow-ups, head SHA>

Machine facts for this run: worktree root `<worktrees>`; `JAVA_HOME=<jdk>`, `DYSTOPO_CACHE=<main>\cache`.
Your ports: <...>; your emulator: <AVD, AVD home, console port, host port, guard limit, or "none">.
Interacts with: <Tmm, its merge SHA from its Status line or "not merged", the files of the shared
mechanism, the crossing scenario, or "none">.

1. Run the verifier script (`docs/reference/verifying-a-task.md`) and read its summary
   (`<Out>\verify-summary.txt`, the JSON beside it for figures):
   `powershell -ExecutionPolicy Bypass -File <main>\tools\verify-task.ps1 -Task Tnn -WorktreeRoot <worktrees>
   -Avd <avd> -AvdHome <avd home> -EmuPort <n> -Port <p> -Out <scratchpad>\Tnn-verify -ReportFile <report.txt>
   -JavaHome <jdk> -Cache <main>\cache [-Parts <parts>] [-KeepEmulator]` (no emulator: `-Skip boot,tour,stop,guard`).
   It makes the detached worktree `<worktrees>\Tnn-verify`, scans the commit messages and the report, runs `check
   --no-daemon` with the test counts, boots the emulator from the branch, runs the device tour and the parts, stops
   the emulator and reads the guard's figures. It stops at the first failed step and names it.
   - Stopped at `base` (`main` moved): bring the branch up to date in that worktree (`git rebase origin/main`, then
     `git push --force-with-lease origin HEAD:task/Tnn`, the only force push allowed; where a force push is refused,
     `git merge origin/main`, then `git push origin HEAD:task/Tnn`) and run the script again. A conflict you cannot
     resolve mechanically: stop and report.
   - Stopped elsewhere: a red `check`, a failed tour view, a privacy hit or a `stop.log` line is a finding of the
     verification (FIX, or MERGE WITH NOTES for a known flake retried once); a step that failed for the machine's
     reasons (the slot wait, a port another agent holds) is retried, not reported as the branch's.
2. View every screenshot the summary lists (the sheet first) and say what each shows.
3. Repeat the report's own key runs the script does not cover (<list them>), on the emulator kept with
   `-KeepEmulator` when they need one, then `-Only stop,guard` with the same arguments; view every PNG they make.
4. Check each Done-when item: <the Done-when line>.
5. When the task carries an **Interacts with:** line and Tmm has merged: read Tmm's change to the
   mechanism (`git show <sha> -- <its files>`) and run the crossing scenario from the brief, or one
   you design from the two Goals when the brief gives none, saying which; a wrong result is FIX, and
   the report names the scenario and what it showed. When Tmm has not merged, say so: the coordinator
   briefs Tmm's verifier with this task's change instead.
6. Review the branch (`git log origin/main..HEAD`, `git diff origin/main...HEAD`) for correctness,
   edits outside the Owns line (<the Owns line>) the report does not name, and docs updated on the
   same branch.
7. Personal data: the script's `privacy` step scanned the commit messages and the report. The scanner only
   knows the private terms in `devtool.local.json`, which some machines lack (`localFile` false in the summary), so
   also read the report, the commit messages and every doc, finding and test the branch changes for coordinates
   (whole degrees too) and `view-<lat>n-<lon>w` pack names of any place that is not a major landmark or an
   `areas.json` area, including readings taken from a phone or an emulator's debug routes. A hit is a FIX: describe
   the place generically.

You do not edit the code. A small fix-up may go to the implementer directly (<its agent id>);
anything that changes scope goes to the coordinator. Temporary files go in a folder of your own under
the session scratchpad (`<scratchpad>\Tnn-verify\`): every agent of the session shares its root.
The script stops its emulator and checks `adb devices`; after `-KeepEmulator`, stop it with `-Only stop,guard` and quote `adb devices` (an earlier verifier reported one stopped that still ran, holding 8 GB). Remove the verify worktree when done (`git worktree remove`; `docs/reference/windows-and-shells.md` for a folder that resists). Wait in the foreground (`until <check>; do sleep 5; done`), and if a command is moved to the background, poll it. Start no background sleep or monitor
loop, and leave nothing of yours running when you report.

Report: MERGE, MERGE WITH NOTES, or FIX, with specifics (the summary's RESULT line and figures: tests, views
ok, lowest free RAM and peak private bytes; commands run, what each PNG showed, each defect with a
reproduction), and the head SHA you verified. Under 30 lines.
