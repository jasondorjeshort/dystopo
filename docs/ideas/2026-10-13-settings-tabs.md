---
date: 2026-10-13
kind: feature
status: done
pipeline: T103
---

# Group the Android settings into tabs by theme

The settings window should have several tabs by theme instead of one long sheet. Candidate
groups, from what is there now: Map (layers, hillshade, slope, contours), Units and display (units,
text scale, night), Navigation and tracking, Data and storage (server, caches, offline areas),
Sync and sharing, About / advanced / debug.

Today the Map options live in a section of the settings sheet (`SettingsSheet.kt`,
`options/MapOptionsSection.kt`), with Storage reached from Settings.

## Open

- The grouping, and where each current row goes; free to iterate in alpha (what humans
  see and touch).
- Tabs inside a sheet versus a settings screen with a list of groups, on small phones and with
  large text scale.
- Device tours that tap settings rows (`tools/android-ui.py`) will need updating.

## Resolved

T103: a list of groups rather than tabs (six tab labels do not fit across a phone at font scale 2.0;
list rows wrap at any text size), each group opening a page; on a window 720 dp wide or more the list
and the page sit side by side. Groups: Data and storage (Sources first, so the server field is two taps
from the map; the first-run card's Settings opens this page directly), Map options, Units and display,
Navigation and tracking, Sync and sharing, About and advanced. Night stays in Map options (it is part
of `MapOptionsSection`). Debug: `GET /settings/layout`, `POST /settings/open?group=`.
