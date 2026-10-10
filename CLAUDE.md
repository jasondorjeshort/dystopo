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
- Rendering changes are guarded by golden images (`devtool/src/test/resources/goldens/`, synthetic terrain only). If `check` fails there and the change is intended: `./gradlew updateGoldens`, look at the new PNGs, commit them with the change. Those goldens are the one place PNGs belong in the repo.

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
./gradlew :devtool:run --args="render --area rainier --shading multi --slope-overlay"   # shading hillshade|multi|none; CalTopo-style slope bands
./gradlew :devtool:run --args="sources --ids best"        # data source registry; composed attribution for a source
./gradlew :devtool:run --args="tour --zooms 9,12"          # world sanity sweep: areas.json expectedElevation/tags -> out/tour/summary.json + contact-sheet.png; exit 1 on failure
./gradlew :devtool:run --args="gpx info shared/src/commonTest/resources/gpx/skyline-loop.gpx"   # track stats; also gpx render --osm ... and gpx export
./gradlew :devtool:run --args="tour --zooms 9,12"         # world tour: every area with expectedElevation, plausibility checks, contact sheet
./gradlew :devtool:run --args="render --help"             # all render options
```
Browser demo (MapLibre over our DEM tiles and OSM PMTiles): generation commands are at the top of `tools/demo/index.html`; then `python -I tools/demo-server.py 8765` and open http://localhost:8765/tools/demo/. **Agents check it headlessly** with `python -I tools/demo-check.py --view <z/lat/lon> --out out/check.png` (Playwright + Chrome): exit 0 means the map went idle with no console errors, page exceptions, or failed requests; the JSON lists rendered feature counts per layer and the screenshot path, so you can see what a user would see. A zero count on a layer that should have features is a bug, not a style choice.

`devtool` runs with the repo root as working directory. Downloads are cached in `cache/` (or `$DYSTOPO_CACHE`), output goes to `out/`. Both are gitignored. **View output PNGs** to check rendering; several bugs so far were only visible in the image.

## Android
`androidApp/` (Compose + MapLibre Native + `shared`) shows a region pack: hillshade from the DEM PMTiles (MapLibre `raster-dem`), OSM from the outdoors PMTiles, and **contour tiles generated on the device** (`shared/.../tiles/ContourTiles.kt`, cached on disk). MapLibre can't read PMTiles, so the app runs a loopback-only HTTP server (port 8686) serving `/dem`, `/osm`, `/contours`, `/glyphs` and `/style.json` (the MapLibre style is translated from the devtool's `DefaultOsmStyle` by `shared/.../maplibre/MapLibreStyle.kt`). The module and `shared`'s android target exist only when an SDK is installed (`settings.gradle.kts` looks at `local.properties` / `ANDROID_HOME`), so SDK-less machines still build.
```
powershell -ExecutionPolicy Bypass -File tools\android-sdk.ps1     # SDK under %USERPROFILE%\Android\sdk + local.properties (no admin)
./gradlew :androidApp:assembleDebug
powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Build   # headless emulator, install, push out/rainier-*.pmtiles, launch, adb forward tcp:8686
powershell -ExecutionPolicy Bypass -File tools\android-push-pack.ps1 -Dem out\rainier-dem.pmtiles -Osm out\rainier-outdoors.pmtiles   # packs + glyphs only
adb forward tcp:8686 tcp:8686                                      # if you started the emulator yourself
```
Debug endpoints (debug builds only; `curl http://localhost:8686/...`):
- `GET /health`: packs, map state (`styleLoaded`, `idle`), server requests.
- `GET /screenshot[?wait=ms][&mode=window]`: PNG of the map (MapLibre snapshot) after waiting for the map to go idle; `mode=window` uses PixelCopy and includes overlays. **View it**, like the devtool PNGs.
- `GET /camera`; `POST "/camera?lat=46.853&lon=-121.76&zoom=12&bearing=0"` (or a JSON body).
- `GET /stats`: tiles served per source, contour generation ms (min/avg/max, recent tiles with sample/trace split), disk-cache hits, DEM decode cache, memory (Java heap, native heap, PSS by category), APK and pack bytes.
- `POST "/location?lat=..&lon=..&accuracy=10"`: inject a fake GPS fix (the location puck moves); `GET /location`.
- `POST /reload` (reopen packs after a push), `POST /cache/clear` (drop cached contour tiles).

