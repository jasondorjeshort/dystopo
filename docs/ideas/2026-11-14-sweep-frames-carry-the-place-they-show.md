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

2026-11-14 (the owner left this to the coordinator, "or get an independent review to confirm"; the critic changed
two things): yes, but the cause is found before it is fixed, and the sidecar names an area, never a centre. The
cause of the four frames is not known (the log's entry on v0.1.29-alpha): a missing fix is one suspect, a fresh
debug install's default server on 8765 the other (the board's Later line). And `out/` is not wholly private:
`.github/workflows/nightly.yml` uploads `out/sweep-nightly/` whole as a workflow artifact, which the paragraph
above does not say.

Plan: one pass, a tidy-up and not first (the owner on the published sheets, 2026-11-14: "Don't care"). The cause
was found and guarded by T287 (the emulator's own default fix recentred the map; `tools/android-emu.ps1` now sets a
public fix at every boot, `docs/reference/android-emulator.md`), so what is left is the check. One implementer: each screenshot
of the sweep gets a sidecar line under `out/` holding the name of the `areas.json` area that contains the viewed
centre, or `outside`, decided at capture (`GET /camera` for a debug build, in `tools/android-check.py` and the
other capturing scripts; the fix the part set, for the release build's tap-driven parts); no coordinate is
written anywhere. `tools/sweep-diff.py` marks an `outside` frame and refuses to write `changes.png` with one in
it. Verify: the script's tests with a planted `outside` line; a sweep whose sheet has none. Out of scope:
anything written into a PNG; pictures that users share (the related idea).
