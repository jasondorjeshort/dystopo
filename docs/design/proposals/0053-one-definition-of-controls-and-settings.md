# 0053. One definition of the controls and settings, read by the web page, the phone and a later iOS app

Date: 2026-11-02
Status: draft

## Question

The web page (`tools/demo/index.html` with its trip editor) and the Android app were never designed together. The
owner (2026-11-01) wants "a consistent design between them that put things in generally the same categories and
places. Be that buttons on screen, settings categories, etc.", not a faithful mirror, and names the real cost: keeping
two clients, later three, in step by hand; "something like OpenAPI docs that both stay in sync with". The inventory
beside this proposal (`0053-controls-inventory.md`) lists every control on both clients at one commit. This proposal
decides three things: (a) the **behaviour layer**, the one place that says what each option is (id, type, values,
default, sync key, the platforms that have it); (b) the **placement layer**, the ordered categories and, per item, its
category, order and on-screen zone per platform, with a check in `./gradlew check` that fails when a client shows a
control the definition does not place; (c) whether **strings** (labels, help) belong in the definition. It does not
decide the categories themselves or their wording: those are the owner's (ADR 0031), filled in the inventory's
Category column. In ten years the answer should let a fourth client (a watch, a desktop page) be placed by reading one
definition, keep each platform's own layout and idioms, and make drift a failed check rather than a surprise.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing (`docs/PLANNING.md` ›
  Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL); offline first; every feature agent-drivable
  (devtool command, debug route); agents write all code; the design goals (ADR 0025).
- The web page has no build step: it is one HTML file and two scripts the server sends as they are (and the static demo
  serves from the repository). Whatever it reads must be a file or a route, not a compiled artifact.
- One shared surface already exists: the map options (`MapOptions` in `shared`, `KEYS`, defaults and `parse` with a
  400 on a bad value), used by `/style.json`, the web page's query string and the phone's settings, synced as
  `app.map.<key>`.
- The phone already has a placement registry for its settings sheet (`SettingsGroup` and `SettingsRow` in
  `settings/SettingsLayout.kt`, served as `GET /settings/layout`, kept honest by `SettingsLayoutTest`) and a behaviour
  registry spread over `AppSettings`, `SettingsRoutes.KEYS` and `sync/SettingsSync.kt`. Its on-map controls have no
  registry: each overlay draws its own.
- Cross-client checks that read source text already run in `./gradlew check`: `SlopeLegendTest` and `MarkerTableTest`
  (devtool tests reading `tools/demo/index.html` and the phone's Kotlin), `HelpRegistryTest` (reads the app's sources
  for help keys both ways), `StyleParityTest`. A JSON registry embedded in commonMain also exists:
  `sources.json`, embedded by `:shared:generateSourcesJson` and parsed with the in-house `MiniJson`.
- What users see is built in alpha and adjusted by the owner afterwards (ADR 0031); the categories and the wording are
  the owner's. A synced key's change migrates user data (ADR 0033). No other product's name in user-visible text.
- The web page is also the public site (ADR 0052): a visitor sees the map and its layers; operator tools stay hidden.
- Maintenance is the cost the owner named: every added option today touches up to six places on the phone (key,
  route parse, sync, row, page, help) and, when it is a map option, the page by hand.

## Prior art

From the benchmark survey (ADR 0039; `out/wave26-research/T206-controls-grouping.md` [no-check: gitignored report]),
over Gaia GPS, AllTrails, CalTopo, OsmAnd, Organic Maps, Locus Map, Komoot, Garmin Explore and Avenza Maps. Coverage is
uneven: several help centres refused automated reads, so Gaia GPS, Komoot, AllTrails and Avenza rest on search
summaries, and only OsmAnd's and Garmin's settings lists were established in full.

- **Common practice.** Map appearance (base map, overlays, relief) sits in a layers control on the map or one tap from
  it, not in settings; units, coordinate format and GPS behaviour sit in settings; settings are few groups by concern
  (general, units, GPS, privacy), with appearance and units early and storage or account late. On the map: search,
  layers (a top corner on most phones), locate or recentre (a lower corner), measure, record; zoom buttons are
  optional.
- **Web against phone.** CalTopo and Gaia GPS keep the same names on web and phone ("Layers", "Settings") and the same
  split (layers on the map, units in preferences) with different chrome: side panels on the web, a top icon bar on the
  phone. That is the owner's "same categories and places, not a mirror". Garmin's and Komoot's web versions differ in
  content (layers or units that exist on one side only), which users report as defects.
