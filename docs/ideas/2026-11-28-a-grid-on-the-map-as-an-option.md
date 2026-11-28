---
date: 2026-11-28
kind: feature
related: [2026-11-22-a-slope-meter-the-phone-as-an-inclinometer.md, 2026-11-28-type-or-paste-a-coordinate-into-search.md]
---

# A grid on the map, as an option

Source: coordinator (claude-fable-5-2), a wave close; offered and accepted by the owner; not sanity-checked.

The owner, 2026-11-28, accepting it: "#4 should be an option for a grid."

The idea: an option, off by default, that draws a grid over the map in the chosen coordinate format: UTM
kilometre lines (finer or coarser with the zoom) when the format is UTM, a latitude and longitude graticule
otherwise, with the values labelled where the lines meet the screen's edges. A grid reference read from a paper
map, a guidebook or a radio call can then be matched by eye, and the square you stand in can be read off.

Anchors: `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/geo/CoordinateFormat.kt` (the formats and
the UTM zone arithmetic), the map's own layers, and `androidApp/.../app/tracking/MapMarks.kt` so that a long press
on a grid line names it. The printed sheet has corner labels only (T167).

Lever: the usual way a topographic map is used with a compass and a second map; the formats it needs exist.

## Open

- Where the option lives (the map options beside the scale bar's, or Units and display) and whether it is synced.
- UTM zone edges, where two grids meet at an angle; the line spacing per zoom; the labels at large text sizes.
