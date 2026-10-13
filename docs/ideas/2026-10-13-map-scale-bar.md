---
date: 2026-10-13
kind: feature
status: new
pipeline: none
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
