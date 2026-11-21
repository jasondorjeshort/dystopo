---
date: 2026-11-21
kind: bug
---

# At the largest text the first-run card covers the activity chip and the readout

At font scale 2.0 the first-run card ("No map on this phone yet", `app/firstrun/FirstRunCard.kt`) is tall enough to
start under the top chips and cover the On foot activity chip and the readout bar. Since T315 the card stands above the
Map | Horizon switch, which lifted it further. Wave 43's sweep flagged it (`a11y`, `first-run:2.0`, an overlap) and T318
baselined it as an overlap for the owner's eye.

The owner, 2026-11-21: record it and do it basically right away, next pass perhaps.

Plan: cap the card's height to the room `OverlayLayout` leaves between the top row and the switch's band and let its
body scroll (the route card's cap, `mapRowDp` in `routing/RouteControls.kt`, is the model), keeping every action on
the card reachable; if the room is still short at 2.0, fold
the secondary actions under one "More" so the chip and readout stay clear. Verify: the emulator at font 1.0 and 2.0 in
portrait and landscape, frames viewed; `GET /layout` overlaps empty with the card up; `tools/android-a11y.py --screens
first-run` at both scales; strike the two `first-run:2.0` overlap lines T318 added to `tools/a11y-baseline.json`.
New strings in the report if a "More" is added (ADR 0031).
