---
date: 2026-11-24
kind: chore
---

# The closing sweep finds its own -Prev

v0.1.43-alpha shipped without a before-and-after sheet of the screens that changed. The closing sweep
ran without `-Prev`, so `summary.json` had no `changes` key, and the release notes said so.

The idea: when `-Prev` is not given, `tools/sweep.ps1` picks the sweep folder of the last published
release and says which one it chose. Each release's log entry already names its sweep folder. A
sweep with no earlier folder available runs as it does now and says why.

Recorded from the post-wave brainstorm of wave 47 (the owner, 2026-11-24).