- **Outliers.** Locus Map and OsmAnd let the user choose which buttons show; OsmAnd splits global from per-profile
  settings. No public source says any surveyed app shares one settings definition between web and phone; the
  cross-client definition has no direct precedent among them.
- **Technical prior art** (well-known projects, not surveyed by an agent; under Claims to verify): OpenAPI 3.1
  describes HTTP operations and their parameters with JSON Schema; JSON Forms and react-jsonschema-form separate a
  data schema (types, values, defaults) from a UI schema (layout, order, widgets), the same two layers as the idea; an
  editor's settings declared in JSON Schema with titles, an `order` and categories that its settings screen renders
  (Visual Studio Code's `contributes.configuration`) is the closest single-client precedent; Android's Preference XML
  and iOS's `Settings.bundle` declare a settings screen as data, each for one platform.
- **Where this departs.** The common practice puts appearance in a layers control on the map; both our clients put it
  in a panel or the settings sheet (the web's Map options section, the phone's Settings › Map options). That is a
  placement question for the owner (decision point D2), not a format question.

## Options

The behaviour layer (A to C) and the placement layer (P1 to P3) are chosen together; strings are S1 to S3. Each says
which of its behaviours would be a user setting (ADR 0044): none of them adds one; the survey's one split (optional
zoom buttons) is decision point D5.

### A. Behaviour as an OpenAPI 3.1 document of the server's query parameters

What it is: a hand-written `openapi.yaml` describing `/style.json`, `/phone`, `/shade/...`, `/contours/...`,
`/print` and `/build` with their query parameters (the map options as parameters with `enum`, `default`, `minimum`),
served at `/openapi.yaml`, and a test that compares its parameters with `MapOptions.KEYS`, the defaults of
`MapOptions()` and the intervals. Placement would ride in `x-` extension keys nothing standard reads.
Meets: the owner's words literally; the public site gains a document third parties and tools read; the map options are
already query parameters with enums and a 400. Misses: the phone's settings are not an HTTP API (the debug
`POST /settings` exists only in debug builds), so 49 of the 57 phone settings items in the inventory and every on-map control fall
outside it; placement in `x-` keys is a private format with an OpenAPI wrapper.
Cost: small (one file, one test, a route); tokens to keep the file current by hand.
Risks: the document drifts on everything the test does not compare; validating the document itself needs a parser
dependency (swagger-parser, Apache-2.0) or none at all.
Exit cost: delete a file and a route; nothing reads it but people and tools.
Moving parts: one YAML file, one route, one test; optionally a parser dependency in a test scope.

### B. Behaviour as a project JSON Schema file, the `sources.json` pattern

What it is: `controls.json` in `shared/src/commonMain/resources/` [no-check: proposed file], a JSON Schema (2020-12
vocabulary) whose properties are the options (`type`, `enum`, `default`, `minimum`, `maximum`, `title`) plus project
keys (`x-sync`, `x-platforms`, `x-category`, `x-order`, `x-zone`), embedded in commonMain by the existing generator
task, parsed with `MiniJson`, served at `/controls.json` for the web page, and checked against the code by tests
(`MapOptions`, `SettingsRoutes.KEYS`, `SettingsSync.current()` keys), as `SourceRegistryMappingTest` checks
`sources.json`.
Meets: one file all three clients and humans read; the owner could edit it directly; standard vocabulary for the
behaviour half; an OpenAPI document can `$ref` its map-option properties later. Misses: the code still defines what a
value does, so every fact lives twice (file and code) with a test between them; the schema subset we interpret is ours
(dependency.md: a reimplemented format answers the checklist), and a general JSON Schema validator in commonMain does
not exist.
Cost: medium: the file (about 150 items), a parser and validator for the subset in commonMain, the mapping tests,
the route. Risks: the subset grows into a schema engine; per-activity keys (`trackProfile.<activity>`,
`readout.<activity>.<entry>`) need pattern properties.
Exit cost: medium: the file is data, the mapping tests and the route go; nothing migrates (no stored data changes).
Moving parts: a resource file, an embed line, a parser, two or three tests, a server route, the page's fetch if it
reads it.

### C. Behaviour as a Kotlin registry in `shared`, generating the JSON and the docs

