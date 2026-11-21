---
date: 2026-11-20
kind: feature
related: [2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md, 2026-11-19-the-horizon-view-beside-the-map-view.md]
---

# The Horizon view from another place: what is left

The Horizon view can stand at a point other than the phone's fix: "Sky from here" on the long-press card (T317), a picked
peak's "View from here" in the view, and the Map | Horizon switch with a place selected on the map (T321: an open
long-press card, else a search result's marker; `docs/reference/android-horizon-view.md`, The switch and Another place).
The owner's worry stands: a view of another place must never be read as your own sky.

What T321 left:

- **Waypoints as selections.** The owner, 2026-11-21, named "a long-pressed point, a tapped peak, waypoint or search
  result" as the map's selection; a tapped waypoint (its sheet open) does not yet count for the switch.
- **"View from map centre"** in Horizon mode (pan the map, then see the sky from its centre): the switch with a selection
  replaced it as the main way in; whether it is still wanted beside it is open.
- The Panorama view's segment calls the same rule (`ViewSwitch.openAtSelection`) when it is built.
