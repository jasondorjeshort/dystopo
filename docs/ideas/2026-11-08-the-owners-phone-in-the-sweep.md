---
date: 2026-11-08
kind: chore
related: [2026-11-07-a-launch-budget-per-mark-enforced-by-the-sweep.md]
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

Plan: one pass, `tools/sweep.ps1` and `tools/sweep-summary.py` only (plus `docs/reference/` sweep page's paragraph):
a `-Phone <serial>` option that, after the emulator parts, runs on that serial `tools/android-check.py` with the
tour's fixes sent as `POST /location` on the debug port (never `adb emu geo fix`), `tools/android-startup.py
--launches 5` (cold), and `GET /diagnostics`, each a part of its own in `steps.tsv` with a `phone` section in
`summary.json` and the sheet. Safety as code: the debug build and the release build share one package
(`androidApp/build.gradle.kts`, no suffix), so the part refuses to install over an installed release and names the
step for the owner, never clears data, and writes nothing read off the phone outside `out/`. Done when:
a run with a serial that is not attached stops at the named step; one run on the owner's phone at a sweep the
owner attends, its figures in the report. Out of scope: the launch budget's ceilings (that idea) and the release
parts on the phone (the `release` skill already has them).
