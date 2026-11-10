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
