---
date: 2026-10-29
kind: feature
related: [2026-10-13-routing-modes-crow-bushwhack.md]
---

# Streams the map forgot, drawn from the DEM

OSM's `waterway` layer (`tilebuild/PROFILE.md`: rivers from z8, streams from z12, an `intermittent`
flag) is thin in the backcountry: whole drainages have no line, and a line says nothing about how
much water runs in it. The DEM already on the phone can say both. Flow direction and flow
accumulation over `shared/.../dem/ElevationGrid.kt` give every cell its catchment area; cells above
a threshold are a channel, and the catchment size with the slope and the land cover class
(`landcover` layer) give a "likely running / seasonal / likely dry" class. Answers "where is water
on this route", which no paper map answers either.

- **Build side first:** a `tilebuild` or devtool step over a pack's DEM (the pyramid in
  `tiles/`, the contour path in `render/Contours.kt` is the model: a vector layer derived from the
  grid at build time), writing a `drainage` line layer with `catchment_km2` and the class, drawn
  dashed and faint under OSM's own waterways so a mapped stream wins.
- **Priority-flood or D8 with pit filling** on a grid with a margin from the neighbouring tiles,
  since a catchment crosses tile edges; the sink-filling step decides what the output looks like
  more than the routing step does.
- **On the route:** the elevation profile (`routing/Elevation.kt`) already walks the DEM along a
  route; mark where the route crosses a channel with its class, on the route card and in the
  directions ("stream crossing, likely dry in late season").
- Agent-testable: a unit test over a synthetic valley gets one channel down its axis with the
  catchment growing downstream; `devtool render --area rainier --layer drainage` writes a PNG to
  compare against OSM's waterways; a report counts channels and how many coincide with a mapped line.

## Open

- Season: "likely running" depends on climate and month; the first pass classes by catchment and
  slope only and says so. A climate source (precipitation normals) is a registry entry later.
- Noise on DEMs built from lidar: ditches, road culverts and bridges cut or dam channels; roads from
  OSM can breach dams, as hydrologists do by hand.
- Flat ground (meadows, lake beds) gives braided nonsense; suppress below a slope and inside
  `water` polygons.
- Benchmark (ADR 0039): how established apps and public hydrography datasets draw unnamed drainages
  and intermittent water, and whether any phone app derives them.
- Roughly one wave for the layer, one more for the route crossings.

Plan: a benchmark first (ADR 0039, technical variant: how public hydrography datasets and the established apps draw unnamed drainages and intermittent water, and which sink-filling method the hydrology tools use), then one task for the layer alone. The algorithm in `shared/.../dem/` (pure Kotlin): priority-flood pit filling, D8 flow direction and accumulation over an `ElevationGrid` read with a margin of neighbouring tiles, cells over a catchment threshold traced to lines carrying `catchment_km2` and a class (likely running, seasonal, likely dry) from catchment and slope only, suppressed under a slope floor and inside `water` polygons; a unit test over a synthetic valley (one channel down its axis, the catchment growing downstream). Devtool: `drainage --area rainier` writes the PNG over the hillshade and a JSON report (channels, how many lie within 30 m of a mapped `waterway` line). The layer into the pack the way `render/Contours.kt`'s output travels (where it lives, in the vector archive beside `waterway` or on the contours' path, is the task's first decision and is reported), drawn dashed and faint under `waterway` with a `StyleParityTest` row and a `tilebuild/PROFILE.md` section. Out of scope: route crossings on the card and in directions (a second task), a climate source, culvert and bridge breaching.
