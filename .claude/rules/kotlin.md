---
paths:
  - "**/*.kt"
  - "**/*.kts"
---

# Kotlin conventions

These load when a Kotlin source or build script is read or edited. Module map:
`docs/reference/code-layout.md`; the rules that apply everywhere are in `CLAUDE.md`.

## Platform boundaries

- `shared/src/commonMain` stays pure Kotlin: **no `java.*` imports**, so it compiles for every target
  (JVM and Android now, iOS later). Platform pieces (image decoding, HTTP, zlib, file cache, thread
  pools) come in through small interfaces (`ByteRangeSource`, `Decompressor`) or `expect`/`actual` in
  `Platform.kt`, with the JVM and Android actuals in `src/jvmCommonMain` and the HTTP client in
  `jvmMain`/`androidMain`.
- JVM-only code (image I/O, PNG rendering) lives in `devtool` or `serverlib`; heavy build-side
  dependencies stay in `tilebuild`.
- Dependencies are permissively licensed (MIT, BSD, Apache-2.0, EPL/EDL); LGPL only in `tilebuild`;
  never GPL or AGPL. `./gradlew check` runs `licenseCheck`; an artifact whose POM has no licence needs a
  line in `gradle/licenses/artifact-overrides.txt`. Write our own code; never copy from other projects.

## Geodata

- Every raster goes through `MercatorWindow`, so grids from different sources line up pixel for pixel.
- Elevation is `Float` metres in **EGM2008**; `NaN` means no data. Sources convert their own datum
  before returning.
- Coverage edges between sources are feathered with `Composite.over`; never hard-cut.
- A new data source gets a registry entry in `shared/src/commonMain/resources/sources.json` first
  (licence, attribution, allowed uses); devtool sources declare `registryIds` and set `provenance` on
  results.

## Tests

- Tests in `shared/src/commonTest` run offline and fast. Network-dependent checks are devtool
  commands, not tests.
- Rendering changes are guarded by golden images (`devtool/src/test/resources/goldens/`, synthetic
  terrain only). If `check` fails there and the change is intended: `./gradlew updateGoldens`, look at
  the new PNGs, commit them with the change. Those goldens are the one place PNGs belong in the repo.
- The PNG renderer and the phone style must agree: `StyleParityTest` (devtool tests) renders the Lake
  Cliff golden and evaluates the generated MapLibre style against every feature of the same archive
  (filters, zooms, paint, text, icons, sort keys, draw order, label priority); a new difference fails it
  until it is fixed or added to its explained list with the reason (report in
  `devtool/build/reports/style-parity.txt`).
- PNG text (labels, contact sheets) is drawn with the bundled Noto Sans TTFs
  (`serverlib/src/main/resources/fonts/`, OFL; `RenderFonts`), never the platform `SansSerif`, so
  goldens match on every OS.
- Android: `LocationListenerTest` fails on a `LocationListener` lambda anywhere in the app; use
  `tracking.locationListener {}` (finding `docs/findings/2026-10-11-android-location-listener-lambda.md`).

## Devtool commands and debug routes

- A devtool command prints one JSON object to stdout (agents parse it) and writes PNGs to `out/`. Add
  counters and timings to reports rather than printing logs.
- Every feature is agent-testable: unit tests in `shared`, a devtool command, and on the phone a debug
  route (debug builds only, behind the loopback token).
- The KDoc at the top of a `*Routes.kt` file documents every route it serves; change it with the
  routes. The endpoint summaries in `docs/reference/android-*.md` change in the same PR.
- The track database schema has its own rule (`.claude/rules/trackdb.md`).
