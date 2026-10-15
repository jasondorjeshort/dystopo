---
date: 2026-10-13
kind: feature
status: done
pipeline: T101
related: [2026-10-13-large-distance-units.md, 2026-10-13-recenter-button.md]
---

# Scale bar on the phone map

A distance scale, lower left (or beside the recenter button), updating with zoom and latitude.
Shows the user's own units only by default; an option shows both metric and imperial, as many
printed maps do.

Today: the browser viewer (`tools/demo/index.html`) has a scale bar that follows the units
preference; the Android app has none.

## Open

- Placement and size; whether it hides while a nav bar or route card is open.
- The "both units" option: a second bar (two-sided ruler) or one bar with two labels.
- Rounding rule: a "nice" length (1, 2, 5 times a power of ten) fitted to a target pixel width.
  The unit choice for that length is the large-distance-units idea.
- The look is ours to iterate on in alpha.

## Resolved

T101 (wave 14): a scale bar lower left on the phone map, above the credit line and whatever bottom panel
shows (route card, track profile), hidden while the trip card is open. Its length is 1, 2 or 5 times a power
of ten in the unit the distance rule gives it (`UnitFormat.scaleBar`), at most 100 dp, at the camera's zoom
and centre latitude. Map options › Scale bar: One unit (the default) or Both units, a two-sided ruler with
the user's units above the line and the other system below. Light ink on a dark plate in the night style.
Debug: `GET /scalebar`, `POST /scalebar?both=on|off`. The browser's bar adopts the same rule in T107.
