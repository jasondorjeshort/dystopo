# Dystopo — notes for agents

Open-source, offline-first topographic maps. Design and decisions: `docs/PLANNING.md`. Read it before making architectural changes.

## Layout
- `shared/` — Kotlin Multiplatform core (currently JVM target only; Android and iOS come later). `commonMain` must stay pure Kotlin: **no `java.*` imports**, so it compiles for every target.
- `devtool/` — JVM command-line tool that runs shared-core features and prints JSON reports. JVM-only code (image I/O, HTTP) lives here.
- `areas.json` — public named test areas. Personal areas go in `devtool.local.json` (gitignored, same format).

## Commands
JDK 21 is required. If `java` isn't on PATH in your shell, set `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` first.

```
./gradlew check                                           # all tests (no device needed)
./gradlew :devtool:run --args="areas"                     # list named areas
./gradlew :devtool:run --args="render --area rainier"     # hillshade + contours -> out/rainier-z13.png + JSON report
./gradlew :devtool:run --args="render --area rainier --source 3dep"   # sources: terrarium (global), 3dep (US 10 m),
                                                                      #   3dep-1m (US lidar, NaN = magenta), 3dep-best (lidar over 10 m)
./gradlew :devtool:run --args="compare --area rainier --zoom 14"      # sources side by side + diff stats
./gradlew :devtool:run --args="render --help"             # all render options
```
`devtool` runs with the repo root as working directory. Downloads are cached in `cache/`, output goes to `out/`. Both are gitignored. View output PNGs to check rendering.

## Rules
- **Never commit personal locations.** No home/travel areas, real GPS tracks, or coordinates that reveal where the developer lives or travels, in code, tests, fixtures, docs, planning logs, or commit messages. That includes indirect hints: nearby place names, lidar project names, DEM cell/tile names. When logging results from a personal area, describe it generically. Use public places (Rainier, Mont Blanc) or synthetic data in anything committed.
  - Git hooks in `tools/git-hooks` enforce this against `devtool.local.json` (area names, `privateTerms`, bbox coordinates, DEM cell names). Enable once per clone: `git config core.hooksPath tools/git-hooks`. They need Java (`JAVA_HOME` or `java` on PATH).
  - **Never bypass with `--no-verify`.** If the check fires, fix the content.
- **Every feature must be agent-testable:** unit tests in `shared`, plus a devtool command (later: debug server endpoint) that triggers it and reports results as JSON or PNG.
- **Dependencies must be permissively licensed** (MIT, BSD, Apache-2.0, EPL/EDL). No GPL/LGPL/AGPL dependencies; they'd block relicensing.
- **Data sources need license and attribution recorded** (see `docs/PLANNING.md` › Licensing). Any output built from a source carries its attribution.
- Commit and push as you go. Don't amend pushed commits.
