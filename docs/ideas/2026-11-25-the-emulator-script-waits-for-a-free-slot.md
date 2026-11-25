---
date: 2026-11-25
kind: chore
---

# The emulator script waits for a free slot instead of refusing

ADR 0070 (the owner, 2026-11-25) runs four Gradle-using agents at once on the 128 GB PC with the emulators kept at two.
Today `tools/android-emu.ps1` refuses a boot past `emulators.max` ("emulator cap reached ... Wait in the foreground until
one is stopped"), so each agent would poll by hand.

Plan: wave 49's first task, run at the width of two (ADR 0070). `tools/android-emu.ps1 -WaitForSlot <minutes>`: while
the cap is reached, wait (the check under the script's existing mutex, the oldest waiter first by a ticket file in the
lock folder, the waiting announced once with the holders), then boot; at the time limit, the same refusal as today. The
sweep and `tools/verify-task.ps1` pass it; the `brief` skill's implementer and verifier templates name it. Tested with
two emulators held and a third request (it boots when one stops; it refuses at the limit), and by
`node .claude/hooks/test-hooks.js` where the hooks are touched. `docs/reference/android-emulator.md` (The emulator cap)
updated.
