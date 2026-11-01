---
date: 2026-10-31
status: resolved (T191)
area: tooling / sweep, android-ui, Windows host memory
---

# The post-wave sweep hung for 9.5 hours when the paging file ran out

## Question

Wave 23's post-wave sweep passed its first 20 parts, then the `background` part (`android-ui.py background`) made no
progress from about 03:38 until the coordinator noticed at 13:17, about 9.5 hours, with `android-ui.py` alive at 1 s of
CPU. Why did a failed step leave the part waiting instead of failing, does `sweep.ps1` have any per-part timeout, and what
did T185's capture retries cover?

## What was tried

- Read the hung run's `background.err.log`, `steps.tsv` and the part's code: `part_background`, `Run.step` and
  `alive_and_clean` in the UI script, `Device.capture` in the first-run script, `adb` in the debug helper, and `Part` and
  `Run` in the sweep script.
- Compared the host facts the coordinator measured at 13:16 with the log: 14.8 of 47.9 GB RAM free, but commit charge
  59.2 of 71.0 GB, the paging file's ceiling. The system drive had under 1 GB free that morning until 27 leftover AVDs
  (about 127 GB) were deleted, so the paging file could not grow.
- Reruns: `-Only background,network,update,apk-strings` (folder suffix b), then a third run (suffix c).

## Result

- **Trigger: commit exhaustion, not RAM.** The log has three lines after `install-map: ok`: `record: FAILED OSError
  [WinError 1455] The paging file is too small`, `alive: FAILED` with the same error, and an "Exception ignored in
  thread" naming a `_readerthread` with a bare `MemoryError`. WinError 1455 is raised by process or pipe creation, so
  `Run.step` caught it as designed (it records `ok: false` and goes on) and the `break` in `part_background` ended the
  steps. The `alive` step is the last one; nothing after it should wait. The step's error path also tries a screencap
  (`shot=True`), and that failed the same way and was swallowed.
- **Where it then waited is not established.** No log line says. The process was idle (1 s of CPU), so it was blocked in
  a wait, not looping. The code has no bounded wait on every path:
  - `subprocess.run(..., timeout=N)` is bounded on its first wait only. On a timeout, Windows `run()` kills the child and
    calls `communicate()` again with no timeout; a reader thread that died from `MemoryError` leaves the pipe undrained,
    or an `adb` daemon holding an inherited pipe (finding 2026-10-13) keeps it open, and that second call, or the
    `wait()` in `Popen.__exit__`, has no limit.
  - A child `adb` blocked on a full pipe nobody reads never exits, and nothing kills it.
  - The retry loop of T185 is bounded (five attempts, 0.5 to 4 s apart, 60 s each) and does not wait forever; it counts
    only an empty or cut capture and a timeout, and never saw this: an `OSError` from process creation escapes it.
  So the cause of the hang is one of the unbounded waits above, probably the second `communicate()`; this is a
  hypothesis, not a reproduction (the process was not inspected before it was ended).
- **No per-part timeout exists.** `Part` runs its body and `Run` calls `WaitForExit()` with no argument, so one stuck
  child stops the whole sweep for good. The slowest part of this sweep took 552 s (`device-tour`); the others took
  under 330 s.
- **T185's retries did not apply.** They cover a bad screencap and the adb forward and cut-read cases, none of which is a
  process that cannot be created or a wait that never returns.
- **Reruns.** Run b failed `device-boot` after 35 s with "ports 5640/5641 are in use": the hung run's emulator had been
  killed moments before and its console ports had not been released yet. Run c, after the AVD cleanup, passed
  `background` (171 s), `network` (210 s) and `update` (462 s), so the hang does not recur with disk room.
- The T185 verifier's "unexpected end of stream" from the mock releases API (seen twice) did not reproduce in run c.

## Next

1. A per-part timeout in the sweep script: one default ceiling (for example 900 s) with overrides for the slow parts
   (`device-tour`, `update`, `check`), the wait done with `WaitForExit(ms)`; on expiry kill the part's process tree by
   PID (`taskkill /PID <pid> /T /F`, never by name), and write a failing `steps.tsv` line saying which part timed out.
2. A timeout on every subprocess wait in the UI script's device path: wrap `subprocess.run` so that after a timeout the
   kill is followed by a bounded `communicate(timeout=...)`, and the `alive` step reports a failure when the host is out
   of memory instead of waiting.
3. Before `device-boot`, wait for the previous emulator's console ports (the pair it listed) to be free, bounded at about
   a minute, and fail with the owner of the port if it stays held.
4. Prevention, the owner's: set the paging file to system-managed or to 32 GB or more on a drive with room, and keep
   the system drive's free space up. The AVD cleanup (delete leftover AVDs when a wave closes) is now in the wave skill.
