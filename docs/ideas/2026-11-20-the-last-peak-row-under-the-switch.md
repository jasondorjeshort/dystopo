---
date: 2026-11-20
kind: bug
---

# The last peak row sits under the switch

Seen by the v0.1.35-alpha draft check on the emulator (frame `30-horizon-scrolled.png` of its `features` run): on the Horizon view, with the peak list scrolled to the end, the last row sits partly under the Map | Horizon switch fixed at the bottom of the content pane. T309's goal said the scroll area is padded so its last row clears the switch; the padding is short, at least in portrait at font 1.0. Listed in the release's known limits.

Wanted: the scroll area's bottom padding equals the switch's measured height plus its margin, at every font scale and in both orientations; checked with a scrolled-to-end frame at 1.0 and 2.0, portrait and landscape.

The owner, 2026-11-20: "Record them all. We'll triage and sanity check later."