What it is: a typed registry in commonMain (`Control` data classes: id, kind setting/action/link/display, value type,
choices, default, range, sync key, platforms) that the existing code reads instead of its scattered constants:
`MapOptions` keeps its parse and takes its keys and defaults from the registry, `SettingsRoutes.KEYS` and
`SettingsSync.current()` iterate it. `devtool controls` writes `controls.json` and the reference table from it; a
golden-style test fails when the committed `controls.json` is stale (`./gradlew updateGoldens` refreshes it, as for
the render goldens). The server answers `/controls.json` from the registry; the iOS app reads the registry through
`shared`.
Meets: one source the compiler checks; no fact twice; the phone's six-place chore shrinks because sync and the route
keys iterate the registry; the generated JSON is the "OpenAPI-like" document the owner asked for and can be emitted
as an OpenAPI fragment for the server's parameters at no extra source of truth. Misses: the owner edits a table, not
the registry (an agent transcribes); the web page reads generated data, so its own controls still need a check.
Cost: medium to large: a refactor of `AppSettings`, `SettingsRoutes` and `SettingsSync` onto the registry (the largest
item), the generator command, the staleness test. Risks: a big refactor of the settings code touches every synced
key's path; done in steps (map options first) it stays reviewable.
Exit cost: low to medium: it is code; leaving means inlining constants again.
Moving parts: a Kotlin registry, a devtool command, a generated JSON file in the repository, one staleness test, a
server route.

### P1. Placement as a manifest with a check in `./gradlew check`

What it is: ordered categories (id, title, order) and, per item, its category, order and zone per platform (web:
`panel`, `map-corner`, `trips`, `map`; phone: `settings`, `map-top`, `map-right`, `map-lower-right`, `map-lower-left`,
`card`, `menu`, `screen`), stored with the behaviour layer (B's `x-` keys or C's registry). The check has three parts:
(1) phone settings: every `SettingsRow` names a manifest item and every phone item in zone `settings` has a row
(`SettingsLayoutTest` grows by one assertion); (2) web: a devtool test reads the page's markup and scripts, lists every
interactive element (`input`, `select`, `button`, `summary`, `addControl` calls, elements given a `data-control`
attribute in the scripts) and fails on one the manifest does not place for the web, or a web item the page lacks;
(3) phone on-map controls: each control passes its manifest id to a small helper (`Modifier.control("<id>")`, also a
semantics test tag for the device tour), and a source-reading test like `HelpRegistryTest` checks both ways.
Meets: the owner's check; each client keeps its layout and idioms; the categories are data the owner's table fills.
Limits: part (3) catches a control that names an id the manifest lacks, not a new button that names none; only a
runtime pass (the device tour reading the semantics tree for clickable nodes without a tag, and `demo-check.py` reading
the live DOM) closes that, and it runs in the sweep, not in `check`.
Cost: medium: the manifest (about 150 items from the inventory), three tests, the helper on every on-map control.
Exit cost: low: delete the manifest's placement keys and three tests; the screens are unchanged.
Moving parts: the manifest data, three tests, a Compose modifier, a `data-control` convention on the page.

### P2. Placement as the inventory, refreshed and diffed, with no failing check

What it is: keep `0053-controls-inventory.md` (moved to `docs/reference/`) as the placement record; `devtool controls
--diff` lists controls present in the code and absent from the table; the sweep reports the diff, nothing fails.
Meets: cheapest; no refactor. Misses: the owner asked for a definition both clients stay in sync with; a report nobody
reads drifts like the code did.
Exit cost: none. Moving parts: one command, one doc.

### P3. Placement generated: both clients build their settings screens from the definition

What it is: the phone's settings pages and the web's panel are drawn by a generic renderer walking the definition
(category pages, a widget per value type), as JSON Forms renders a UI schema.
Meets: maximal consistency; a new option needs no screen code. Misses: "not a faithful mirror"; the phone's rows are
not generic (per-activity rows, typed fields with validation, live status lines, actions); the web page gains a
renderer and loses its plainness; on-map controls cannot be generated sensibly.
Exit cost: high: both screens rewritten twice. Moving parts: two renderers.

### S1 to S3. Strings

- **S1. Labels and help in the definition.** One wording per item for every client; the owner edits wording in one
  place. Help texts on the phone are functions of the units and name phone gestures (`HelpText` lambdas), so moving
  them means a template language. Localisation later would key off the same ids.
