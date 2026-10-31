---
date: 2026-10-31
kind: chore
---

# Emulators: a lock with a cap, and close on done

Owner priority for the next wave (2026-10-31): the emulators cost about 10 GB of host memory each and add up; this
wave ran two implementer emulators and one verifier's by convention, which matches the compiler running out of
memory and the guest watchdog kills the verifiers saw, and one verifier was cut off before its cleanup step and left
its emulator to the next chore. Two mechanisms, both wanted, with the cap at the owner's choice (the coordinator's
recommendation: 2, one implementer and one verifier; the owner said "your rec").

1. **Close on done.** Every boot goes through `tools/android-emu.ps1`, which writes a lock file per agent under
   `out/emulators/` (PID, serial, AVD, owner, time). A `SubagentStop` hook in `.claude/hooks/` stops the emulator
   recorded in the ending agent's lock by its PID (its own process, by PID: inside the kill rule of `CLAUDE.md`)
   and removes the lock. The coordinator's chore reclaims a lock whose PID is dead. [no-check: the lock folder is
   the task's to create]
2. **The lock.** The script refuses to boot when the live locks reach `emulators.max` in `.claude/hooks/policy.json`,
   with a message naming the holders, after reclaiming dead locks. A `PreToolUse` hook denies a raw `emulator -avd`
   (or `emulator @name`) command line that bypasses the script, pointing at it. The sweep's emulator holds a lock
   like any other.

Also: drop the guest RAM from `-Memory 3072` to 2048 and measure the host cost per emulator (the working set of the
`emulator.exe` and `qemu-system-x86_64-headless.exe` pair) at 2048 and 3072, since 10 GB for a 3 GB guest says the
overhead is elsewhere (the graphics process, snapshots); the report states the figures and the cap they support.

Plan: one task, first in the next wave, Owns `tools/android-emu.ps1`, new `.claude/hooks/emulator-lock.js` and
`emulator-stop.js` with their cases in `.claude/hooks/test-hooks.js`, the `emulators` block of
`.claude/hooks/policy.json` (`max` 2) and its line in `.claude/hooks/policy.js` if the schema is checked, the
`SubagentStop` and `PreToolUse` lines of `.claude/settings.json`, the emulator lines of
`docs/reference/android-emulator.md` and `machines.md`, and the wave skill's "Emulator slots" sentence (the number
moves to the policy). Done when: two scripted boots take two locks and a third is refused with the holders named;
a lock whose PID is gone is reclaimed; a subagent that booted an emulator and ends (a test harness ending the hook's
process) leaves no emulator and no lock; a raw `emulator -avd` line is denied by the hook test; the host memory per
emulator at 2048 and 3072 is in the report; `node .claude/hooks/test-hooks.js` green. A base-owned hook file changed
here is a row in ADR 0037's departure table and a carry-back. [no-check: names the hook files the task creates]
