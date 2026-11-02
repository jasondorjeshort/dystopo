# 0053. One definition of the controls and settings, read by the web page, the phone and a later iOS app

Date: 2026-11-02
Status: under review

## Question

The web page (`tools/demo/index.html` with its trip editor) and the Android app were never designed together. The
owner (2026-11-01) wants "a consistent design between them that put things in generally the same categories and
places. Be that buttons on screen, settings categories, etc.", not a faithful mirror, and names the real cost: keeping
two clients, later three, in step by hand; "something like OpenAPI docs that both stay in sync with". The inventory
beside this proposal (`0053-controls-inventory.md`) lists every control on both clients at one commit. This proposal
decides three things: (a) the **behaviour layer**, the one place that says what each shared option is (id, type,
values, default, sync key, the platforms that have it); (b) the **placement layer**, the ordered categories and, per
item, its category, order and on-screen zone per platform, with a check in `./gradlew check` that fails when a client
shows a control the definition does not place; (c) whether **strings** (labels, help) belong in the definition. It
does not decide the categories themselves or their wording: those are the owner's (ADR 0031), adjusted after the build
through the inventory's Category column. In ten years the answer should let a fourth client (a watch, a desktop page)
be placed by reading one definition, keep each platform's own layout and idioms, and make drift a failed check rather
than a surprise.

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing (`docs/PLANNING.md` ›
  Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL); offline first; every feature agent-drivable
  (devtool command, debug route); agents write all code; the design goals (ADR 0025). PLANNING's hard rule 2: what
  clients exchange must be usable by a future iPhone app whatever its code, so a cross-client definition must exist as
  a document, not only as Kotlin.
- The web page has no build step: one HTML file and two scripts the servers send as they are (`serve --live` reads
  them from disk and inserts one `window.DYSTOPO_LIVE` script per request, `injectLive` in `LiveTiles.kt`; the
  standalone server copies them into `web/`; the static demo serves the repository tree). Whatever it reads must be a
  file, a route or that injected script.
- One shared surface already exists: the map options (`MapOptions` in `shared`, `KEYS`, defaults and `parse` with a
  400 naming the key), used by `/style.json`, the web page's query string and the phone's settings, synced as
  `app.map.<key>`.
- The phone already has a placement registry for its settings sheet (`SettingsGroup` and `SettingsRow` in
  `settings/SettingsLayout.kt`, served as `GET /settings/layout`, kept honest by `SettingsLayoutTest`). A phone
  setting lives in ten places: the `AppSettings` key and property, `SettingsRoutes.KEYS`, a field of
  `SettingsRoutes.Parsed.Ok`, a branch of `SettingsRoutes.apply`, a constant, a `current()` line and a `publish()` line
  in `sync/SettingsSync.kt`, a `SettingsRow`, a case in `SettingsPages`, and a `HelpText`; plus two test lists
  (`SettingsLayoutTest`'s expected rows, `HelpRegistryTest.theFirstPlacementsAreAllThere`). Its on-map controls have
  no registry: each overlay draws its own.
- An item's id is a stored and synced key: the phone stores a setting under its key and syncs it as `app.<key>`
  (`SettingsSync.syncedKey`); renaming one is a data migration (ADR 0033).
- The phone's help texts are keyed in their own namespace (`map.*`, `settings.*`, `nav.*`, `longPress.*`,
  `storage.*`), declared beside each control and checked both ways by `HelpRegistryTest`.
