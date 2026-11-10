---
date: 2026-11-10
kind: chore
---

# The sweep retries a part once when the failure is the machine's, and counts the flake

Both sweeps of 2026-11-10 went red on one machine flake and green on the rerun: `features` on "mkdir
/sdcard/Android: Permission denied" right after a guard reboot, `release` on "device offline" with the emulator
gone. Each cost a coordinator read, a chore launch and a `-Only` rerun whose summary overwrote the first. The
device tour already retries captures and forwards and counts them on its `steps.tsv` line (T185, T105's part note
in `tools/sweep.ps1`). The owner, 2026-11-10: "I guess?"

- A part whose failure matches a short list of device-class errors (device offline, not found, `adb` permission
  denied, the emulator's console gone) is run once more after the guard's reboot; a golden, test or assertion
  failure is never retried.
- `summary.json` gains `retried` (part, reason) and the sheet marks the part; `steps.tsv` keeps both runs.
- The flake rate per part, release over release, is then a number to watch.

## Open

- T273's storage wait after a reboot may remove the first cause; the retry still covers the second.
