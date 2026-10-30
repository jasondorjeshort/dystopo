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

Plan: the algorithm and the devtool are built (T165, 2026-10-30): `shared/.../dem/FlowGrid.kt` (priority-flood with epsilon, D8, accumulation, a lower-bound flag where the upslope area reaches the grid edge) and `dem/Drainage.kt` (channel heads at a catchment threshold gated by the local slope, flats under 1 degree and a mask not drawn, lines of `DrainageLine`: grid points, `catchmentKm2`, `StreamClass`, `catchmentComplete`), run over a whole area in one grid since no per-tile margin can promise the upslope area; `devtool drainage --area rainier` scores it against the pack's `waterway` lines (`docs/reference/devtool-commands.md`). Left: (1) T174, the layer: build the grid per pack area in one pass at z13, cut the lines into tiles after, write a `drainage` layer with `catchment_km2` and the class beside `waterway` or on the contours' path (the task's first decision, reported), masked under `water` and `landcover` ice as the devtool does, drawn dashed and faint under `waterway` with a `StyleParityTest` row and a `tilebuild/PROFILE.md` section. (2) A second task: route crossings on the card and in the directions, from `routing/Elevation.kt`'s walk along the route. Out of scope: a climate source, culvert and bridge breaching.
