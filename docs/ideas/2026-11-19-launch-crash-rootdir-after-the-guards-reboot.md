---
date: 2026-11-19
kind: bug
---

# A launch crash "rootDir must be verified to be directory beforehand" after the guard's reboot

The sweep `out/sweep-20261119-1231` [no-check: gitignored] (main at cb2c6752) went red on `a11y`: the debug
build crashed at launch with `java.lang.AssertionError: rootDir must be verified to be directory beforehand`,
61 s into the part, right after the emulator guard rebooted the emulator (private bytes 6.0 GB, over the 6 GB
line; the boot took 539 s). No frames. The sweep's own retry did not fire: the line is not in `DEVICE_LINES`
(`tools/sweep-summary.py`). The `-Only a11y` rerun was green. The message is not in the project's code, so it
comes from a library walking a directory at start-up (a cache or store folder that is not there yet on a guest
whose storage is still settling after the reboot is the first guess, unproven).

To find out: the crash's stack in the part's logcat (wave 37's follow-up "save logcat on a launch crash" is what
would have kept it); which start-up step walks a directory; whether it reproduces by launching right after
`android-emu.ps1`'s storage wait on a rebooted emulator. A real phone after a reboot could hit the same.

## Open

- Whether the sweep should treat a launch crash right after a guard reboot as a device-class line (retry once)
  while the cause is unknown.

2026-11-20: it happened again in the next sweep, `out/sweep-20261119-1925` [no-check: gitignored] (main at
aeb4b028): the guard rebooted the emulator before `a11y` (private 6.1 GB over 6 GB; booted again in 540 s, the guest's
storage ready 27.9 s after `sys.boot_completed`), and the debug build crashed at launch with the same
`AssertionError` (PID 3422). Two sweeps out of two after that reboot: reproducible, so worth a pass before the
emulator's memory line moves it to another part.

2026-11-21: a third time, in wave 44's closing sweep `out/sweep-20261121-w44` [no-check: gitignored] (main at
c464b55c): the guard's reboot before `a11y` (private 6.0 GB; 518 s; storage ready 6.3 s after `sys.boot_completed`),
the same `AssertionError` 61 s into the part (PID 3278), no retry, and a green `-Only a11y` rerun. Still no stack
kept.

Plan: the message is Kotlin's own: the stdlib's `FileTreeWalk` asserts it when a directory it is about to enter is no
longer a directory, so some `File.walk*()` in the app ran while another thread deleted or replaced that directory (or
the guest's storage answered `isDirectory` inconsistently just after boot). Start-up walkers to check:
`options/ShadeService.kt` (the legacy `shade` folder deleted bottom-up on its own thread), `ContourCache.kt` (garbage
collection, walk and delete), `storage/Storage.kt` (`walk`, and the bottom-up delete), `cachebudget/BudgetEngine.kt`
and `online/DirectSources.kt`. Reproduce: an emulator rebooted by `tools/android-emu.ps1` and the app launched at the
storage wait's end, ten times, with logcat saved for the stack. Fix the walker the stack names (a walk over a tree
another thread deletes catches the error or both use one lock; prefer `listFiles` loops that tolerate a vanished
entry), and add `rootDir must be verified` to `DEVICE_LINES` in `tools/sweep-summary.py` only if the stack shows the
guest's storage, not the app, at fault. Verify: the ten launches clean and one full `a11y` part after a guard reboot.
