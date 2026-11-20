---
date: 2026-11-17
kind: chore
---

# A ledger of the app state each sweep part leaves behind

T300 found that the a11y part's navigation screen read "Head NW 3.7 mi to rejoin" and "North up" because of a
course and an orientation the `device-tour` part two parts earlier had left, kept across restarts; the guess had
been the `features` part, which runs after it. Every such leak so far was found by a red line somewhere else. A debug
route (`GET /debug/state`: the settings that differ from their defaults, the orientation, the simulated heading and
course, the packs folder, the last fix, the recording state, open sheets) read before and after each device part by
`tools/sweep.ps1`, with the differences written to `summary.json` per part and listed on the sheet, would name what
each part changes; a part's own setup could then assert the state it needs. Anchors: `tools/sweep.ps1`'s part loop,
`androidApp/.../*Routes.kt` (a new route), T300's pin in `tools/android-a11y.py` as the first consumer.

## Open

- Which state is worth a line, and how a part declares the state it leaves on purpose.
- Recorded from conversation, not triaged or checked against the code.

Plan: `GET /debug/state` in a new `androidApp/.../debug/StateRoutes.kt` (KDoc lists it): the synced and plain
settings that differ from their defaults (from the settings registry, keys and values, no places), the map's
orientation, the simulated heading and course, the packs folder's file names, whether a fix is held and its age (no
coordinates), the recording state, and the open sheets and views. `tools/sweep.ps1` reads it before and after each
device part through the existing debug helper and writes the differences to that part's entry in `summary.json`
(`leftBehind`); `tools/sweep-summary.py` lists them on the summary sheet. Out of scope: parts asserting their own
start state (a follow-up once the ledger shows which matter), the before-and-after screenshot sheet. Verify: a unit
test of the difference function in the summary script, and a two-part sweep run (`-Only device-tour,a11y`) whose
`leftBehind` names T300's course and orientation.
