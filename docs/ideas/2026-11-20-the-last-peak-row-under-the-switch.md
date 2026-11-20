---
date: 2026-11-20
kind: bug
---

# The last peak row sits under the switch

Seen by the v0.1.35-alpha draft check on the emulator (frame `30-horizon-scrolled.png` of its `features` run): on the Horizon view, with the peak list scrolled to the end, the last row sits partly under the Map | Horizon switch fixed at the bottom of the content pane. T309's goal said the scroll area is padded so its last row clears the switch; the padding is short, at least in portrait at font 1.0. Listed in the release's known limits.

Wanted: the scroll area's bottom padding equals the switch's measured height plus its margin, at every font scale and in both orientations; checked with a scrolled-to-end frame at 1.0 and 2.0, portrait and landscape.

The owner, 2026-11-20: "Record them all. We'll triage and sanity check later."

Plan: confirm before fixing. In `app/horizon/HorizonScreen.kt` the switch is a sibling below the scroll Column
(`weight(1f)`), so by construction the scroll area ends above it; and `horizon_step` in `tools/android-ui.py`
captures `horizon-scrolled` after one swipe, not at the end (the frame in `out/ui` shows "Pinnacle Peak", the fifth
of twelve rows, clipped at the scroll area's edge above the switch, which is ordinary scrolling). The pass adds a
scrolled-to-end frame (swipe until `GET /horizon` `scrollPx` equals `scrollMaxPx`) at font 1.0 and 2.0, portrait and
landscape, and checks that the last row's bounds end above `switchPx`. If they do, the idea is closed as no defect
and the release's known-limits line is withdrawn in the report; if not, the scroll's bottom padding becomes the
switch's measured height. Either way the step keeps the end frame.
