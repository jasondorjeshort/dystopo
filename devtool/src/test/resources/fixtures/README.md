# devtool test fixtures

Real data kept for offline tests. Public places only (`areas.json` names).

| File | What | Source and licence |
|---|---|---|
| `rainier-dem-12-662-1443.png` | DEM tile z12/662/1443 (Mount Rainier's summit, `areas.json` rainier): 256 px Terrarium PNG, metres above EGM2008, read by `DemFixture.RAINIER` for the slope shading goldens (T104) | The tile as stored in the `rainier-dem.pmtiles` region pack (`devtool tiles`, source `best`, built 2026-10-12): USGS 3D Elevation Program (3DEP) 1/3 arc-second DEM and 1 m lidar DEM, courtesy of the U.S. Geological Survey; geoid NGS GEOID18 and EGM2008 via PROJ-data. All public domain (registry ids `3dep`, `3dep-1m`, `geoid18`, `egm2008`). |
