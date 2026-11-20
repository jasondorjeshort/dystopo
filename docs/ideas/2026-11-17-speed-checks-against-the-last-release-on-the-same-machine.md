---
date: 2026-11-17
kind: chore
related: [2026-11-14-the-sweep-reruns-only-parts-whose-inputs-changed.md]
---

# Speed checks run against the last release on the same machine, not against fixed ceilings

Wave 35's closing sweep failed `startup` on its fixed ceiling (`tools/launch-budget.json`: map drawn 7,158 ms against
6,680) while the PC was loaded by other programs; every stage had risen 10 to 30 % together. What settled it was an
A/B run by hand: v0.1.31-alpha and main alternating on the same emulator, one at a time (old 5,814 to 5,996 ms, main
6,270 to 6,477), which showed the machine's state and, under it, a few percent that may be the app's. Make that the
part itself: `startup` (and later render times, memory and route planning) installs the last published release's APK
beside the new build, alternates their cold launches, and fails on the ratio past a margin, keeping the absolute
ceilings as a backstop. Anchors: the `startup` part of `tools/sweep.ps1`, `tools/android-startup.py`, the `upgrade`
part's download of the previous release by `gh`.

## Open

- Two installs of one package cannot sit side by side: alternate by reinstalling, or give the old build a test
  package name.
- The margin: from the run-to-run spread of each figure, which the alternation itself measures.
- Recorded from conversation, not triaged or checked against the code.

Plan: the `startup` part of `tools/sweep.ps1` and `tools/android-startup.py` get an A/B mode. The last published
release's APK is fetched as the `upgrade` part already does (`gh release download`); the part then alternates, five
rounds: uninstall, install the old APK, push the pack, N cold launches; uninstall, install the new build, push the
pack, N cold launches (uninstall each time, so no downgrade flag and no package rename). It reports both medians, the
ratio per stage, and the run-to-run spread within each build, and fails when the new median is over the old by more
than max(5 %, twice the larger spread); `tools/launch-budget.json`'s absolute ceilings stay as a backstop. Verify: one
`-Only startup` run, its JSON and timings in the report (expect the part to take about twice as long). Out of scope:
render times, memory and route planning (the same mode later), the margin's tuning beyond the first run.
