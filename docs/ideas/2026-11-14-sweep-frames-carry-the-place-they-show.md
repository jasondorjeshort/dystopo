---
date: 2026-11-14
kind: chore
related: [2026-11-14-a-shared-map-picture-carries-its-place.md]
---

# Sweep screenshots carry the place they show, so the published sheet can be checked by a script

Before v0.1.29-alpha the coordinator and a chore opened the changed frames of the before-and-after sheet one by
one, and four showed a map outside `areas.json` (an emulator that has had no fix set; the board's Later line and
the log's entry of 2026-11-14). `tools/sweep-diff.py` cannot tell: a frame is pixels. If each screenshot of the
sweep had a line beside it with the camera's centre and zoom (a sidecar JSON, from the debug routes for a debug
build; for the release build's tap-driven parts the fix the part set), the diff could refuse or mark a frame
outside every public area before `changes.png` is written.

The owner, 2026-11-14: "There are privacy issues here." They are met by what is recorded and where: the viewed
place, never a position (an emulator has only the fix the sweep gives it); in a sidecar under `out/`, which git
ignores and no release attaches; nothing written into the PNG, so the published sheet carries no more than it does
today. The sidecar exists to keep a private place off the sheet and is itself the one file that would name such a
place, so the check prints "outside the public areas" and never the coordinates.

Recommendation (the coordinator): the cheap half first, a public fix for every sweep emulator at boot
(`tools/android-emu.ps1`), which removes the known cause; then the sidecar and the check. The owner's wider
question, pictures that users share, is the related idea.

Question: with the sidecar kept under `out/` only, naming the viewed place and never a position, and the check
printing "outside the public areas" without coordinates, do you want the script check on the sheet? Recommended:
yes, in two steps: a public fix for every sweep emulator at boot first (`tools/android-emu.ps1`; it records no
place at all), then the sidecar and the check in `tools/sweep-diff.py`. It is yours to decide because your words on
it were about privacy, and the sidecar writes down where a map was looking.
Answer:
