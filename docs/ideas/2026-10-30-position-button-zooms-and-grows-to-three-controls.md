---
date: 2026-10-30
kind: feature
related: [2026-10-13-auto-download-when-zoomed-in.md]
---

# The position button zooms in, and can grow to one, two or three controls

Owner priority for wave 23 (2026-10-30). Anchor: `androidApp/.../app/controls/RecenterButton.kt`,
`Recenter.kt` (the tap), `MapOrientation.kt` (`tap`, the orientation state), `Orientation.kt` (`OrientationRules`).

## Wanted

1. **Tap zooms.** Today a tap off centre recentres and keeps the zoom; on a centred map it advances the
   orientation. The owner wants a tap to centre and also bring the map to a default close zoom. The one-button
   layout stays the default; the simple option is never removed.
2. **Optional wider bar**, a setting with three layouts:
   - **1 button** (default): the current button, now zooming.
   - **2 buttons**: zoom-to-me, and change the orientation type. No lock button (as in Google Maps).
   - **3 buttons**: zoom-to-me, lock the orientation type, change the orientation type.
3. **Lock without a button?** Try a long press on the one- and two-button layouts to toggle the lock.
4. **North-up lock.** The owner wants the map kept north-up, and today any pan or rotate changes it
   (`MapOrientation`: a bearing change drops north-up to free rotate). With the lock on, a gesture must not change the
   orientation type. Most users prefer other views, so the lock's default is off and the setting is the owner's lever.

## Open (settle in the task, per ADR 0039 and ADR 0044)

- The close zoom: a fixed default, or per activity (the recorder's activity picker is the remembered one), and whether
  a tap while already close zooms back out. A survey of how the established apps do it comes first (ADR 0039).
- Whether a tap on an already centred, already close map still advances the orientation, or the second button owns that.
- What the lock does to rotation gestures: ignore them, or let them rotate and snap back on release.
- Layout and the panels: the bar must clear the nav card, the readout strip and the scale bar (`RecenterLayout`).
- Settings: layout (1, 2 or 3), close zoom, lock default; each synced with a row, help text and a debug route.
- Debug: `GET /camera` already reports the button; extend it with the layout, the lock and the zoom target.
