---
date: 2026-10-30
kind: bug
---

# The device scripts' screenshot capture returns an empty file now and then

On the second PC the sweep's `recenter`, `update` and `upgrade` parts failed on 2026-10-30 with
`ValueError: not a PNG` at their first screenshot after a fresh install (a 0-byte
`01-install-previous-failed.png`), while the app itself was fine (`alive: ok`); the same parts
passed on a rerun, except `upgrade`, which hit it twice in the rerun sweep (`out/sweep-wave22-b`,
`out/sweep-wave22-c`) and had passed in the full sweep. T173's implementer saw a truncated
screenshot transfer (`IncompleteRead`) during a device tour while a `check` build ran beside it.
Anchor: `tools/android-ui.py` (`still()`, `dev.frame()`) and the capture helper the device tour
shares. Reproduce: a fresh API 35 AVD at 3 GB, install an APK, capture at once. Likely the capture
is read before the emulator has finished writing it, or the adb transfer is cut under load; the
fix is a retry inside the capture helper when the file is empty or not a PNG, and the part should
not fail on one empty frame.
