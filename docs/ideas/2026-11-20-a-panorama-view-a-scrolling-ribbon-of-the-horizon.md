---
date: 2026-11-20
kind: feature
related: [2026-11-19-the-horizon-view-beside-the-map-view.md, 2026-10-29-viewshed-and-peak-labels.md]
---

# A Panorama view: the horizon as a scrolling ribbon, the third view beside Map and Horizon

The owner, 2026-11-20: a third view. With it the app has three: **Map**, **Horizon** and **Panorama**. The Panorama
view shows just a ribbon of the horizon (the skyline, flattened to a strip) that you scroll around in, instead of
the Horizon view's round sky plot. It shows what the Horizon view shows at the same angles: peaks first, and the
other layers too (GPS satellites, the sun's path, your own places; see the layer list of
`2026-11-19-the-horizon-view-beside-the-map-view.md`). What it adds is room: a strip as wide as you care to scroll
gives each label space and potentially allows zooming in, which the round plot cannot (T309's closest-peaks-first
rim and its leader lines exist because the rim is crowded).

Anchors: the switch is T309's Map | Horizon button on the map (a third entry); the skyline and peak candidates are
`Viewshed.peaks` and the Horizon view's layer toggles (T307, T308), which the ribbon can share rather than copy.

Notes for when it is built (nothing decided):
- Horizontal axis is bearing (0-360, wrapping), vertical is elevation angle; scrolling pans bearing, a pinch zooms
  the field of view. A compass-following mode (the ribbon turns with where the phone points) is the natural default
  of "Follow the phone".
- The same layer toggles as the Horizon view, kept in one place so a layer on in one view is on in the other.
- Zoom changes label density: coarse zoom names only the closest or most prominent peaks, fine zoom names more.
- Agent-testable like the rest: a debug route that sets bearing and field of view and returns the screenshot.

Not started; recorded for later.

Question: build the Panorama view now as one task after a benchmark of how the peak and panorama apps present a
scrolling ribbon (zoom, follow-the-phone, label density), or wait until the Horizon view's open items settle
(the peak-names density setting, the moon, the long-pressed point) so the ribbon shares finished layers? Recommended:
wait for those three, then a benchmark and one task; the ribbon is a third view on T309's switch, and building it on
layers still moving would mean doing the label and layer work twice.

Answer:
