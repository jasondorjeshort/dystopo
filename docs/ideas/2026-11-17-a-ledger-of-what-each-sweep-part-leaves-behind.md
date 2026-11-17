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
