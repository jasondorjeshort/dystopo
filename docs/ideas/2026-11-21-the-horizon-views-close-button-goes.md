---
date: 2026-11-21
kind: feature
related: [2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md]
---

# The Horizon view's Close button goes, and the Panorama view never gets one

The owner, 2026-11-21: the Horizon view "has an unhelpful close button that just takes you back to map view ... it
should just go away. And we don't need a similar button for pano view." The button stands at the right of the heading
row (`androidApp/.../app/horizon/HorizonScreen.kt`, the row with the title and its help: `Button(GnssText.CLOSE, pal) {
HorizonUi.close() }`). It dates from the view's days as a page opened from Settings (T307, T308). Since T309 the Map |
Horizon switch stands in the same place in every view (T315), and its Map segment does what Close does; Back does too.
Horizon is a view beside the map, not a page over it, and a view has no Close.

The owner: not now ("We don't need to fix that now but record it").

Plan: remove the button from the heading row; the title and its help take the row. The switch's Map segment and Back
remain the ways out (Back still lets go of the menu, the confirmation, a picked peak and a chosen point first, then
closes). Follow the string and the tag to their other users before deleting them (`GnssText.CLOSE`; the sweep's
`horizon_step` and the a11y screens in `tools/android-ui.py` and `tools/android-a11y.py` already leave by the switch
since T309, so check for a leftover tap on Close; `tools/a11y-baseline.json` lines for the button go). Docs:
`docs/reference/android-horizon-view.md` (The screen: "its help, Close"; The switch: "closes it as Close and Back do").
The Panorama view's pass two (ADR 0064, its idea's Plan) builds its heading without one. Verify: frames of the view at
font 1.0 and 2.0 in both orientations; the Map segment and Back each return to the map; `android-a11y.py --screens
horizon` with nothing beyond the baseline. The removed string in the report (ADR 0031).