- **S2. Keys only; strings per platform.** Each platform keeps its strings; the definition carries a label key and a
  help key. Cheapest; the wording drifts as today ("single light" on the web, "Single" on the phone).
- **S3. The short label in the definition, help per platform.** The label (a few words, no units) is one string for
  every client, checked by the placement tests (the page's label text and the phone's row label equal the
  definition's); help stays per platform, keyed by the item's id, with `HelpRegistryTest`'s both-ways check.

## Recommendation

C with P1 and S3, built in steps; the categories, their order, the wording and the map-versus-settings placement wait
for the owner (decision points below).

- Why C: the phone's settings code is where the maintenance cost is, and only a registry the code itself iterates
  removes the six-place chore; a JSON file (B) would add a seventh place and a test between it and the code. The
  generated `controls.json` is the document the owner asked for: the web page and later tools read it, and an OpenAPI
  fragment for the server's query parameters (A's value for the public site) is generated from it, not hand-kept.
- Why P1: it is the check the owner's words require, and its three parts reuse tests that already run. Its gap (a new
  button with no id) is stated, and closed at runtime by the device tour and `demo-check.py` in the sweep.
- Why S3: the label is where the clients visibly disagree today, and one short string per item is cheap to keep;
  help texts depend on units and gestures and stay with each platform.
- First step: the map options only. The registry holds `MapOptions.KEYS` with types, choices and defaults (behaviour),
  and the Map category with zones for both clients (placement); `MapOptions` reads it; the web test and the
  `SettingsLayoutTest` assertion cover the map options. No user-visible change. The second step moves the phone's other
  settings; the third adds the on-map controls' ids; each is a task.
- The owner's table comes first: the manifest's categories are transcribed from the inventory's Category column once
  the owner has filled it.

**Decision points for the owner** (ADR 0031):
- **D1.** The categories and their order, in the inventory's Category column. The survey suggests Map, Units and
  display, Navigation and tracking, Data and storage, Sync and sharing, About and advanced (Map moved ahead of Data).
- **D2.** Map appearance on the map: a Layers control on the map in both clients (the common practice), or the panel
  and the settings sheet as today.
- **D3.** The web panel's sections under the same category names as the phone's settings.
- **D4.** One short label per item for both clients (S3), and whose wording wins where they differ today.
- **D5.** Optional on-map buttons (zoom, position) as a setting, as the survey suggests (ADR 0044: apps split).

## What would change this recommendation

- The iOS app is built without `shared` (pure Swift): the definition must be a file every client reads; B over C.
- The owner wants to edit categories and labels in a file, not a table: B over C, keeping P1 and S3.
- A third party builds against the public site's API: A's document becomes a published contract, versioned on its own.
- The registry refactor of the settings code proves larger than two tasks: stop after the map options, keep P1, and
  check the phone's other settings against a list rather than iterate it.
- The owner wants a mirror after all: P3 for the settings screens only.
- Six months of the check catching nothing: P2 is enough.

## Claims to verify

- `tools/demo/index.html`, `trips.js` and `trip-sync.js` are sent without a build step (`LiveTiles.handler`, the
  standalone server's `ServerConfig`, `tools/demo-server.py`).
- `SlopeLegendTest` and `MarkerTableTest` read `tools/demo/index.html` and phone sources from a devtool test and run in
  `./gradlew check`; `HelpRegistryTest` reads the app's sources both ways and runs in `check`.
- `sources.json` is embedded by `:shared:generateSourcesJson` (`shared/build.gradle.kts`) and parsed by `MiniJson`
  with no JSON dependency in commonMain.
- `SettingsRoutes.KEYS`, `AppSettings` and `SettingsSync.current()` are the three places a phone setting's key lives;
  `routeSnapM`, `navStaleS`, `navSnoozeMin`, `warmup` and `server` are not synced (inventory, section 3).
- OpenAPI 3.1 aligns its schema objects with JSON Schema 2020-12; JSON Forms and react-jsonschema-form separate data
  and UI schemas; Visual Studio Code renders settings from `contributes.configuration` with an `order` key (each
  project's public documentation).
- The survey's statements, each with its source in the report; the ones resting on search summaries are marked there.
- The inventory's counts against the files at the pinned commit.

## Critic's objections and responses

(none yet)
