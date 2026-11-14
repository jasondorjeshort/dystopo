---
date: 2026-11-10
kind: feature
related: [2026-11-07-tracks-and-routes-manager-and-the-missing-track.md]
---

# A timeline of recordings on the tracks page

The owner's "lost" track of 2026-11-07 was displaced, not gone (T262's cause: one transient shown id). A list
sorted newest first, which T262 builds, finds it; a calendar view, a month grid with a mark per day recorded and
its length, makes a missing day visible at a glance and is the index a year of hikes wants. The owner, 2026-11-10:
"Seems like it's part of the tracks page which we don't have yet." So this waits for T262's "Tracks and routes"
screen (new `tracks/TracksScreen.kt` [no-check: new file]) and then adds a view to it, not a screen of its own.

- A "Timeline" chip beside Tracks, Routes and All: months as grids, a day with a recording marked with the activity's
  glyph and the day's total length; tapping a day filters the list to it.
- The data is `GET /tracks` as the screen reads it; the grouping by day is a pure function in `shared` with a test.
- Agent-testable: `GET /tracks/ui?filter=timeline` reports the months and marked days; a tour step screenshots it.

## Open

- After T262 has merged; its screen's filter chips are where this goes.

Plan: one pass on T262's screen, merged since (`androidApp/.../app/tracks/TracksScreen.kt`). A "Timeline" chip
beside Tracks, Routes and All: months as grids, newest first, a recorded day marked with its activity's glyph and
the day's total length, a tap filtering the list to that day and a second tap clearing it; the grouping by local
day a pure function in `shared` with a test (a recording across midnight counts on the day it started; an empty
month is drawn only between two months that have recordings). The screen's debug route reports the months and the
marked days under `filter=timeline`; a tour step screenshots it at font scales 1.0 and 2.0. Done when: `check`
green with the grouping test; the screenshots viewed; strings listed (ADR 0031). Out of scope: figures per month,
routes on the grid.