The emulator needs CPU virtualization, which an administrator enables once: Windows Hypervisor Platform (`Enable-WindowsOptionalFeature -Online -FeatureName HypervisorPlatform -All`, reboot) or the Android Emulator hypervisor driver. Without it, x86_64 images never start under `-accel off` and ARM images are refused on x86_64 hosts, so `android-emu.ps1` stops with these instructions; `-NoEmulator` uses a phone connected with USB debugging instead.

**No device?** `./gradlew :devtool:run --args="serve"` serves the same routes from the same shared code (style, DEM with ancestor fallback, generated contours, glyphs from `cache/glyphs`) on http://127.0.0.1:8687/ with a MapLibre GL JS page, so the phone style can be checked with `python -I tools/demo-check.py --url http://localhost:8687/ --views 12.5/46.853/-121.76` (MapLibre GL JS, not Native: it catches style and tile bugs, not Android ones). `GET /stats` there gives desktop contour timings.

## Debug before you push
The implementer debugs the change; the reviewer confirms. Before opening a PR:
1. `./gradlew check` green (unit tests, goldens, license check).
2. Exercise the change end to end with the real tooling: render the affected areas at the zooms that matter and look at the PNGs; run `tiles --verify` for anything touching tiles; run `tools/demo-check.py` for anything touching the viewer; run `tilebuild` for anything touching the profile. Fix what you find, then re-run.
3. The PR body lists exactly what was run and what was seen. A verifier agent then repeats the key runs; "the reviewer will catch it" is not a plan.

