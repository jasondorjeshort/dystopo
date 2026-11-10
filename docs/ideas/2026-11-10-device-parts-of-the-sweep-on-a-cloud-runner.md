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
