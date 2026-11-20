---
date: 2026-11-19
kind: feature
related: [2026-11-19-the-horizon-view-beside-the-map-view.md]
---

# The sky from a long-pressed point

The GPS view's skyline and sun are drawn for where the phone is. The long-press card already offers "What can I
see from here" for any point (T182, `app/viewshed/`). Add "Sky from here" beside it: the sky plot for that point,
with its skyline, today's sun (T308) and no satellites. For planning: is a campsite in a deep valley,
when does the sun reach this bivy, does this col have open sky for a satellite messenger. The GPS view then has
two modes, here (with the satellites) and a chosen point (without).

## Open

- The card's room: it already carries several actions; whether "Sky from here" folds under the viewshed's action.

Plan: a "Sky from here" action on the long-press card beside "What can I see from here" (T182's action, the card in
`app/viewshed/` and its label in `options/MapOptionsSection.kt`), opening the Horizon view for that point: the
skyline, peaks and today's sun computed there (`horizon/HorizonData.kt` given a point in place of the fix), with the
satellites layer hidden and its toggle disabled while a chosen point is shown, the heading naming the point ("Sky
from 46.85, -121.76" or its feature name) and a "Back to here" control returning to the phone's position. The
Horizon view's synced layer settings are not changed by the point mode. Debug: `POST /horizon/ui?lat=&lon=` and
`GET /horizon` reporting `point`, documented in `HorizonRoutes.kt`'s KDoc. Verify: a unit test of the point source,
the emulator's frames for Rainier's summit and Paradise from the card, `android-ui.py`'s horizon step extended by one
frame. New strings in the report (ADR 0031). Out of scope: a date picker, the Panorama view.
