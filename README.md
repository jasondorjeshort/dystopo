# Dystopo

Open-source, offline-first topographic maps and backcountry navigation. Android first, with a server, desktop, and iOS planned.

**Status (October 2026): phase 0, desktop exploration.** The elevation pipeline works: a Kotlin Multiplatform core reads USGS 3DEP (10 m and 1 m lidar) and Copernicus GLO-30 (global 30 m) directly from their public cloud files, converts everything to one vertical datum, blends sources seamlessly, and renders hillshade and contours. There is no app yet; everything runs from a command-line devtool. Next: the OpenStreetMap base layer (trails, roads, water, names), then the Android shell.

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