## Working in parallel (several agents at once)
- The main checkout is `C:\Users\Dorje\Documents\src\android-app` (the folder name is historical; the project is Dystopo). While other agents are active, nobody commits to `main` directly.
- One task from `docs/TASKS.md` per agent, in its own git worktree (an agent launched with worktree isolation gets one under `.claude/worktrees/`; otherwise `git worktree add ..\dystopo-wt\<task-id> -b task/<task-id>`). Rename or create the branch `task/<task-id>`, work only in that worktree, and stay inside the files the task owns (the task's **Owns:** line).
- Set `DYSTOPO_CACHE=C:\Users\Dorje\Documents\src\android-app\cache` in your environment so worktrees share downloaded data instead of re-fetching it from USGS and AWS.
- Before pushing: `./gradlew check`, then `git fetch && git rebase origin/main`. Push the branch and open a PR: `gh pr create --fill`. Put measurements, timings, and what you saw in the PNGs in the PR description. Don't commit PNGs.
- Don't edit the Phase 0 log in `docs/PLANNING.md` from a task branch; the integrator adds the log entry when merging. Edit other docs only where the task says so.
- The privacy hooks run in worktrees too (they read `devtool.local.json` from the main checkout). Use only public areas from `areas.json` in anything committed or written up.
- When the task is merged the integrator removes the worktree and deletes the branch (Gradle build dirs exceed Windows path limits for `git worktree remove`; delete with `Remove-Item -LiteralPath \?\<path> -Recurse -Force`, then `git worktree prune`).
- **Never create a junction or symlink inside a worktree that points at the main checkout** (e.g. `mklink /J out ...`). Removing the worktree follows the link and deletes the target's contents; this wiped the main `out/` once. Read shared data by absolute path (`C:\Users\Dorje\Documents\src\android-app\out\...`), or copy the few files you need, or generate into your own `out/`.
- Model per kind of work (for whoever launches agents): the most capable model for planning, docs, review decisions and merges; a strong coding model (Opus-class) for implementing a task and its tests; a mid-tier model (Sonnet-class) for verifying a PR (run `check`, view PNGs, check done criteria) and small fix-ups; the cheapest model (Haiku-class) for mechanical git work (rebase, push, open the PR from a given summary, cleanup). Set the model explicitly on every launch; the default inherits the launcher's, usually the most expensive.

## Coordinating a wave (for the session that launches agents)
What worked across waves 1 to 5 (24 tasks, 24 PRs, one coordinator). Keep it unless there is a reason to change.
- **Unit of work is a task, not a folder.** A task's **Owns:** line in `docs/TASKS.md` partitions the files so agents never collide; a feature usually spans `shared`, `devtool` and tests, so a folder-owner would need a handoff for every feature. Two tasks that must touch the same file go in different waves, or one task owns the file and the other records a follow-up.
- **Roles and models.** Coordinator (most capable model): writes the wave in TASKS.md, launches, decides, merges, writes the Phase 0 log, runs post-wave sweeps. Implementer (Opus-class) per task: builds, tests, debugs end to end, opens the PR. Verifier (Sonnet-class) per PR: rebases if `main` moved, runs `check`, repeats the PR's key runs, views the PNGs, returns MERGE / MERGE WITH NOTES / FIX with specifics. Chores (Haiku-class) for mechanical git work. Always set the model explicitly.
- **Communication is hub and spoke.** Agents report to the coordinator; the coordinator relays. A verifier may message its implementer directly for a small fix-up, but anything that changes scope or ownership goes through the coordinator. Nobody edits another task's files.
- **Wave size.** 5 to 8 implementers at once has been fine; verifiers run as PRs arrive. Stop launching new work when the usage recorder (`~/.claude/usage.json`, written by the status line) shows 75% of either the 5-hour or the 7-day budget; let running agents finish.
- **Merge sequence** (coordinator, main checkout): read the verifier report; `gh pr merge <n> --squash` with a quote-free body (PowerShell 5.1 splits arguments on double quotes); only on success delete the remote branch, fast-forward `main`, remove the worktree, delete local branches; add the dated log entry to `docs/PLANNING.md` and the **Status:** line with follow-ups to the task in `docs/TASKS.md`; commit to `main`. Deleting a branch before the merge succeeded once closed a PR; recover with `gh pr reopen` after recreating the branch at the recorded SHA.
- **Post-wave sweeps** after the last merge of a wave: `./gradlew check`, `tour --zooms 9,12`, `python -I tools/demo-check.py --views-file tools/demo-tour.txt`, and now the Android stand-in (`devtool serve` + `demo-check.py --url http://localhost:8687/`). Findings become the next wave's sweep task (T11a, T19, T23 were these).
- **Privacy at the coordinator too.** Log entries and PR bodies describe personal areas generically; the hooks catch names and coordinates, not paraphrases.

## Rules
- **Never commit personal locations.** No home/travel areas, real GPS tracks, or coordinates that reveal where the developer lives or travels, in code, tests, fixtures, docs, planning logs, PR descriptions, or commit messages. That includes indirect hints: nearby place names, lidar project names, DEM cell/tile names. When logging results from a personal area, describe it generically. Use public places (Rainier, Mont Blanc, Glacier/Waterton, Bonneville) or synthetic data in anything committed.
  - Git hooks in `tools/git-hooks` enforce this against `devtool.local.json` (area names, `privateTerms`, bbox coordinates, DEM cell names). Enable once per clone: `git config core.hooksPath tools/git-hooks`. They need Java (`JAVA_HOME` or `java` on PATH).
  - **Never bypass with `--no-verify`.** If the check fires, fix the content.
- **Every feature must be agent-testable:** unit tests in `shared`, plus a devtool command (later: debug server endpoint) that triggers it and reports results as JSON or PNG.
- **Dependencies must be permissively licensed** (MIT, BSD, Apache-2.0, EPL/EDL). No GPL or AGPL dependencies anywhere; they'd block relicensing. LGPL is tolerated only in build-side tooling modules (`tilebuild`), never in `shared`, `devtool`, or app modules. `./gradlew check` enforces this (`licenseCheck`; config in `gradle/licenses/`, reports in `<module>/build/reports/licenses/`). An artifact whose POM has no license needs a line in `gradle/licenses/artifact-overrides.txt` with its real license. Don't copy code from other projects.
- **Data sources need license and attribution recorded** in `shared/src/commonMain/resources/sources.json` (the registry; see `docs/PLANNING.md` › Licensing). A new source gets a registry entry first; devtool sources declare `registryIds` and set `provenance` on results so reports and tile metadata credit exactly what contributed.
- Commit as you go with descriptive messages. Don't amend pushed commits. Solo work goes straight to `main`; parallel work goes through branches and PRs (above).
