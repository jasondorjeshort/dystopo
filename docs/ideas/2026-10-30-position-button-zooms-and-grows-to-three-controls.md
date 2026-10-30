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

## Prior art (owner, 2026-10-30, from use; not yet surveyed)

CalTopo, which the owner takes as the gold standard for this kind of app: the map is always north-up (a power-user
choice; most apps default otherwise) and one button brings the map onto the user at zoom 14. That supports the one-button
default zooming to a fixed close level, with z14 as the starting value, and the lock as the power-user setting. The task's
survey (ADR 0039) still checks the mainstream apps and records where the build departs.

## Open (settle in the task, per ADR 0039 and ADR 0044)

- The close zoom: z14 to start (above), a fixed default or per activity (the recorder's activity picker is the
  remembered one), and whether a tap while already close zooms back out. The survey comes first (ADR 0039).
- Whether a tap on an already centred, already close map still advances the orientation, or the second button owns that.
- What the lock does to rotation gestures: ignore them, or let them rotate and snap back on release.
- Layout and the panels: the bar must clear the nav card, the readout strip and the scale bar (`RecenterLayout`).
- Settings: layout (1, 2 or 3), close zoom, lock default; each synced with a row, help text and a debug route.
- Debug: `GET /camera` already reports the button; extend it with the layout, the lock and the zoom target.

Plan: one task, wave 23's first priority, merged before the readout bar task (that one keeps clear of this bar). Survey
first (ADR 0039, `brief` skill `benchmark.md`): the tap-to-me zoom in the mainstream map and topo apps (a fixed level or per
activity, whether a second tap zooms out), the orientation control and any north-up lock; the report says where the build
departs from the owner's CalTopo reference. Defaults unless the survey overturns one: (1) `OrientationRules.tap`
(`controls/Orientation.kt`) gains a `close` input (the camera at or nearer than the close zoom) and a zoom step: off centre,
recentre and zoom in to the close zoom; centred but farther out, zoom in; centred and close, advance the orientation (one-button
layout only); a tap never zooms out. `MapOrientation.tap` hands the zoom to its existing `move(target, bearing, zoom, ms)`.
Navigation keeps its own zoom: `Recenter.tap` from `navCard`, or any tap while `NavSession.active`, passes none. (2) The lock,
`MapOrientation.locked`: rotate gestures off (`uiSettings.isRotateGesturesEnabled = false`: ignored, not snapped back, so
nothing fights the finger); `onCameraIdle`'s `bearingChanged` turns the camera back to north instead of entering free rotate;
a tap no longer advances; the orientation button and the nav card still change it. (3) `RecenterButton.kt` draws one, two or
three 48 dp buttons as a column at the lower right growing upward (the scale bar and the readout strip are lower left):
two is zoom-to-me and change orientation, three adds the lock (a padlock icon). `RecenterLayout` reports every button's bounds
and `clear` covers the column; the `noRoomBelowChips` rule drops the extra buttons before the bar. A long press
(`combinedClickable`, plus a semantics custom action "Lock orientation" for TalkBack and the tools) toggles the lock on the one-
and two-button layouts, with a short toast. (4) Settings (ADR 0044; the owner asked for each): `mapControls` 1|2|3 (default
1), `recenterZoom` (default 14; per activity only if the survey finds apps do that), `orientationLock` (default off). Each a
synced key through the registry files (`AppSettings.kt`, `SettingsRoutes.kt`, `settings/SettingsLayout.kt`,
`settings/SettingsPages.kt`, `sync/SettingsSync.kt`), a row and help text (`HelpRegistryTest`). The orientation itself stays
per-device. (5) Debug: `GET /camera` adds `recenter.layout`, `lock`, `closeZoom`, the zoom before and after in `lastTap` and every
button's bounds; `POST /camera/recenter?long=1` long-presses. Update the `ControlRoutes.kt` KDoc and `android-debug-routes.md`.
Verify: `OrientationRulesTest` rows for each tap case and the lock. On the emulator, a fix at Paradise, a pan away at z10:
one tap lands on the fix at z14 and a second advances; with the lock on, a two-finger turn leaves the bearing at 0. Screenshots
of the three layouts at font scale 1.0 and 2.0, with the nav card and the route card open and `clear: true`. Update the
`recenter` part of `tools/android-ui.py`: it asserts that the zoom is kept. Out of scope: the readout bar, MapLibre's own compass.
