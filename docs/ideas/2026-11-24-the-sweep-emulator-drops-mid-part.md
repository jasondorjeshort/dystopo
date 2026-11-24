---
date: 2026-11-24
kind: bug
---

# The sweep's emulator drops mid-part

Wave 47 hit `device 'emulator-<n>' not found` during a screencap again and again:
- once in T353's split sweep, where the sweep rebooted the emulator and retried `features`;
- in the counted rerun `out/sweep-20261124-w47-rerun2` (`features`, two capture retries);
- in the release draft check (the settings step).
The first closing sweep's `update` part also failed on an adb drop.

Each drop cost a reboot, a retry or a red part. The cause is not known. Possibilities include an adb
server restart, the guard's reboot timing, or host load with two emulators up.

The idea: find what drops the device. The evidence is in the parts' `.err.log` lines, the guard's
`emu-reboots.log` and adb's own log. Then fix it, or record it in a finding.

Recorded from the post-wave brainstorm of wave 47 (the owner, 2026-11-24).

Plan: one read-only investigation, Sonnet-class, no emulator and no Gradle (wave 48 holds both). Read the wave 47
sweep folders (`out/sweep-20261124-*`), each part's `.err.log` and the guard's `emu-reboots.log` from
`tools/android-emu-guard.ps1`, adb's server log, and the release draft check's log; line up each
`device 'emulator-<n>' not found` against the guard's reboots, adb server restarts (`adb kill-server` or a second
adb version on PATH), the second emulator's start and stop, and free memory. Write a finding (`finding` skill) with
the timeline and the best-supported cause; if a fix is clear and small (the guard's timing, one adb, a retry rule
in `sweep.ps1`'s `DEVICE_LINES`), the finding's Next is that fix as a Plan for a later pass. Out of scope: changing
any script in this pass.
