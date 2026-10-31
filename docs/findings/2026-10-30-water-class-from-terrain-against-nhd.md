---
date: 2026-10-30
status: resolved
area: routing / water along a route / drainage
---

# The terrain catchment ranks streams against NHD flow codes; "likely flowing" from 2 km² keeps 0.83 to 0.93 precision

## Question

T179 classes a stream crossing from the catchment of the derived line (`dem/FlowGrid.kt`, the `drainage` layer):
"likely flowing", "may run dry", "Gully, usually dry", dropped under channel size. The survey of 2026-10-30 gave
starting thresholds (0.5 and 5 km², drop under 0.1 km²) with no published standard behind them. How well does the
catchment agree with an independent flow code, which thresholds should stand, and is the mapped OSM tag a better
signal?

## What was tried

- Truth: USGS NHD high-resolution flowlines (public domain), pulled read-only by `tools/nhd-flowlines.py` from The
  National Map's NHD service (layer 6), densified to a point every 25 m (so tables weigh length). FCode 46006
  perennial, 46003 intermittent, 46007 ephemeral; artificial paths (55800) and 46000 left out. NHD codes come from
  aerial photos and maps at compile time, so they are a second opinion, not a measurement.
- Terrain: `devtool water --truth <csv> --area <area>`: one z13 flow grid (about 13 m cells, `best` source: 3DEP in
  the US, Copernicus and HRDEM over the border) of the area plus a 3 km margin; each NHD point takes the largest
  catchment within 30 m (NHD lines sit 10 to 40 m off the DEM's valley floors). Points whose catchment reaches the
  grid edge were left out.
- Samples: the `rainier` area (8,149 points), `glacier-waterton` (16,426) and the Rainier outdoors pack's box
  (119,236, mostly forested lowland, the only one with ephemeral codes: 347 points).
- Tags: with `--pack`, the class the nearest mapped `waterway` within 30 m gives (untagged stream "likely flowing",
  `intermittent=1` "may run dry").
- Routes: `devtool water` on two Rainier hikes (Mowich Lake to Spray Park, 5.2 km; Longmire up the Wonderland Trail,
  14.0 km) against the outdoors pack and a `drainage-pack` copy, each crossing matched to NHD within 30 m.

## Result

Catchment percentiles, km² (p25 / p50 / p75):

| Sample | Perennial | Intermittent | Ephemeral |
|---|---|---|---|
| rainier | 0.57 / 1.9 / 5.2 | 0.04 / 0.23 / 0.57 | none |
| glacier-waterton | 0.55 / 2.3 / 9.7 | 0.03 / 0.07 / 0.24 | none |
| Rainier pack box | 0.21 / 1.4 / 6.3 | 0.02 / 0.05 / 0.19 | 0.01 / 0.04 / 0.14 |

The ordering holds everywhere; the classes overlap widely. 10 to 17% of perennial points find under 0.1 km² within
30 m (headwaters fed by snow, glaciers or springs, and NHD lines farther than 30 m from the DEM channel).

"Likely flowing" threshold (dry 0.5), perennial precision / recall:

| From km² | rainier | glacier-waterton | Rainier pack box |
|---|---|---|---|
| 1 | 0.78 / 0.65 | 0.92 / 0.65 | 0.81 / 0.54 |
| 1.5 | 0.82 / 0.58 | 0.93 / 0.56 | 0.85 / 0.48 |
| 2 | 0.83 / 0.49 | 0.93 / 0.52 | 0.88 / 0.44 |
| 5 (survey) | 0.87 / 0.27 | 0.97 / 0.37 | 0.91 / 0.28 |

Perennial-against-not balanced accuracy peaks near 0.75 to 1 km² (0.76, 0.82, 0.76) and is 0.62 to 0.68 at 5.

- **Kept: likely flowing from 2 km².** The rule: the largest recall with at least 0.8 of "likely flowing" points coded
  perennial in all three samples (1.5 km², bound by `rainier`), rounded up because the `drainage` layer carries a
  line's downstream-end catchment, which reads high at a crossing. A false "likely flowing" is the costly error for
  a hiker planning refills, so precision leads.
- **Kept: gully under 0.5 km², dropped under 0.1 km².** NHD cannot tune them: ephemeral codes are almost absent and
  their catchments (median 0.04) do not separate from intermittent ones (0.05 in the same box). 71 to 87% of
  intermittent length is under 0.5 km² and 35 to 64% under 0.1 km²; the drop applies only to derived crossings with
  no mapped line beside them.
- **The OSM tag is a weak signal one way and a strong one the other.** An untagged mapped stream is NHD-perennial for
  only 52 to 54% of its length (precision 0.52 rainier, 0.54 pack box, recall 0.96 to 0.98); `intermittent=1` is
  NHD-intermittent for 82 to 87%. This supports the merge rule (the tag caps the class; the absence of the tag does
  not raise it) and shows that a pack without the `drainage` layer calls about half the NHD-intermittent mapped
  length "likely flowing".
- **Routes** (thresholds 0.5 / 2 / 0.1): stream crossings, mapped only / with the layer: Mowich to Spray Park 11 / 15,
  Longmire on the Wonderland 17 / 24 (the layer adds 4 and 7 after merging 4 and 8 and dropping 0 and 2). 7 to 12
  crossings per run match an NHD point within 30 m; on them the mapped tags agreed better (6 of 7 and 8 of 10) than
  the merged classes (3 of 7 and 8 of 12): anecdotes beside the area tables, which are the evidence.
- Not measured: season or climate (no source; deferred with T165), glacier-fed streams (NHD and the mask both thin
  there), places outside the two US areas.
