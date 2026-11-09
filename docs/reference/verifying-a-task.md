---
verified: 2026-11-09
source: observed in T246 (tools/verify-task.ps1 run against its own branch on the second PC); idea 2026-11-08, cheaper verification
---

# Verifying a task with tools/verify-task.ps1

The verifier's mechanical half as one command (T246): the worktree, the base check, the privacy scan, `check`, the
emulator, the device tour, any `android-ui.py` parts, the stop and the guard's figures. The verifier agent runs it,
reads its summary, and keeps for itself what needs judgement: the report's own scenario, every screenshot, the review
of the diff and the verdict (`.claude/skills/brief/verifier.md`). The measurement of what it saves is the wave's
close against 2.5 points of the 7-day meter a task (the `wave` skill).

## Running it

From the main checkout (the script lives there once merged; it runs the branch's own `tools\android-emu.ps1`,
`android-check.py` and `gradlew` from the verify worktree), with the brief's machine facts:
```
powershell -ExecutionPolicy Bypass -File tools\verify-task.ps1 -Task T247 -WorktreeRoot C:\Users\<you>\Documents\src\dystopo-wt `
    -Avd dystopo35-T247v -AvdHome E:\dystopo-avd -EmuPort 5570 -Port 8694 -Out <scratchpad>\T247-verify `
    -JavaHome "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot" -Cache C:\Users\<you>\Documents\src\dystopo\cache
```
- `-WorktreeRoot` gets `<Task>-verify` (`git worktree add --detach`, at `origin/task/<Task>`). Default: the main
  checkout's sibling `<name>-wt`. A clean one left from an earlier run is moved to the branch's tip instead. An
  empty folder of that name is used as it is: after T246's run `git worktree remove` and the long-path delete
  emptied it, but a process started during the run (likely the shared adb server) still held it as its directory.
- `-Avd`, `-AvdHome`, `-EmuPort`, `-Port`: the emulator of the brief; `-PrivateLimitGB` the guard limit when the brief
  gives one (else `policy.json`'s); `-Owner` the lock's owner (default `<Task>-verify`); `-Server` the app's server
  for the boot and the tour (default `none`; never the coordinator's 8765, which the script refuses).
- `-JavaHome`, `-Cache`: `JAVA_HOME` and `DYSTOPO_CACHE` for every program it runs (defaults: the environment, then
  the JDK paths of `machines.md` and the main checkout's `cache`). `GRADLE_OPTS` gains `-Dorg.gradle.daemon=false`.
- `-Parts routes,search` runs `android-ui.py <part> --serial --port --out` after the tour; `-UiArgs` adds arguments
  (the release parts need `--apk`, `release-realism.md`).
- `-ReportFile <file>`: the implementer's report as text, scanned with the commit messages.
- `-Out`: where everything goes (default `<worktree>\out\verify`, deleted with the worktree; give the scratchpad).
- `-DryRun` prints every command and runs nothing; `-Skip a,b` and `-Only a,b` choose steps; `-Continue` runs on past
  a failure; `-AllowBehind` goes on when `origin/main` moved; `-KeepEmulator` leaves the emulator running.

Exit 0 when every step that ran passed, 1 when one failed, 2 for bad arguments. A run takes as long as `check` (minutes
on a fresh worktree) plus the boot and the tour (about ten minutes); run it in the background and poll its log, or in
the foreground with a ceiling over 40 minutes.

## The steps

| Step | What | Fails when |
|---|---|---|
| `fetch` | `git fetch --prune origin` in the main checkout; the branch's head SHA | no `origin/task/<Task>` |
| `worktree` | the detached worktree (or the existing one moved to the tip) | the folder is not a worktree, or has changes |
| `base` | `merge-base origin/main origin/task/<Task>`; commits on `main` since | `main` moved (`-AllowBehind` passes) |
| `privacy` | `git log origin/main..origin/task/<Task> --format=%B` (and `-ReportFile`) through `PrivacyCheck.java scan` | any hit |
| `data` | `local.properties` and the Rainier packs copied from the main checkout (never linked) | a source missing |
| `check` | `gradlew check --no-daemon`; counts from every `TEST-*.xml` under `*/build/test-results`, per module | exit not 0 |
| `slot` | the console port's shape and owner, then waits (adb every 10 s, `-SlotWaitMinutes` 60) for the cap | a port another AVD holds, or the wait ends |
| `boot` | `android-emu.ps1 -Build` with the brief's AVD and ports, from the worktree | exit not 0 (its last lines named) |
| `tour` | `android-check.py --views-file tools/android-tour.txt --server <Server>` into `tour\` | the tour's `ok` false (failed views listed) |
| `ui-<part>` | each `-Parts` entry | the part's `ok` false |
| `stop` | `android-emu.ps1 -Stop -Delete`, then `adb devices` must not list the serial | either |
| `guard` | the guard log's lowest free RAM and peak private bytes since the boot; `stop.log` lines naming this emulator | a `stop.log` line |

The run stops at the first failed step and names it; `stop` and `guard` still run when `boot` ran, so no emulator is
left behind. Each program's wait has a ceiling (`check` 2400 s, `boot` and `tour` 1500, a part 900, `stop` 300); at
it the program's process tree is killed by its PID, as `tools\sweep.ps1` does, and the step fails saying so.

**When `main` moved** the run stops at `base` before the expensive steps. Bring the branch up to date in the verify
worktree (`git rebase origin/main` and `git push --force-with-lease origin HEAD:task/<Task>`; the auto-mode classifier
refuses a force push in this session's agents, T246 saw it, so there `git merge origin/main` and
`git push origin HEAD:task/<Task>`), then run the same command again: the worktree is reused.

**The emulator for the scenario.** With `-KeepEmulator` the `stop` step is `kept` and its note gives the stop command;
run the report's scenario on that emulator, then `-Only stop,guard` with the same arguments: the summary keeps the
earlier steps (each marked `from`) and adds the stop and the guard's figures for the last day.

## The summary

`<Out>\verify-summary.txt` is for reading in one go:
```
verify T246 at 25b41241... (origin/task/T246), worktree ...\dystopo-wt\T246-verify
RESULT: FAILED at step tour
check      ok         333.3 s  4883 tests, 0 failures, 0 errors, 0 skipped in 839 files
slot       ok           2.3 s  0 of 2 slots taken by others
boot       ok         187.3 s  emulator-5570 booted, the branch's debug APK installed, server none
tour       failed     674.7 s  tour not ok (exit 1): 10/10 views ok; sheet ...	our	our-sheet.png; failed steps: cards
stop       ok           3.4 s  emulator-5570 stopped and dystopo35-T246 deleted; adb devices no longer lists it
guard      ok           0.1 s  lowest free 87.2 GB, peak private 5.1 GB, 0 stop.log lines
screenshots: 40 (look at each; the sheet first): ...	our	our-cards-sheet.png, ...	our	our-sheet.png
```
(T246's own run, a tools-only branch: the tour's cards step failed at font scale 2.0 in landscape on the app as it
was on `main`, which the summary names; the whole run took about 20 minutes on the second PC.)
`<Out>\verify-summary.json` has `task`, `branch`, `head`, `worktree`, `ok`, `failedStep`, `seconds`, `screenshots`
(every PNG of the tour and the parts), `commands` (each step's command lines) and `steps`, one object per step with
`name`, `status` (`ok`, `failed`, `not run`, `skipped`, `kept`), `ok`, `seconds`, `note` or `error`, and its figures:
`check` has `tests`, `failures`, `errors`, `skipped`, `testFiles`, `modules`; `base` `mainMovedBy`, `branchCommits`;
`privacy` the scanner's hit counts and `localFile`; `slot` `others`, `cap`, `waitedSeconds`; `boot` `buildSeconds`;
`tour` `viewsOk`, `viewsTotal`, `tourOk`, `glyphsOk`, `backgroundOk`, `directOk`, `cardsOk`, `failedViews`, `sheet`;
`ui-<part>` `partOk`; `guard` `lowestFreeGB`, `peakPrivateGB`, `stops`, `log`. Every program's output is in
`<Out>\logs\<step>.out.log` and `.err.log`.

The guard writes a line per new GB of private bytes, so the lowest free RAM is the lowest at those moments, not a
continuous minimum. Without `devtool.local.json` the scanner knows no private terms (`localFile` false, said in the
note): the agent reads the text itself (verifier step 7).

## Its test

`python -I tools/test_verify_task.py` runs the script with `-Fixture <dir>`, which runs no program: exit codes come
from `codes.json`, git and adb answers from `answers.json`, the worktree is `<dir>\wt` and the lock folder
`<dir>\lock`. It checks the summary's shape for a clean run, a failed boot (the stop and the guard still run), the
first failure kept under `-Continue`, `main` moved, a held and an odd console port, a `stop.log` line, a privacy hit,
`-Skip`, a dry run on the real checkout and a bad argument.
