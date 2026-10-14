---
date: 2026-10-13
kind: feature
status: planned
pipeline: T104
related: [2026-10-13-no-other-product-names.md]
---

# Two slope shadings: avalanche-risk window and plain steepness

"Caltopo mode" is not a term (and see the no-other-product-names idea). There are two different
shadings and the map options should offer them as such:

- **Avalanche slope angle:** colours keyed to the slope range where avalanches release. Reddest at
  the highest-risk angles; slopes above and below the window get their own, different colours.
- **Plain steepness:** a ramp where steeper is scarier (a continuous or banded colour ramp), with
  no avalanche meaning.

Today: one overlay (`Slope.bands` in `shared/.../render/Slope.kt`, option `slope` in the Map
section): no tint below 27 degrees, yellow through red at 35-45, then violet and blue above that.
That is the avalanche one, labelled in the UI "CalTopo bands" (`MapOptionsSection.kt`).

## Open

- Source the avalanche slope ranges from public avalanche-safety guidance (the commonly published
  release range is roughly 30 to 45 degrees, most frequent near 38), cite it in the registry or
  docs, and decide band edges; today's edges (27, 30, 32, 35, 46, 51, 60) are our own choice.
- The option shape: `slope` off / avalanche / steepness; labels and legend in the app; units do
  not matter (degrees).
- Add a legend, and a safety line: slope shading is not an avalanche forecast.
- Renames in code, KDoc, README, the debug routes docs and the `--slope-overlay` help text.