- Cross-client checks that read source text already run in `./gradlew check`: `SlopeLegendTest` and `MarkerTableTest`
  (devtool tests reading `tools/demo/index.html` and the phone's Kotlin with regexes), `HelpRegistryTest`,
  `StyleParityTest`. A JSON registry embedded in commonMain exists: `sources.json`, embedded by
  `:shared:generateSourcesJson` and parsed with the in-house `MiniJson`; commonMain has no JSON library, while
  `serverlib`, `server` and `devtool` depend on kotlinx-serialization-json.
- What users see is built in alpha and adjusted by the owner afterwards (ADR 0031); the categories and the wording are
  the owner's. No other product's name in user-visible text (`tools/user-text-check.py` scans `shared`'s sources, so
  labels moved there stay under the rule).
- The web page is also the public site (ADR 0052): a visitor sees the map and its layers; operator tools stay hidden.
- What the inventory found: the web page has 62 controls, the phone 245 (57 on the settings sheet, 188 on the map, its
  cards, menus, sheets and screens); 19 items exist on both (section 1 lists 25 rows, six of them one-sided), most of
  them map options already defined once in `shared`. Most of the phone's 188 are actions inside a flow (a route card's
  Go, a pack row's Delete), which the idea leaves to each platform. Settings also live outside the sheet: the trip
  corridor width (synced) only on the Offline areas screen, the cache budgets on the Storage screen, the sync server
  and its token on the Sync status sheet (two address fields for what is usually one server), and the map's
  orientation on the lower right buttons.

## Prior art

From the benchmark survey (ADR 0039; `out/wave26-research/T206-controls-grouping.md` [no-check: gitignored report]),
over Gaia GPS, AllTrails, CalTopo, OsmAnd, Organic Maps, Locus Map, Komoot, Garmin Explore and Avenza Maps. Coverage is
uneven: several help centres refused automated reads, so Gaia GPS, Komoot, AllTrails and Avenza rest on search
summaries; of the settings lists, only OsmAnd's global settings and Garmin's map settings were established, each a
subset of the app's settings.

- **Common practice on the map** (seven apps cited). Map appearance (base map, overlays, relief) sits in a layers
  control on the map or one tap from it, not in settings. On the map: search, layers (a top corner on most phones),
  locate or recentre (a lower corner), measure, record; zoom buttons are optional.
- **Common practice in settings** (three partial lists, so weaker). Units, coordinate format and GPS behaviour sit in
  settings, in a few groups by concern; appearance and units early, storage or account late.
- **Web against phone.** CalTopo and Gaia GPS keep the same names on web and phone ("Layers", "Settings") and the same
  split (layers on the map, units in preferences) with different chrome: side panels on the web, a top icon bar on the
  phone. That is the owner's "same categories and places, not a mirror". Garmin's and Komoot's web versions differ in
  content: Garmin's web contours stay in feet when the units change (forum reports), and Komoot keeps two base maps
  on its website only.
- **Outliers.** Locus Map and OsmAnd let the user choose which buttons show; OsmAnd splits global from per-profile
  settings. No public source says any surveyed app shares one settings definition between web and phone; the
  cross-client definition has no direct precedent among them.
- **Technical prior art** (checked by the critic against the primary pages): OpenAPI 3.1's Schema Object is a superset
  of JSON Schema 2020-12, and its `x-` extensions "may or may not be supported by the available tooling"; JSON Forms
  and react-jsonschema-form separate a data schema (types, values, defaults) from a UI schema (order, visibility,
  layout), the same two layers as the idea; Visual Studio Code declares settings in "a superset of JSON Schema" with
  category titles and an integer `order` its settings screen renders, the closest single-client precedent; Android's
  Preference XML and iOS's `Settings.bundle` (`PreferenceSpecifiers`) declare a settings screen as data, each for one
  platform.
- **Where this departs.** The common practice puts appearance in a layers control on the map; both our clients put it
  in a panel or the settings sheet (the web's Map options section, the phone's Settings › Map options). That is a
  placement question for the owner (D2), not a format question.

## Options

The behaviour layer (A to C, and C2) and the placement layer (P1 to P3) are chosen together; strings are S1 to S3.
None of them adds a user setting (ADR 0044); the survey's one split (optional zoom buttons) is D5.

### A. Behaviour as an OpenAPI 3.1 document of the server's query parameters

What it is: a hand-written `openapi.yaml` describing `/style.json`, `/phone`, `/shade/...`, `/contours/...`,
`/print` and `/build` with their query parameters (the map options as parameters with `enum`, `default`, `minimum`),
served at `/openapi.yaml`, and a test that compares its parameters with `MapOptions.KEYS`, the defaults of
`MapOptions()` and the intervals. Placement would ride in `x-` extension keys nothing standard reads.
Meets: the owner's words literally; the public site gains a document third parties and tools read; the map options are
already query parameters with enums and a 400. Misses: the phone's settings are not an HTTP API (the debug
`POST /settings` exists only in debug builds), so 49 of the 57 phone settings items in the inventory and every on-map
control fall outside it; placement in `x-` keys is a private format with an OpenAPI wrapper (the specification's own
words on extensions).
Cost: small (one file, one test, a route); tokens to keep the file current by hand.
Risks: the document drifts on everything the test does not compare; validating the document itself needs a parser
dependency (swagger-parser, Apache-2.0, OpenAPI 3.1 since 2.1.0) or none at all.
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
value does, so every fact lives twice (file and code) with a test between them; the schema subset commonMain
interprets is ours (dependency.md: a reimplemented format answers the checklist). A test-scope JSON Schema validator on
the JVM (devtool already has kotlinx-serialization-json) was not evaluated against `dependency.md`; it would check the
file, not replace the subset commonMain reads.
Cost: medium: the file, a parser and validator for the subset in commonMain, the mapping tests, the route. Risks: the
subset grows into a schema engine; per-activity keys (`trackProfile.<activity>`, `readout.<activity>.<entry>`) need
pattern properties.
Exit cost: medium: the file is data, the mapping tests and the route go; nothing migrates (no stored data changes).
Moving parts: a resource file, an embed line, a parser, two or three tests, a server route, the page's fetch if it
reads it.

