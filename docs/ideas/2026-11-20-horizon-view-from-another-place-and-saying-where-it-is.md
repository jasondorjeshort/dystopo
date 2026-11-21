---
date: 2026-11-20
kind: feature
related: [2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md, 2026-11-19-the-horizon-view-beside-the-map-view.md]
---

# The Horizon view from another place: two more ways in

The Horizon view (`horizon/HorizonData.kt`, the Map | Horizon switch of T309) can be drawn for a point other than the
phone's fix since T317: "Sky from here" on the long-press card opens it there, the place line under the plot says where
it stands ("Your location", "At <name>, <distance> <compass> of you", "<distance> <compass> of you"), tinted apart with
"Back to here", the satellites and flights hidden (`docs/reference/android-horizon-view.md`, Another place). The owner's
worry stands: a view of another place must never be read as your own sky.

What T317 left (the critic of wave 43: one pass too big) are two more ways to choose the point:

- A named peak's card in the Horizon view offers "View from here": the view moves to that peak (the line then reads
  "At <peak>, ..."), so a peak seen on the rim can be stood on.
- In Horizon mode, a "View from map centre" action: pan the map, then see the sky from its centre.

## Open

- Where "View from map centre" lives while the Horizon view covers the map (the switch's Map segment shows the map; the
  action would need a place on the map while Horizon is the chosen view, or on the view itself).

Plan: on T317's point mode (`HorizonUi.openAt(HorizonPoint)`, `HorizonPlace` in `shared/.../horizon/`): the picked
peak's line under the heading (`PickedPeak` in `app/horizon/HorizonScreen.kt`) gains "View from here", opening the
view at that peak's place with its name (`HorizonPoint(lat, lon, name)`; the peak's own `Peak` carries both); and a
"View from map centre" action whose place is settled first (the Open question; a benchmark survey if in doubt, ADR 0039).
Debug: `POST /horizon?viewFrom=<peak name>` and `POST /horizon/ui?centre=1`, documented in `HorizonRoutes.kt`'s KDoc.
Verify: the emulator's frames at Rainier's summit from a peak tap (from Paradise) and from the map centre, the place line
and "Back to here" in each; `android-ui.py`'s horizon step. New strings in the report (ADR 0031). Out of scope: the
Panorama view.
