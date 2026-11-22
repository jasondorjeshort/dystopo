---
date: 2026-11-20
kind: feature
related: [2026-11-19-the-horizon-view-beside-the-map-view.md]
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
- **Hop history** (2026-11-21, the coordinator's list after wave 44, recorded on the owner's "we can record them
  all"). Back steps through the places you looked from, not only to your own: "View from here" on a peak, then on a
  second peak seen from it, then Back returns to the first. Today Back and "Back to here" both go straight to the fix
  (`HorizonUi.back()`). The last hops return without a sweep, since `Gnss.horizons` keeps `HORIZON_KEEP` of them; the
  place line still says where each step stands, and "Back to here" stays the one-tap way home.
- The Panorama view's segment calls the same rule (`ViewSwitch.openAtSelection`) when it is built.

2026-11-22, triage: the last bullet is built (T326: the Panorama segment opens through `ViewSwitch.openAtSelection` by
`HorizonPlace.switchAction`). A tapped peak has no card on the map (only waypoints, tracks, trips and flights take a
tap), so the owner's "tapped peak" is the long-press card on a peak, which counts already.

Plan: one pass, the two settled bullets. (1) A tapped waypoint counts as the map's selection: `HorizonPlace.switchFrom`
(`shared/.../horizon/HorizonPlace.kt`, with its test) gains a waypoint case between the long-press card and the search
marker, and `ViewSwitch.openAtSelection` (`app/controls/ViewSwitch.kt`) reads the open waypoint sheet (`Tracking`'s
`selectedWaypoint`) and opens at it under its name; `POST /horizon/switch` answers `from: waypoint`. (2) Hop history:
`HorizonUi` (`app/horizon/HorizonScreen.kt`) keeps the places looked from, in memory, as many as `Gnss.HORIZON_KEEP`
holds; Back (`app/horizon/SkyViewEffects.kt`) steps to the one before and to the fix after the first; "Back to here"
still goes straight to the fix; `GET /horizon` reports the steps. Both sky views share it. Verify: the rule's unit
test; on the emulator at Paradise, a waypoint tapped and then each segment, and two "View from here" hops then Back
twice, the frames viewed with the place line at each step and `horizonSweeps` unchanged by the steps back. Docs:
`docs/reference/android-horizon-view.md`. Out of scope: "View from map centre", which is the owner's (whether it is
still wanted beside the switch); the pass cuts this file down to that bullet and leaves it without a block, in place
of deleting it. Not in a wave beside a task that edits `app/horizon/SkyViewEffects.kt`.