### C. Behaviour as a Kotlin registry in `shared` for every setting, generating the JSON and the docs

What it is: a typed registry in commonMain (`Control` data classes: id, kind setting/action/link/display, value type,
choices, default, range, sync key, platforms) that the existing code reads instead of its scattered constants:
`MapOptions` keeps its parse and takes its keys and defaults from the registry, `SettingsRoutes.KEYS` and
`SettingsSync.current()` and `publish()` iterate it. `devtool controls` writes `controls.json` and the reference table
from it; a golden-style test fails when the committed `controls.json` is stale. The server answers `/controls.json`
from the registry; the iOS app reads the registry through `shared` or the generated file.
Meets: one source the compiler checks; the generated JSON is the "OpenAPI-like" document the owner asked for and can
be emitted as an OpenAPI fragment for the server's parameters. Misses: for the web, C is B: the page reads only
generated JSON, and a test sits between it and the page. C's own gain is phone-internal: of a setting's ten places it
removes the `KEYS`, `current()` and `publish()` lines; the `Parsed.Ok` field, the `apply` branch, the row, the page
case, the help text and the test lists stay unless `Parsed.Ok` becomes a map, a further refactor not included here.
The owner edits a table, not the registry.
Cost: medium to large: a refactor of `AppSettings`, `SettingsRoutes` and `SettingsSync` onto the registry, the
generator command, the staleness test. Risks: B's risk, unchanged: the phone's settings are not uniform values
(`turnDeadline` is `sunset | civil | terrain | HH:MM`, `units` accepts several spellings, the per-activity composites
have their own encoders), so a `Control` either stays lossy, and the registry is documentation with a test, or grows
kinds and branches into a schema engine with three interpreters; and a refactor of every synced key's path.
Exit cost: low to medium: inline the constants again in `SettingsRoutes` and `SettingsSync`, delete the generated file,
its test, the devtool command and the route, the page's read of the document, the `data-control` attributes and the
Compose helper on the phone's persistent controls.
Moving parts: a Kotlin registry, a devtool command, a generated JSON file in the repository, one staleness test, a
server route.

### C2. C narrowed to the shared surface (the critic's alternative)

