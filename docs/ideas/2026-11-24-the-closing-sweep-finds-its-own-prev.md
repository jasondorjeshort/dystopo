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

Plan: one implementer (no Gradle; PowerShell and Python only). In `tools/sweep.ps1`, when `-Prev` is not given
and `-SummaryOnly` is not set: read the latest published release (`gh release view --json tagName`, or the newest
`vX.Y.Z-alpha` tag when `gh` fails), find its close entry in `docs/PLANNING.md` (the dated log names the sweep
folder as `out/sweep-...`), and take the folder that holds the full `ui/` set: a `-rerun` folder holds only its
parts, so prefer the first-run folder unless the rerun's screenshots were copied in (check how wave 47's `rerun2`
was made and say which rule holds). Print the chosen folder and the reason; with no folder on disk, run as today and
say why in `summary.json` (a `prevChosen` field: path or reason). `-Prev ''` explicitly turns it off. Update the
script's header and the `sweep` skill's line on `-Prev`. Verify with `-SummaryOnly -Out out/sweep-20261124-w47-rerun2`
style dry runs (no emulator: the wave holds them) that the choice is printed and right. Out of scope: `-PrevRelease`.
