---
date: 2026-11-15
kind: bug
related: [2026-11-14-sweep-frames-carry-the-place-they-show.md]
---

# A first launch with no map ignores a fix that arrives after about four seconds

Seen by T287 and its verifier (2026-11-15, the emulator, debug build): with no pack, no saved view and no stored
fix the app opens the world view (`OpenView.choose`, `app/MapViewport.kt`), and its window for a first fix should
then be the long one (`OpenView.STALE_FIX_WINDOW_MS`, 60 s, since no view was ever set). In three of four runs the
window ended after 3.8 to 4.4 s with the state `cameraMoved` and `fixes` 0 (`GET /startup`, `openView.window`),
before any gesture: something of the app's own moves the camera as the world view comes to rest, and the window
reads it as the user's move. A fix that arrives after that (4 to 5 s on the emulator; a real GPS cold start is
slower) no longer recentres the map on that launch; the next launch opens on it. A new user without a map would
see the world, not where they are.

## Steps

1. A new AVD, `tools/android-emu.ps1 -NoPush -Server none` (the boot sends a fix on Paradise at Rainier, T287).
2. Launch the debug app; read `GET /startup` (`openView.won`, `openView.window`) and `GET /camera`.
3. The camera is at the world view, `window` reads `cameraMoved` with `endedAfterMs` near 4,000 and
   `recentredTo` null; relaunch and it opens `view from fix`.

## Open

- What moves the camera at about 4 s (the world view's own settle, a style reload, the first-run card's layout),
  and whether the window should end only on a gesture.
- Whether a real phone's first launch shows the same (its first fix usually takes longer than the emulator's).

Plan: one implementer, phone only. The likely cause, read from the code (triage, 2026-11-16, unconfirmed on the
device): under `OpenView.Source.WORLD` the open view is null, so `MapController.opened` sets `placed` to null, and
the map's first `onCameraIdle` after the style loads reaches `atRest`, which ends the window as `cameraMoved` (and
saves the world view through `SavedView.save`) although nothing moved. First confirm it: `endedAfterMs` matches the
first camera idle (a `StartupTrace` mark or a log line). Fix: while the window is open and no gesture or `cameraSet`
has ended it, a rest under a WORLD open is the app's own placement: take that rest's camera as `placed`, do not save
it, keep the window open; the rule as a pure function in `OpenView` (`app/MapViewport.kt`) with cases in
`OpenViewTest`. Verify: this file's Steps on a fresh AVD four times, `GET /camera` `openView.window` reads `recentred`
with `recentredTo` set each time; a pan inside the window still ends it as `gesture`; the screenshot viewed.
Out of scope: the window lengths, a real phone's cold start, the sweep's frames.
