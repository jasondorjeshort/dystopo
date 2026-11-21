---
date: 2026-11-20
kind: feature
related: [2026-11-19-the-sky-from-a-long-pressed-point.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md, 2026-11-19-the-horizon-view-beside-the-map-view.md]
---

# The Horizon view from another place, with a line saying where it is

The Horizon view (`horizon/HorizonData.kt`, the Map | Horizon switch of T309) always shows the sky from the phone's
fix. There is no way to move to another location and see its horizon, which would be easy: the skyline is computed
from the DEM for any point. The owner's words: this has to be balanced against making it obscure when the view is
for a spot slightly or far from where you stand, so you read the wrong view as your own.

The sibling idea `2026-11-19-the-sky-from-a-long-pressed-point.md` chooses the point (a long-press card action) but
labels it only by a heading. This idea is the other half, and the two should be built together: the **place line**.

- A line just below the horizon itself, always present, one of:
  - "Your location" when the view's point is the fix (within a few tens of metres);
  - "At <feature name>" when the chosen point has a name (a peak, a pass, a hut);
  - "<x miles SSE of you>" (units per the user's setting, ADR 0022) otherwise, updating as the phone moves.
- A way to choose the point beside the long-press: pan the map and tap "View from map centre" in Horizon mode, or
  tap a named peak in the Horizon view itself (it moves the view there; the line then reads "At <peak>").
- A "Back to here" control whenever the point is not the fix, so a moved view is never mistaken for the current one;
  the line is tinted differently from "Your location" for the same reason.
- Satellites and the live compass heading make sense only at the fix: hidden or disabled for another point.

## Open

- Whether a moved point should snap back to the fix after a while (it is easy to leave the app showing a stale place).
- The distance wording for very short distances ("40 m N of you" vs "Your location").

Plan: built in the same task as, or straight after, `2026-11-19-the-sky-from-a-long-pressed-point.md` (its Plan
supplies the point mode, the hidden satellites and "Back to here"); this pass adds the place line and two more ways
in. The line sits just below the horizon in `app/horizon/HorizonScreen.kt`, always shown: "Your location" when the
point is the fix or within 50 m of it, "At <name>" when the point is a named feature (a tapped peak, or the
long-press card's feature), otherwise "<distance> <16-point compass> of you" (units per ADR 0022), recomputed as the
fix moves; the moved-point form is tinted differently from "Your location". Ways in: a named peak's card in the
Horizon view offers "View from here" (the line then reads "At <peak>"), and in Horizon mode a "View from map centre"
action. A moved point is not persisted: the app reopens at the fix (no timed snap-back, which would change the view
under the user). The line's text is a pure function in `shared` (`horizon/`) with a unit test (fix, 40 m, 12 km SSE,
named). Debug: `GET /horizon` reports `point` and `placeLine` (KDoc of `HorizonRoutes.kt`). Verify: the emulator's
frames at the fix, at Rainier's summit from a peak tap and from the map centre, `android-ui.py`'s horizon step. New
strings in the report (ADR 0031). Out of scope: the Panorama view.
