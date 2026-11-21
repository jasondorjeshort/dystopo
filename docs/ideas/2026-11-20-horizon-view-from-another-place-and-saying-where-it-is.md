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

The owner, 2026-11-21: "we absolutely want to be able to look at the horizon and pano view from another location. If
we select a different location on the map and switch to horizon view we want the horizon from THAT location, not ours."
So the switch itself carries the place: with a location selected on the map (a long-pressed point, a tapped peak,
waypoint or search result), the switch's Horizon segment, and later Panorama, opens the view there, with the place line
and Back to here; with nothing selected, at the fix. That replaces "View from map centre" as the main way in.

## Open

- ~~Where "View from map centre" lives~~ (the owner, 2026-11-21: the map's selection goes with the switch). Left open:
  what counts as "selected" (a long-press card open, a tapped feature's card, a search result's pin), and whether
  "View from map centre" is still wanted beside it.

Plan: on T317's point mode (`HorizonUi.openAt(HorizonPoint)`, `HorizonPlace` in `shared/.../horizon/`): the picked
peak's line under the heading (`PickedPeak` in `app/horizon/HorizonScreen.kt`) gains "View from here", opening the
view at that peak's place with its name (`HorizonPoint(lat, lon, name)`; the peak's own `Peak` carries both); and the
Map | Horizon switch opens the view at the map's selected location when there is one (the owner, 2026-11-21; what
counts as selected is the Open question, settled in the task: at least an open long-press card and a tapped peak's card).
Debug: `POST /horizon?viewFrom=<peak name>`, and `POST /horizon/switch` honouring the selection, documented in
`HorizonRoutes.kt`'s KDoc. Verify: the emulator's frames at Rainier's summit from a peak tap (from Paradise) and from the
switch with a long-press card open, the place line and "Back to here" in each; `android-ui.py`'s horizon step. New strings in the report (ADR 0031). Out of scope: the
Panorama view.
