---
date: 2026-11-10
kind: chore
related: [2026-11-10-the-sweep-retries-a-part-once-on-a-machine-failure.md]
---

# The sweep's device parts on a cloud runner, so the PC's emulator cap stops sizing the wave

The two-emulator cap, the guard's reboots (finding `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`),
one Kotlin compiler out-of-memory per `check` with two Gradle agents, and the tour screenshot flakes all come from
one machine. `nightly.yml` already runs the world tour, the demo tour and the live views on `ubuntu-latest` and
says the device parts "need an emulator and run only in tools/sweep.ps1"; `release.yml` builds every release there.
GitHub's Linux runners expose KVM, and an Android emulator under it runs the device tour at close to the PC's
speed. The owner, 2026-11-10: "Sure."

- A `device` job in `nightly.yml` (and on demand for a release): the SDK and a system image installed, the emulator
  booted headless with KVM, the debug build installed, the Rainier pack pushed, then `tools/android-check.py` with
  `tools/android-tour.txt`, the features part and the strings scan, the sheet uploaded as an artifact.
- `tools/sweep.ps1`'s device parts factored so the runner and the PC run the same Python steps; the PowerShell
  wrapper stays the PC's.
- Measured first: one run's minutes and whether the cap on the PC can rise to three implementers with the device
  parts elsewhere.

## Open

- The emulator action in common use is MIT, within the licence rule; the Rainier pack must be built in the job or
  cached, since `out/` is not in the repository.
- The owner's phone parts and the release-realism parts stay on the PC.

Plan: one pass with no Gradle on the PC, measured before anything is promised. A `device` job in
`.github/workflows/nightly.yml`, started by hand only (`workflow_dispatch`) until it has been green three times:
KVM enabled, the SDK and one API 35 system image cached, the emulator booted headless (the emulator action's
licence confirmed against the rule first, else the few `sdkmanager` and `emulator` lines written out), the debug
APK built, the Rainier pack built in the job and cached by the hash of its inputs, then `tools/android-check.py`
with `tools/android-tour.txt` and the strings scan, the sheet and `summary.json` uploaded as artifacts. The job
calls the same Python entry points the PC's sweep calls; `tools/sweep.ps1` changes only where a step must be
factored out to be shared. Done when: one green run and its minutes in the report beside the PC's for the same
parts, with the report's answer on whether the PC's cap can rise. Out of scope: the release-realism parts, the
owner's phone, and making the job nightly or a release gate (the next pass, on the measurement).
