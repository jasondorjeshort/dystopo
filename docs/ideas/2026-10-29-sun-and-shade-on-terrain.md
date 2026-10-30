---
date: 2026-10-29
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md, 2026-10-29-turn-around-clock.md]
---

# Sun and shade on the real terrain

For a date and time, shade the map where the terrain blocks the sun, and answer for a point when
the sun actually reaches it and leaves it (behind the ridge, not the flat horizon). That covers
questions like when a north face gets sun, whether a camp is in shade by 4 pm, and when sunset
really is in a deep valley. Everything comes from data already on the phone: the DEM
(`shared/.../dem/ElevationGrid.kt`) and the hillshade code (`render/Hillshade.kt`, which already
lights the terrain from an azimuth and altitude but casts no shadows).

- **Sun position:** the standard solar position equations (declination, equation of time, hour
  angle), written in `shared` from the published formulas, with no dependency and no network.
- **Cast shadows:** march from each cell toward the sun over the elevation grid, as a hillshade
  variant with a time parameter. Tiles go through the existing shade-tile path (`tiles/ShadeTiles.kt`)
  with the time in the cache key.
- **Point answer:** the terrain horizon around a point (the highest elevation angle per bearing,
  from the same sweep the viewshed idea needs), crossed with the sun's path for the day, gives the
  sunrise and sunset times on the terrain plus any gaps when a peak blocks the sun.
- **Controls:** a time slider over the day on the map options sheet; the point answer in the
  point's card.
- Agent-testable: `devtool shade --area rainier --at <time>` writes a PNG; a unit test checks a
  synthetic cone's shadow points away from the sun and has the right length.

## Open

- Shares the horizon sweep with `2026-10-29-viewshed-and-peak-labels.md`; build the sweep once.
- Shadows cast from outside the tile: a ridge 10 km away shades this valley, so each tile reads a
  margin of coarser DEM toward the sun.
- Time zones: show the local time of the place viewed, not the phone's zone; that needs a zone
  lookup (a small bundled boundary set, like the country set of T141, with its source in the registry).
- Benchmark (ADR 0039): how established apps show sun and shadow, and which default time they open at.
- Roughly one wave for the overlay and the point answer.