What it is: the registry in `shared` holds only what two clients share today (the map options, units, text scale, the
scale bar's both-units key, the position and orientation controls, print, the trip flow's entry points) and gains an
item only when a second client gains it. Phone-only settings keep `AppSettings`, `SettingsRoutes` and `SettingsSync`
as they are and are placed in the manifest by their existing `SettingsRow.id`, with no behaviour refactor. The
registry generates the committed `tools/demo/controls.json` [no-check: proposed file], which is the cross-client
contract (the iOS client and the web read it; PLANNING's hard rule 2); `serve --live` also inlines it through
`injectLive` as `window.DYSTOPO_CONTROLS`, so the live page needs no fetch, and the static demo fetches it relatively;
the standalone server copies it into `web/` with the page.
Meets: every benefit C claims for the owner's question; no fact twice for the shared surface; no refactor of the
phone-only settings; the document stays small (about twenty items now). Misses: the phone's ten-place chore for
phone-only settings is untouched (re-filed as its own idea).
Cost: medium: the registry for the shared items (the map options read their keys and defaults from it; `MapOptions`
keeps its parse), the generator command, the staleness test, the inlining in `injectLive` and the standalone copy.
Risks: B's risk scaled down: the shared values are enums, booleans, bounded numbers and the interval list per unit;
a shared item whose values the registry cannot express keeps its parse in code and the registry records only its id,
kind and placement, never a second copy of its values (a stated rule, not a new kind).
Exit cost: low: delete the registry's shared items (the map options take their constants back), the generated file and
its test, the command, the `injectLive` line and the copy, the page's read, the `data-control` attributes and the
Compose helper.
Moving parts: a small Kotlin registry, a devtool command, a generated file in `tools/demo/`, one staleness test, one
injected script line, one copy in the standalone build.

### P1. Placement as a manifest with a check in `./gradlew check`

What it is: ordered categories (id, title, order) and, per item, its category, order and zone per platform (web:
`panel`, `map-corner`, `trips`, `map`; phone: `settings`, `map-top`, `map-right`, `map-lower-right`, `map-lower-left`,
`card`, `menu`, `screen`; the zone vocabulary is per platform and versioned with the document, so iOS zones are a
schema change when they come), stored with the behaviour layer (the registry for shared items, `SettingsRow.id`
references for phone-only ones). What it places: every stored option wherever its screen is, the controls that stay
on the map (top row, chip column, search, lower right, lower left; the web's panel sections, Trips box and map
corners), the entry points to screens and the long-press menu's entries; about 110 items. It does not place actions
inside a flow (a card's Go, GPX or Delete, a list row's buttons, a screen's own fields): the idea leaves flows to each
platform. The check has three parts:
1. Phone settings: every `SettingsRow` id is a manifest item and every phone item in zone `settings` has a row
   (`SettingsLayoutTest` gains one assertion).
2. Web: every placed element carries `data-control="<id>"`; a flow action carries `data-control="flow:<parent-id>"`,
   or sits inside an element that does (the Trips box's saved-trip and waypoint rows mark their `<li>`). A devtool test
   reads `index.html`, `trips.js` and `trip-sync.js`: elements in the markup and in template strings by their tags
   (`input`, `select`, `button`, `summary`), elements made in script at each `createElement("button" | "input" |
   "select")` call (the attribute is set on the next lines, which the test reads), and `addControl(new maplibregl.X)`
   calls, whose id the manifest maps from the class name. It fails on an interactive element with neither attribute,
   on a `data-control` id the manifest lacks, and on a manifest web item the page lacks. `demo-check.py` repeats the
   first rule on the live DOM in the sweep.
3. Phone on-map controls: each persistent control and entry point passes its id to a helper
   (`Modifier.control("<id>")`, also a semantics test tag for the device tour), and a source-reading test like
   `HelpRegistryTest` checks both ways.
Refusal messages, each naming what lifts it (any implementer, in its own task's files, while the session runs):

| Check | The message names |
|---|---|
| Stale generated document | the generated file and `./gradlew updateGoldens` (the document is wired into it), then view the diff |
| A row with no item, or a settings item with no row | the row id or item id, `SettingsLayout.kt` and the manifest's file |
| A web element with no attribute | the element, its file and line, and both fixes: `data-control="<id>"` with a manifest item, or `data-control="flow:<parent-id>"` |
| A web id the manifest lacks, or a web item the page lacks | the id, the file and line or the manifest entry, and the edit on either side |
| A phone control id the manifest lacks, or an item no control names | the id, the file and line or the manifest entry, and the edit on either side |

Meets: the owner's check; each client keeps its layout and idioms; the categories are data. Limits: part 3 catches a
control that names an id the manifest lacks, not a new button that names none; the device tour reading the semantics
tree for clickable nodes without a tag closes that, in the sweep, not in `check`. The source-reading tests are regex
readers; each fails with a message naming the pattern it could not find, not a parse error.
Cost: medium: the manifest, three tests, the helper on the phone's 30 or so persistent controls and entry points, the
attributes on the page. A debug route `GET /controls` on the phone's debug port lists the placed ids with zone and
whether each is on screen now (as `GET /help` does for help keys); the web's is `/controls.json`.
Exit cost: low: delete the manifest's placement data, three tests, the attributes, the helper and the route; the screens
are unchanged.
Moving parts: the manifest data, three tests, a Compose modifier, a `data-control` convention, one debug route.

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
  them means a template language, and re-keys about sixty help texts. Exit cost: high (the help texts move back to
  their screens). Moving parts: a template language, every screen's help call.
- **S2. Keys only; strings per platform.** The definition carries each platform's existing label and help key.
  Cheapest; the wording drifts as today ("single light" on the web, "Single" on the phone). Exit cost: none. Moving
  parts: none beyond the references.
- **S3. The short label in the definition, help per platform.** The label (a few words, no units) is one string for
  every client, checked by the placement tests (the page's label text and the phone's row label equal the
  definition's). Help stays per platform under each platform's existing key: the manifest carries the reference
  (`help.android: "map.hillshade"`), and `HelpRegistryTest` gains one assertion that every referenced key is declared;
  no help key is renamed. A per-platform label is allowed by name, as an override with a reason in the manifest that
  the label test lists instead of failing on, for wording the owner wants different on one client. Exit cost: low but
  not none: labels once unified either stay so or drift again, and the label test goes. Moving parts: a label per
  item, one assertion in each placement test, one in `HelpRegistryTest`.

## Recommendation

C2 with P1 and S3, built now with today's categories; the owner adjusts the categories, their order, the wording and
the map-versus-settings placement afterwards (ADR 0031).

- Why C2: the owner's cost is cross-client drift, and the cross-client surface is nineteen items, most of them map
  options already defined once in `shared`. A registry of exactly that surface is the contract the owner asked for and
  the document PLANNING's hard rule 2 requires; C's refactor of the phone-only settings buys nothing across clients and
  carries B's schema-engine risk. The phone-internal clean-up of a setting's ten places is re-filed as its own idea
  (`docs/ideas/2026-11-02-phone-settings-registry.md` [no-check: added on this branch]).
- Why P1: it is the check the owner's words require; part 2's `data-control` convention gives every web element a
  place or a declared flow, so the check is neither hollow nor a refusal without a lift; parts 1 and 3 reuse patterns
  that already run.
- Why S3: the label is where the clients visibly disagree today, and one short string per item is cheap to keep; help
  texts depend on units and gestures and stay under each platform's keys.
- Ids: an item's id is its existing phone settings key (`textScale`, `hillshade`, `scaleBar.both`); a web id (`text`,
  `hill`, `unitsel`) maps to it in the manifest; no stored or synced key is renamed for the manifest's sake. A rename,
  if ever wanted, is its own task under ADR 0033 with a migration.
- Categories: the first build uses today's six phone groups (`SettingsGroup`: Data and storage, Map options, Units and
  display, Navigation and tracking, Sync and sharing, About and advanced) as placeholder categories, with the web's
  items placed in them (its Map options section in Map options, Units and Text size in Units and display, the
  operator tools in Data and storage). The owner's Category column re-orders and renames afterwards; an agent
  transcribes it into the manifest.
- Build steps, each a task: (1) the map options: the registry holds `MapOptions.KEYS` with types, choices and
  defaults, `MapOptions` reads them, the generated document, P1 parts 1 and 2 for the map options and the web page's
  `data-control` attributes, no user-visible change; (2) the rest of the shared surface and every placed item of both
  clients, P1 part 3 with the Compose helper and `GET /controls`; (3) the owner's adjustments D1 to D6 as they come.

**Adjustments for the owner after the build** (ADR 0031; none is a precondition):
- **D1.** The categories and their order, in the inventory's Category column. The survey agent proposes Map, Units and
  display, Navigation and tracking, Data and storage, Sync and sharing, About and advanced (Map ahead of Data); that
  is the agent's recommendation, not an observed practice.
- **D2.** Map appearance on the map: a Layers control on the map in both clients (the common practice), or the panel
  and the settings sheet as today.
- **D3.** The web panel's sections under the same category names as the phone's settings.
- **D4.** One short label per item for both clients (S3), and whose wording wins where they differ today.
- **D5.** Optional on-map buttons (zoom, position) as a setting, as the survey suggests (ADR 0044: apps split).
- **D6.** Settings the phone keeps outside the sheet (the trip corridor width, the cache budgets, the sync server and
  its token): placed in the sheet's categories as rows or links, or left on their screens with the manifest naming
  them there. The two address fields on the phone (Sources, and the sync peer) for what is usually one server are a
  flow change for the owner, outside the format question.

## What would change this recommendation

- The iOS app is built without `shared` (pure Swift): it reads the generated document, which C2 already commits; if
  that proves awkward, B over C2.
- The owner wants to edit categories and labels in a file, not a table: B over C2, keeping P1 and S3.
- A third party builds against the public site's API: A's document becomes a published contract, versioned on its own.
- A second client gains most of the phone's settings (a full web settings page): C's wider registry, with its risk.
- The owner wants a mirror after all: P3 for the settings screens only.
- Drift the check missed: a verifier or the sweep finds a placed control the manifest does not name, or two clients
  disagree on a label the registry holds. Two such findings in a year mean P1's rules need widening; none, while the
  ids keep being added, means the check is doing its job (a check that never fails is not by itself a reason for P2).

Predictions for the ADR, dated: (1) the first iOS task reads the generated document or the registry unchanged, with
no format change; (2) the manifest's first three tasks rename no stored or synced key; (3) by 2027-05-01 the registry
holds fewer than forty items, because it grows only with a second client.

## Claims to verify

- `tools/demo/index.html`, `trips.js` and `trip-sync.js` are sent without a build step; `injectLive` inserts one
  script per request (`LiveTiles.kt`); the standalone build copies the files into `web/` (`server/build.gradle.kts`).
- `SlopeLegendTest` and `MarkerTableTest` read `tools/demo/index.html` and phone sources from a devtool test and run in
  `./gradlew check`; `HelpRegistryTest` reads the app's sources both ways and runs in `check`.
- `sources.json` is embedded by `:shared:generateSourcesJson` (`shared/build.gradle.kts`) and parsed by `MiniJson`
  with no JSON dependency in commonMain.
- A phone setting's ten places and two test lists (Constraints); `routeSnapM`, `navStaleS`, `navSnoozeMin`, `warmup`
  and `server` are not synced (inventory, section 3).
- `SettingsSync.syncedKey` builds the synced key from the settings key, so a renamed id is a migration.
- `./gradlew updateGoldens` exists and can take one more generated file (`devtool/build.gradle.kts`).
- The technical prior art as quoted (the critic's review, claim 5, with the pages read).
- The survey's statements, each with its source in the report; the ones resting on search summaries are marked there.
- The inventory's counts against the files at the pinned commit; 19 items on both clients.

## Critic's objections and responses

The critic's review: `0053-critic-review.md` (0 blocking, 5 serious, 10 minor; the shape upheld, the scope narrowed).
The coordinator decided the five serious items; the ten minor ones are taken as the review resolves them.

1. **Serious: C's refactor of the phone-only settings has no cross-client payoff and carries B's schema-engine risk.**
   Response: the case holds; for the web C is B, and C's own gain is three of a setting's ten places. Resolution:
   accepted with change. Recommendation is C2 (added as an option, C kept as the record with B's risk carried to it);
   phone-only items are placed by `SettingsRow.id` with no behaviour refactor; the phone-internal second step is
   dropped and re-filed as an idea (`2026-11-02-phone-settings-registry.md`).
2. **Serious: P1 part 2 has no exemption rule for the web's flow actions.** Resolution: accepted. `data-control="<id>"`
   and `data-control="flow:<parent-id>"`; the test fails on an interactive element with neither and its message names
   the element, the file and line and both fixes; dynamic elements are found at their `createElement` calls and
   `addControl` calls, and `demo-check.py` repeats the rule on the live DOM (P1, part 2).
3. **Serious: "the owner's table comes first" gates the build on the owner, against ADR 0031.** Resolution: accepted.
   Today's six phone groups are the placeholder categories; the owner's Category column re-orders and renames
   afterwards; D1 to D6 are post-build adjustments, not preconditions (Recommendation).
4. **Serious: no rule against renaming ids that are sync and preference keys.** Resolution: accepted. An item's id is
   its existing phone key, web ids map to it in the manifest, no stored or synced key is renamed; a rename would be its
   own ADR 0033 task (Recommendation, Ids).
5. **Serious: S3 silently re-keys the help registry.** Resolution: accepted. The manifest carries each platform's
   existing help key as a reference; `HelpRegistryTest` gains one assertion; no re-keying (S3).
6. **Minor: "25 items exist on both" overcounts by six.** Resolution: accepted. 19 in the Constraints and the
   inventory; the inventory's section 1 marks its six one-sided rows.
7. **Minor: "three" and "six places" undercount.** Resolution: accepted. Ten places and two test lists are listed in
   Constraints; C states which three it removes and that `Parsed.Ok` as a map is excluded.
8. **Minor: S1 to S3 have no exit cost or moving parts.** Resolution: accepted; added, S3's exit stated as low, not none.
9. **Minor: C's exit cost is understated.** Resolution: accepted; C's and C2's exit lines list every part.
10. **Minor: no refusal message names its lift.** Resolution: accepted; P1 carries a table of each check's message and
    lift.
11. **Minor: Prior art over-reads the survey.** Resolution: accepted. "OsmAnd's global settings and Garmin's map
    settings"; the on-map lines marked as seven cited apps and the settings lines as three partial lists; D1 says the
    survey agent proposes.
12. **Minor: where the committed `controls.json` lives, how the static page reaches it, iOS zones.** Resolution:
    accepted. `tools/demo/controls.json`, inlined by `injectLive` in live mode, fetched relatively by the static demo,
    copied by the standalone build; the zone vocabulary is per platform and versioned (C2, P1).
13. **Minor: B under-explored on validation.** Resolution: accepted; B says a test-scope JVM validator was not
    evaluated and would not replace commonMain's subset. The outcome does not rest on it.
14. **Minor: no phone debug route for the manifest.** Resolution: accepted; `GET /controls` on the debug port, in build
    step 2 (P1).
15. **Minor: the exit condition is ambiguous.** Resolution: accepted; reworded to drift the check missed, and three
    dated predictions for the ADR.

The critic's residual risks are answered in the body: regex readers fail with a message naming the pattern (P1); the
zone vocabulary is versioned for iOS (P1); D2 may move items whose layout code is still written by hand (the manifest
records the place, the page and the app implement it); a per-platform label is allowed by name as an override (S3).
