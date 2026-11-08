---
date: 2026-11-08
kind: chore
---

# A before-and-after sheet of every screen the wave changed, for the owner's eye

The owner, 2026-11-08, on the idea: "Yes please."

ADR 0031 says a change a person sees is built in alpha and shown to the owner in the task report; today that is prose
in merge bodies and screenshots scattered under each agent's `out/`. The sweep already writes hundreds of screenshots
per release (`tools/sweep.ps1`: the device tour's views and card steps, the `ui/` sets of the release-realism parts,
first-run's captures) under one output folder, and `sheet.png` lays them all on one page. What is missing is the
comparison: the same shots from the previous release, and the pairs that differ.

## Open

- A sweep step (or a devtool command over two sweep folders) that pairs each screenshot with the previous release's by
  name, pixel-diffs them with a tolerance for timing (the camera in flight, a clock), and writes `changes.png`: the
  changed pairs side by side with a heat overlay, nothing else; `summary.json` gets the count and the list.
- The release notes' "What changed" gets the sheet as an asset (or the repository's images folder), so a tester sees
  the wave's visible changes in one picture.
- The pairs the owner should look at first: those from tasks flagged "for the owner" in their merge bodies.
