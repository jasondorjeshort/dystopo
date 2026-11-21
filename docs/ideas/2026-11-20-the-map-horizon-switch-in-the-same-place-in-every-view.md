---
date: 2026-11-20
kind: feature
related: [2026-11-20-the-last-peak-row-under-the-switch.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md, 2026-11-19-the-horizon-view-beside-the-map-view.md]
---

# The Map | Horizon switch in the same place in every view

The owner: the view toggle has to be displayed in the same place in every view, so a tap that switched views once
switches back from the same spot.

Today it is two controls in two places (`app/controls/ViewSwitch.kt`, T309). On the map, `MapViewSwitch()` draws it
in the left lower column above the scale bar's line, where the layout rule puts it
(`ui/OverlayLayout.kt`, `OverlayIds.HORIZON_SWITCH`), so it moves with the other lower controls (the flights card,
the readout, the position buttons; `AircraftCard.kt` already reserves room above them). On the Horizon view
`HorizonScreen.kt` draws a second copy (`TAG_ON_VIEW`) fixed at the bottom of the content pane. Neither is the
other's position, and the map's own moves with the chrome.

Wanted: one anchor for the switch in every view (same corner, same distance from the screen edge, same size, same
order of segments), whether the map, the Horizon view, a long-pressed point's sky or a later Panorama view is
showing. The map's own chrome arranges itself around it, not the reverse.

## Open

- Where the anchor is: the owner chose the first place by trial and error (T309, "its place: trial and error"), so a
  top-of-screen or bottom-centre place is possible; the Horizon view's bottom edge is the candidate, since the map's
  lower column is the crowded one.
- The switch is a third segment when the Panorama view lands; the anchor must hold three.
- Landscape and a phone's gesture bar: the same rule at both, measured, not guessed.

Plan: one placement rule for the switch, shared by the map and the Horizon view: a single `ViewSwitchRow` anchor
in `ViewSwitch.kt` (the same alignment, insets from the window's system-bar insets and the same size), with
`OverlayLayout.kt` reserving that rectangle on the map (the other lower controls stack above it) and `HorizonScreen.kt`
using the same anchor instead of a pane-fixed copy. The scroll area of the Horizon view is padded by the switch's
measured height (this also closes `2026-11-20-the-last-peak-row-under-the-switch.md`'s question). Debug: `POST /horizon/switch` already answers the switch's place in both views (`map` and `view`), and `GET /layout` reads the map's; `tools/android-ui.py`'s horizon step asserts the two
rectangles are identical in portrait and landscape at font 1.0 and 2.0, with frames of both. Unit test on the layout
rule (the switch's rectangle is the same for every set of visible cards). New view placement in the report (ADR 0031).
