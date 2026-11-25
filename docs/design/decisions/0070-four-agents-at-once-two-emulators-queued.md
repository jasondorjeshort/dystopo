# 0070. Four agents at once on the 128 GB PC, the two emulators queued

Date: 2026-11-25
Status: accepted

Record: the owner's answer of 2026-11-25 in the phone session (multiple choice: "4 agents, 2 emulators"), after the
question why wave 48 took so long. Supersedes ADR 0057 in part (its default width of one, as widened to two for this
PC on 2026-11-09); the rest of ADR 0057 stands.

## Context

ADR 0057 set one Gradle-using agent at a time after wave 28 on the 48 GB PC, where seven builds at 3 GB to 4 GB and a
Kotlin daemon left 2.1 GB free. The owner widened it to two for the 128 GB PC on 2026-11-09 (wave 31's paragraph;
`docs/reference/machines.md`). The emulator cap of two (`emulators.max` in `.claude/hooks/policy.json`, the owner,
2026-10-31, T190) is a separate rule, and with two slots the two numbers coincided.

Wave 48 (launched 2026-11-24 14:08 UTC, twenty tasks) had merged seventeen tasks about 32 hours later: two lanes of
about ten tasks each, 1.6 h to 7.7 h from a task's first commit to its merge (git, the merges of wave 48), the long
ones those with fix rounds. The owner, 2026-11-25: "This is based on the emulator running and eating a lot of ram
use, but it's really the emulator testing step that is the bottleneck." Reading the PC the same evening: 128 GB,
16 cores, 81.3 GB free with one emulator (2.1 GB) and five Java processes (6.7 GB) running. Reading, writing code and
unit tests use no emulator; RAM is not the binding limit on this PC, the emulators are.

## Decision

- **On the 128 GB PC, up to four Gradle-using agents at once** (implementers and verifiers counted together), from
  wave 49. The 48 GB PC keeps ADR 0057's default of one unless the owner says otherwise.
- **The emulators stay at two** (`emulators.max` 2, the RAM admission and the guard of T231 unchanged). An agent that
  needs an emulator while both are held waits in the foreground for one, as `tools/android-emu.ps1`'s refusal already
  says; it does not stop another agent's emulator and does not raise the cap.
- **A wait instead of a refusal:** wave 49's first task gives `tools/android-emu.ps1` a wait for a free slot
  (`-WaitForSlot <minutes>`, checked under the script's mutex, the oldest waiter first, a refusal naming the holders
  when the time runs out), and the briefs pass it. Until it merges, waves run at the width of two.
- **Timed runs go alone:** a run whose figures are compared against budgets (`tools/android-startup.py`, the closing
  sweep, a battery or frame-time measurement) runs with no other Gradle build or emulator on the PC, since sixteen
  cores shared with four builds would slow it and fail its checks on a false reading. The coordinator holds new launches
  for it.
- **The coordinator still reads free RAM after each merge** (ADR 0057); below 16 GB free it launches nothing new
  until it recovers and says so in the log.

Out of scope: the 7-day and 5-hour meters (ADR 0035; four at once spends them faster on the clock, not per task); the
emulator count, which the owner raises, nobody else.

## Consequences

A wave's coding and unit tests overlap four ways; its emulator work stays two at a time, so tasks heavy on the device
gain least. Waits for an emulator are idle agents rather than refusals and retries. The 5-hour meter reaches its 50 %
line sooner, so launches pause more often for its reset. Four branches merging into `main` mean more rebases;
Owns lines keep files disjoint as before.

ADR 0057 now holds for the 48 GB PC and for its other rules (order on the board, the agents without Gradle running
alongside, the RAM read after each merge).

- Prediction, check at wave 49's close: free RAM stayed above 16 GB in every reading after a merge, and no emulator
  was stopped by the guard for low RAM.
- Prediction, check at wave 49's close: a task's time from launch to merge is no longer than wave 48's on average,
  and the wave's tasks per hour of the clock rise by at least half.
