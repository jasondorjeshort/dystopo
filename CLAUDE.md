# Dystopo — notes for agents

Open-source, offline-first topographic maps. Design, decisions, and findings: `docs/PLANNING.md`. Read it before making architectural changes. Work breakdown: `docs/TASKS.md`.

## Layout
- `shared/` — Kotlin Multiplatform core (currently JVM target only; Android and iOS come later). `commonMain` must stay pure Kotlin: **no `java.*` imports**, so it compiles for every target. Platform-specific pieces (image decoding, HTTP, zlib) are injected through small interfaces such as `ByteRangeSource` and `Decompressor`.
- `devtool/` — JVM command-line tool that runs shared-core features and prints JSON reports. JVM-only code (image I/O, HTTP, elevation sources) lives here.
- `tilebuild/` — JVM-only build tool: OSM extract → PMTiles via Planetiler (heavy dependencies stay here, out of devtool). `./gradlew :tilebuild:run --args="--extract liechtenstein --out out/liechtenstein.pmtiles"`; first run downloads ~1.45 GB of aux data into the cache.
- `tools/` — repo tooling: `PrivacyCheck.java` and the git hooks in `tools/git-hooks`.
- `areas.json` — public named test areas. Personal areas go in `devtool.local.json` (gitignored, same format, main checkout only).

## Conventions
- Every raster goes through `MercatorWindow`, so grids from different sources line up pixel for pixel.
- Elevation is `Float` meters in **EGM2008**; `NaN` means no data. Sources convert their own datum before returning.
- Coverage edges between sources are feathered with `Composite.over`; never hard-cut.
- Commands print one JSON object to stdout (agents parse it) and write PNGs to `out/`. Add counters and timings to reports rather than printing logs.
- Tests in `shared/src/commonTest` must run offline and fast. Network-dependent checks are devtool commands, not tests.

## Commands
JDK 21 is required. If `java` isn't on PATH in your shell, set `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` first.

```
./gradlew check                                           # all tests (no device needed)
./gradlew :devtool:run --args="areas"                     # list named areas
./gradlew :devtool:run --args="render --area rainier"     # hillshade + contours -> out/rainier-best-z13.png + JSON report
./gradlew :devtool:run --args="render --area rainier --source 3dep"   # sources: best (default; global, EGM2008), copernicus (global 30 m),
                                                                      #   3dep (US 10 m), 3dep-1m (US lidar, NaN = magenta), 3dep-best, terrarium (legacy)
./gradlew :devtool:run --args="compare --area rainier --zoom 14"      # sources side by side + diff stats
./gradlew :devtool:run --args="mvt dump --file tile.mvt"  # summarize a Mapbox Vector Tile (--gzip, --layer, --samples)
./gradlew :devtool:run --args="pmtiles info --file x.pmtiles"   # PMTiles header, metadata, per-zoom counts (--url for remote, --tile z/x/y)
./gradlew :devtool:run --args="tiles --area rainier --min-zoom 8 --max-zoom 13 --verify --preview-zoom 13"   # Terrarium XYZ tiles -> out/rainier-best/
./gradlew :devtool:run --args="render --help"             # all render options
```
`devtool` runs with the repo root as working directory. Downloads are cached in `cache/` (or `$DYSTOPO_CACHE`), output goes to `out/`. Both are gitignored. **View output PNGs** to check rendering; several bugs so far were only visible in the image.

## Working in parallel (several agents at once)
- The main checkout is `C:\Users\Dorje\Documents\src\android-app` (the folder name is historical; the project is Dystopo). While other agents are active, nobody commits to `main` directly.
- One task from `docs/TASKS.md` per agent. From the main checkout: `git worktree add ..\dystopo-wt\<task-id> -b task/<task-id>` and work only in that worktree. Stay inside the files the task owns.
- Set `DYSTOPO_CACHE=C:\Users\Dorje\Documents\src\android-app\cache` in your environment so worktrees share downloaded data instead of re-fetching it from USGS and AWS.
- Before pushing: `./gradlew check`, then `git fetch && git rebase origin/main`. Push the branch and open a PR: `gh pr create --fill`. Put measurements, timings, and what you saw in the PNGs in the PR description. Don't commit PNGs.
- Don't edit the Phase 0 log in `docs/PLANNING.md` from a task branch; the integrator adds the log entry when merging. Edit other docs only where the task says so.
- The privacy hooks run in worktrees too (they read `devtool.local.json` from the main checkout). Use only public areas from `areas.json` in anything committed or written up.
- When the task is merged: `git worktree remove ..\dystopo-wt\<task-id>` and delete the branch.
- Model per kind of work (for whoever launches agents): the most capable model for planning, docs, review decisions and merges; a strong coding model (Opus-class) for implementing a task and its tests; a mid-tier model (Sonnet-class) for verifying a PR (run `check`, view PNGs, check done criteria) and small fix-ups; the cheapest model (Haiku-class) for mechanical git work (rebase, push, open the PR from a given summary, cleanup). Set the model explicitly on every launch; the default inherits the launcher's, usually the most expensive.

## Rules
- **Never commit personal locations.** No home/travel areas, real GPS tracks, or coordinates that reveal where the developer lives or travels, in code, tests, fixtures, docs, planning logs, PR descriptions, or commit messages. That includes indirect hints: nearby place names, lidar project names, DEM cell/tile names. When logging results from a personal area, describe it generically. Use public places (Rainier, Mont Blanc, Glacier/Waterton, Bonneville) or synthetic data in anything committed.
  - Git hooks in `tools/git-hooks` enforce this against `devtool.local.json` (area names, `privateTerms`, bbox coordinates, DEM cell names). Enable once per clone: `git config core.hooksPath tools/git-hooks`. They need Java (`JAVA_HOME` or `java` on PATH).
  - **Never bypass with `--no-verify`.** If the check fires, fix the content.
- **Every feature must be agent-testable:** unit tests in `shared`, plus a devtool command (later: debug server endpoint) that triggers it and reports results as JSON or PNG.
- **Dependencies must be permissively licensed** (MIT, BSD, Apache-2.0, EPL/EDL). No GPL or AGPL dependencies anywhere; they'd block relicensing. LGPL is tolerated only in build-side tooling modules (`tilebuild`), never in `shared`, `devtool`, or app modules. Don't copy code from other projects.
- **Data sources need license and attribution recorded** (see `docs/PLANNING.md` › Licensing). Any output built from a source carries its attribution.
- Commit as you go with descriptive messages. Don't amend pushed commits. Solo work goes straight to `main`; parallel work goes through branches and PRs (above).
