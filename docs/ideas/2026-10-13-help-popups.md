---
date: 2026-10-13
kind: feature
status: done
pipeline: T109
related: [2026-10-13-cannot-create-waypoints-android.md, 2026-10-13-settings-tabs.md]
---

# Help text pop-ups behind a question-mark icon

Small help pop-ups around the app, opened from the familiar question-mark icon next to a control or
setting, explaining what it does and how to use it. A feature that is hard to find (dropping a
waypoint, what the slope colours mean, what a pinned area is) gets one line of text where the user
is looking.

## Open

- Where they go first: Map options rows (slope, contour interval), the Storage and Offline areas
  sheets, the track bar and navigation bar. One text per icon, kept next to the control in code so
  it is not lost.
- Wording is user-visible text (no other product names in user-visible text). Units in help text follow the units
  preference.
- Testable: a debug route that lists the help keys and opens one, and a test that every key has
  text.

## Done (T109)

Built as `docs/reference/android-app.md` › Help pop-ups describes: the registry, `HelpIcon(key)`, the
pop-up, `GET /help` and `POST /help/open`, and icons on Map options, Offline areas, the Settings rows
that need one, the track bar and the long-press card. Left for later placements: the Storage screen
(its rows changed in T112 the same wave) and the navigation bar (`nav/**`, T108's that wave).
