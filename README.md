# Dystopo

Open-source, offline-first topographic maps and backcountry navigation. Android first, with a server, desktop, and iOS planned.

**Status (October 2026): phase 0b complete; phase 1 (Android) started.** A Kotlin Multiplatform core reads USGS 3DEP (10 m and 1 m lidar) and Copernicus GLO-30 (global 30 m) directly from their public cloud files, converts everything to one vertical datum, blends sources seamlessly, and renders hillshade, slope shading and labelled contours. An OpenStreetMap build tool (Planetiler, with an outdoors profile) produces our own vector archives, and the renderer draws trails, water, peaks and names over the terrain from a declarative style. Region packs are PMTiles (DEM and vectors) that any PMTiles-aware tool can open; a browser demo (MapLibre) views them live. An Android shell (Compose + MapLibre Native) shows a region pack with hillshade, trails and contours generated on the device, driven for testing through a loopback debug server. A world tour of 22 public landmarks and a 14-view browser tour run as sanity sweeps.

- Design, decisions, and findings: [docs/PLANNING.md](docs/PLANNING.md)
- Work breakdown for parallel development: [docs/TASKS.md](docs/TASKS.md)
- Conventions and commands for agents: [CLAUDE.md](CLAUDE.md)

## Quick start
Requires JDK 21 and Git.

```
./gradlew check                                      # build and run all tests
./gradlew :devtool:run --args="render --area rainier" # out/rainier-best-z13.png + JSON report
./gradlew :devtool:run --args="compare --area glacier-waterton --sources copernicus,best"
```

## License
Code: GPLv3 (see [LICENSE](LICENSE)); other modules may use AGPLv3/LGPLv3 later. Map data keeps its sources' licenses (OSM: ODbL; USGS: public domain; Copernicus: attribution required). See [docs/PLANNING.md › Licensing](docs/PLANNING.md#licensing).
