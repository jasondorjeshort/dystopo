---
date: 2026-11-08
kind: chore
related: [2026-11-07-report-a-problem-with-a-full-diagnostic-dump.md, 2026-11-07-a-launch-budget-per-mark-enforced-by-the-sweep.md]
---

# The owner's phone on USB runs the sweep's device parts beside the emulator

The owner, 2026-11-08: "I could do this on my phone easily enough; let's do it."

The emulator and the phone disagree where it matters: the tracker race (T229) never reproduced on the emulator and
the phone froze on it; the tile cache walk (T242) took 120 s on the phone and seconds on the emulator; the
emulator's cold start is over the 5 s limit on its own. The release skill already runs two phone steps when
`adb devices` lists a serial that is not an emulator (first-run to the test step, and the features part), and
`tools/android-ui.py` and `tools/android-check.py` take a `--serial`.

## Open

- A `-Phone <serial>` option for `tools/sweep.ps1`: after the emulator parts, the device tour, the startup launches
  (`tools/android-startup.py`, five cold launches, the per-mark table against the launch budget idea), the features
  and permissions parts and `GET /diagnostics` on the phone, with their own sheet section; the phone's figures kept
  release over release.
- What a phone cannot do that the emulator can: `adb emu geo fix` (the tour's fixes) needs a mock-location path on a
  phone (Android's mock location provider under developer options, selected once by the owner; the app's debug
  build already takes `POST /location` on its debug port, which the tour could use instead); the release build has
  no debug port, so the release-realism parts run by taps only, as they do today.
- Safety: the phone's data is the owner's: the sweep installs the debug build under its own package or asks before
  touching the release install, and never clears data; the privacy rule holds (nothing read off the phone goes in a
  tracked file; the second PC has no private-terms file).
- The owner's part: a USB cable, developer options on, the mock location app chosen once.
