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
