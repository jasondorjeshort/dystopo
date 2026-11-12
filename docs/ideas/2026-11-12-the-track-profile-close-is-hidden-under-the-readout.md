---
date: 2026-11-12
kind: bug
related: [2026-11-07-recording-card-close-button-reads-as-stop.md, 2026-11-08-a-touch-target-and-accessibility-sweep.md]
---

# The track profile's Close is hidden under the readout strip

The owner, 2026-11-12 (v0.1.27-alpha, a real phone): "Once I show a track, seeing the vertical profile, there's no
way to hide that v profile?" After being told that a Close action exists, they replied: "it needs to be easier to find".

The profile panel (`androidApp/.../app/tracking/TrackControls.kt`, `ProfilePanel()`, about line 538) has one header
row with the track's name, then **Fit** and **Close** right after the name, not at the panel's right edge. The
owner's screenshot shows the readout strip (`readout/ReadoutStrip.kt`: elevation, climb, speed, course, pressure)
drawn over the top of the panel. The strip hides the whole header row, so the name, Fit and Close cannot be seen or
tapped. The scale bar (`controls/ScaleBar.kt`) sits on the statistics line, and the lock, north and recentre
buttons cover the panel's right side. The strip, the scale bar and the recentre button all read
`BottomPanels.trackPanelsTop` as their floor, so something in that chain fails here. Possible causes: the panel is
too tall for the strip to fit above it, or the floor is measured before the panel has grown. The track in the
screenshot was long, with many segments, so the statistics line wrapped to two lines.

Steps: readout bar on (`AppSettings.readoutBar`), portrait, a track with gaps and elevation open in the profile (the
Tracks and routes screen, tap the row; or `POST /tracks/{id}/profile`), the map anywhere.

Plan: one pass. (1) The overlap: reproduce it on the emulator with a long multi-segment track on Rainier
(synthetic or from a public GPX), read `GET /layout`, `GET /scalebar` and `GET /route`'s layout block, find why each one sits over the panel, and fix it so that no control covers
the panel. Either the panel takes a maximum height and the controls stand above it, or the strip folds away while a
profile is open. Say in the report which was chosen and why. A layout test fails when the strip's or the scale
bar's bounds intersect the panel's. (2) Findability, following the pattern T253 set for the recording panel: the
header row fills the width, with the name taking the free space (`weight(1f)`, ellipsised), Fit next, and **Close**
pinned at the right edge as a 48 dp target. A downward swipe on the panel (`hideOnSwipe()`) and Back
(`BackHandler`) also close it. Close only hides the profile and keeps the track shown, as it does today. The word
stays "Close", as on the other cards (the 2026-11-07 idea's triage). `GET /tracks` already reports `profile` (the open
track or null), and `POST /tracks/close` closes it. The help entry for the panel names the swipe. Done when: `check` green with the layout test; on the
emulator at font scale 1.0 and 2.0, portrait and landscape, with the readout bar on and a long multi-segment track's
profile open, the screenshot shows the name, Fit and Close uncovered; tapping Close, swiping down and pressing Back
each close the panel and leave the track shown (`GET /tracks`); the strings and screenshots listed for the
owner (ADR 0031).
