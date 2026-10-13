---
date: 2026-10-13
kind: feature
status: new
pipeline: none
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
