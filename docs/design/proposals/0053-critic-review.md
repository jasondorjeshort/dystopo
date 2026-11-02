# 0053. Critic review: one definition of the controls and settings

Date: 2026-11-02
Reviewed: `0053-one-definition-of-controls-and-settings.md` and `0053-controls-inventory.md` at `task/T206` head
`0df27be6`; read-only (no Gradle run, no server, no emulator). Files read in the brief's order: `CLAUDE.md`, the
proposal, the inventory, the idea, ADR 0031, 0039, 0044, 0022 (and 0025, 0033, 0052), `docs/PLANNING.md` (Licensing,
Design goals, Units), `tools/demo/index.html`, `trips.js`, `trip-sync.js`, `serverlib/.../ServeMapOptions.kt`,
`LiveTiles.kt`, `server/.../ServerConfig.kt`, `Main.kt`, `server/build.gradle.kts`, `tools/demo-server.py`,
`shared/.../maplibre/MapOptions.kt`, `<app>/settings/SettingsLayout.kt` and its test, `<app>/AppSettings.kt`,
`<app>/SettingsRoutes.kt`, `<app>/sync/SettingsSync.kt`, `<app>/help/Help.kt` and `HelpRegistryTest`,
`SlopeLegendTest`, `MarkerTableTest`, `StyleParityTest`, `SourceRegistryMappingTest`, `shared/build.gradle.kts`,
`sources.json`, `MiniJson.kt`, `tools/user-text-check.py`, the survey `out/wave26-research/T206-controls-grouping.md`
[no-check: gitignored report], and the public documentation named under Claims. `<app>` is the inventory's
abbreviation for the Android app's source folder.

Verdict in one line: the recommendation's shape (a registry in `shared`, a placement manifest with a failing check,
one short label) is right; its scope is too wide on the phone, its web check has no exemption rule, and its first
step is gated on the owner against ADR 0031. 0 blocking, 5 serious, 10 minor.

## Claims to verify

1. **The web page is sent without a build step.** Verified, with one nuance. `LiveTiles.handle` reads
   `tools/demo/index.html` from disk on every request and inserts one `<script>` (`window.DYSTOPO_LIVE = {...}`)
   before the page's module script (`injectLive`, LiveTiles.kt lines 858 to 860 and 911 to 916); `trip-sync.js` is
   read from disk beside the page (lines 812 to 815), `trips.js` through `RouteRoutes`. The standalone server copies the
   three files unchanged into `web/` (`server/build.gradle.kts` lines 30 to 32) and `Main.kt` (line 165) picks
   `web/index.html`, else `tools/demo/index.html`. `tools/demo-server.py` is Python's `SimpleHTTPRequestHandler` with
   Range support over the repository root. So there is no build step, but there is one server-side injection point,
   which matters for option C: the generated document could be inlined there, sparing the page a fetch (minor 12).
2. **Source-reading tests in `check`.** Verified. `SlopeLegendTest` and `MarkerTableTest` are devtool unit tests
   reading `../tools/demo/index.html` (and `MapOptionsSection.kt`) with regexes over `const SLOPE_BANDS = {`,
   `const MARKERS = {` and so on; `HelpRegistryTest` walks `src/main/kotlin` for `HelpIcon("…")`, `help = "…"` and
   `HelpText("…")` and checks both directions. Gradle's `check` depends on each module's `test` by default;
   `build.gradle.kts` adds `userTextCheck` to it (line 43). Note for P1: these tests are regex readers of source
   text, which is a proven but brittle pattern here (a renamed constant fails them with a null-pointer rather than a
   message).
3. **`sources.json` embedded, `MiniJson`, no JSON dependency in commonMain.** Verified. `shared/build.gradle.kts`
   lines 14 to 60 generate `SourcesJson.kt` (and two sibling constants) from the resource; `MiniJson` says in its KDoc
   it exists "so `shared` doesn't need a JSON library"; `shared/build.gradle.kts` declares no serialization plugin.
   Nuance the proposal misses: `serverlib` (`api(libs.kotlinx.serialization.json)`), `server` and `devtool` do depend
   on kotlinx-serialization-json, so a JVM-side validator of a JSON document is available to a devtool test; only
   commonMain is bare (minor 13).
4. **Three places a phone key lives; five keys not synced.** The five keys are verified: `routeSnapM`, `navStaleS`,
   `navSnoozeMin`, `warmup` and `server` appear nowhere in `SettingsSync.kt`; `current()` (lines 130 to 160) and
   `publish()` (lines 200 to 234) list the synced keys by hand, one line each. The "three places" undercounts: a
   setting also has a field in `SettingsRoutes.Parsed.Ok`, a branch in `SettingsRoutes.apply`, a constant plus a
   `current()` line plus a `publish()` line in `SettingsSync`, a `SettingsRow`, a `SettingsRow.X ->` case in
   `SettingsPages`, a `HelpText`, and an entry in two test lists (`SettingsLayoutTest.AFTER_T103` with its expected
   label list, `HelpRegistryTest.theFirstPlacementsAreAllThere`). Ten places, not six (minor 7).
5. **Technical prior art.** All verified against the primary pages.
   - OpenAPI 3.1.0, section 4.8.24: the Schema Object "is a superset of the JSON Schema Specification Draft 2020-12",
     dialect `https://spec.openapis.org/oas/3.1/dialect/base`; section 4.9: extension fields "are always prefixed by
     `x-`" and "may or may not be supported by the available tooling" (spec.openapis.org/oas/v3.1.0.html). So A's
     "placement in `x-` keys is a private format with an OpenAPI wrapper" is a fair reading of the spec itself.
   - JSON Forms: "the data/JSON schema defines the underlying data to be shown in the UI (objects, properties, and
     their types)"; "the UI schema defines how this data is rendered as a form, e.g. the order of controls, their
     visibility, and the layout" (jsonforms.io/docs). Two layers, as the proposal says.
   - react-jsonschema-form: generates a form "based on a JSON Schema" with `uiSchema` among the "tools … to customize
     the look and feel"; Apache-2.0 (rjsf-team.github.io/react-jsonschema-form/docs). The ordering keyword
     (`ui:order`) is in its uiSchema reference, not on the page read.
   - Visual Studio Code `contributes.configuration`: "Configuration keys are defined using a superset of JSON
     Schema"; "Both categories and the settings within those categories can take an integer `order` type property";
     "Properties without an explicit `order` field will appear in lexicographical order"; "The `title` of a category
     is the heading used for that category" (code.visualstudio.com/api/references/contribution-points).
   - Android: a settings screen "contains a `Preference` hierarchy. You can define this hierarchy as an XML
     resource, or you can build a hierarchy in code" (developer.android.com, Settings). iOS: `Settings.bundle` with
     `Root.plist`, `PreferenceSpecifiers` "an array of dictionaries, with each dictionary containing the information
     for a single control", `PSGroupSpecifier`, `PSToggleSwitchSpecifier` (Apple's Preferences and Settings guide).
   - swagger-parser: Apache-2.0; "Since version 2.1.0 Swagger Parser supports OpenAPI 3.1" (github.com/swagger-api/
     swagger-parser). A's dependency note holds.
6. **The survey.** Read in full. Its table marks Gaia GPS, Komoot, AllTrails and Avenza rows as "search summary" and
   the proposal says so. Two over-readings: the proposal writes "only OsmAnd's and Garmin's settings lists were
   established in full" where the survey says "OsmAnd's global settings and Garmin's map settings" (a subset of
   each); and D1's six-category list is the survey agent's own Recommendation section, not an observed practice,
   while the proposal's D1 says "the survey suggests" as if it were a finding (minor 11). The survey's common-practice
   lines on what sits on the map are well supported (seven apps cited); its line on settings groups rests on three
   partial lists.
7. **The inventory against the files** (ten controls per client, at the pinned commit's descendants in the
   worktree; no control changed between the pin and `0df27be6` in these files).
   - Web, verified: W1 summary text "Dystopo: Mount Rainier" (line 87); W4 `unitsel` options feet, metres, "reset to
     the region default" (line 90); W6 `osmsel`; W7 `text` range 0.8 to 2, step 0.1, default 1.3 (line 93); W19 to
     W28 ids and the `hidden` cancel button (lines 113 to 122); W29 `#print hidden` (line 124); W35 to W37
     `NavigationControl` at top-right (line 542; its defaults show zoom in, zoom out and the compass); W38
     `GeolocateControl` inside `if (LIVE)` (lines 546 to 548); W39 `AttributionControl({ compact: phone.matches })`
     with `phone = matchMedia("(max-width: 520px)")` (lines 140, 759); W54 `sync-pull` "Get from server"
     (trip-sync.js lines 75 to 79); W56 `trip-dsync`. The only `onclick` handlers in index.html are on W24, W26,
     W27 and W34, so no clickable non-control was missed. Nothing missed in the sample.
   - Phone, verified: 44 `SettingsRow` entries (43 plus `SET_HEIGHT`) in six groups in the inventory's order; M1 and
     M2 are `Chip(Screens.SETTINGS)` and `Chip(Screens.PACKS)` in `PacksScreen.kt` lines 103 to 104; M90's three
     labels (NavOverlay.kt lines 209 to 211); M94 to M97 match `RecenterButton.kt` (the lock button only at three
     buttons, the orientation button from two, a long press on the orientation button only at two and on the position
     button up to two, lines 232 to 269); M179 to M184 ("Sync now", "Close", "Sync peer …", "Save", "Clear token" in
     `SyncNowAction.kt`); S10's chip "From where I am" (`ViewshedText.FROM_POSITION`); S18's "1 button"; M8 "Download
     this region"; M132 "Keep each trip offline"; M102 to M107 in `FirstRunCard.kt`. Nothing missed in the sample.
   - Miscounted: section 1 says 25 items exist on both clients and the proposal repeats it, but six of its rows are
     one-sided (Hillshade intensity, Contour labels and Drainage: "no control" on the web; Contours on/off and All
     OSM layers: "none" on the phone; Scale bar: "display only" on the web). Nineteen items are on both (minor 6).

## The case against the recommendation, and the best alternative

The owner's cost is cross-client drift. The inventory shows how small the cross-client surface is: nineteen items,
of which twelve are map options already defined once in `shared` (`MapOptions.KEYS`, `parse`, the defaults) and
validated by the server's 400; the rest are units, text size, print, position, orientation and the trip flow. The
phone's other 38 settings rows and its 188 on-map rows have no second client to drift from.

Option C's distinctive promise over B is "one source the compiler checks; no fact twice". For the web client that
promise cannot hold: the page reads Kotlin only as generated JSON, so for the web C is B (a document, and a test
between the document and the page). C's real extra is phone-internal: `SettingsRoutes.KEYS`, `SettingsSync.current()`
and `publish()` iterate a registry instead of listing keys by hand. That is a worthwhile clean-up of three of the ten
places a phone setting lives (claim 4), but it is a refactor of every synced key's path, the proposal's own "largest
item", bought for settings that no other client has. It also inherits B's stated risk unchanged: the phone's settings
are not uniform values. `turnDeadline` is `sunset | civil | terrain | HH:MM`, `units` accepts `ft | feet | imperial`,
`readout.<activity>.<entry>` and `trackProfile.<activity>` are per-activity composites with their own encoders. A
`Control` data class that describes them either stays lossy (and then the registry is documentation with a test, B
again) or grows kinds and branches until it is the schema engine B was faulted for. The proposal names that risk for B
and not for C.

The best alternative is the proposal's own fourth "what would change" bullet, made the plan rather than the fallback:
**C narrowed to the shared surface.** The registry in `shared` holds what two clients share today (the map options,
units, text scale, the scale bar's both-units key, the on-map zones both have) and gains an item only when a second
client gains it. Phone-only settings keep `AppSettings`, `SettingsRoutes` and `SettingsSync` as they are and are
placed by id in the manifest (P1 part 1 needs only `SettingsRow.id`, which exists). The generated `controls.json` is
then exactly the cross-client contract the owner asked for and nothing else; the phone's ten-place chore is a
separate idea for a separate wave if it is wanted. This keeps every benefit the proposal claims for the owner's
question and removes the one risk it did not carry over from B.

## Hidden coupling

- **Ids are sync keys and stored-preference keys.** The registry's `id` would be the phone's settings key, synced as
  `app.<id>` (`SettingsSync.syncedKey`) and stored under the same name in `SharedPreferences`. A cross-client
  vocabulary invites harmonising names (`text` on the web against `textScale`; `hill` against `hillshade`;
  `scaleBar.both`), and any such rename is a sync-log and preference migration under ADR 0033. Nothing in the proposal
  forbids it (serious 4).
- **The help registry's key space.** S3 says help stays per platform "keyed by the item's id". The phone's help keys
  are `map.hillshade`, `settings.routeSnap`, `nav.offRoute`, `longPress.menu`: a different namespace, declared next
  to each control and listed verbatim in `HelpRegistryTest.theFirstPlacementsAreAllThere` (about sixty keys). Re-keying
  is a sweep over every screen file; the manifest can instead carry each platform's existing help key (serious 5).
- **iOS.** C assumes the iOS app reads `shared` through Kotlin Multiplatform; the proposal states the pure-Swift case
  as what would flip it to B. With C the generated JSON exists too, so a Swift client has a path either way; the
  coupling is acknowledged and acceptable. The manifest's zone vocabulary is per platform and iOS zones are not yet
  defined, so adding them is a schema change of the document; say so (minor 12).
- **The device tour.** `Modifier.control("<id>")` doubling as a semantics tag ties `tools/android-ui.py` and the tour
  to manifest ids. That is a good coupling (one name for a control in the manifest, the test and the tour); note that
  it makes the ids part of the debug interface and so part of what a rename breaks.
- **PLANNING's hard rule 2** (what other clients exchange must be usable by a future iPhone app regardless of code
  licence): the generated JSON satisfies it; a registry readable only through `shared` would not, so the generated
  file is not optional in C. The proposal treats it as the web's convenience; it is the contract.

## Mechanisms that refuse or fail closed

| Mechanism | Who lifts it, from where, while a session runs | Message says so? |
|---|---|---|
| Stale `controls.json` golden test (C) | Any implementer: `./gradlew updateGoldens` in its worktree, then view the diff | Not stated; must name the command |
| P1 part 1: a `SettingsRow` with no manifest item, or a `settings`-zone item with no row | Any implementer: add the item or the row in its own task files | Not stated; must name the row id and the manifest file |
| P1 part 2: a web element the manifest does not place, or a web item the page lacks | Undefined for flow actions: the proposal exempts "actions inside a flow" but gives the web no way to mark one, and 22 of the 62 web rows are Trips-box items, several created by `createElement("button")` with no id (`trips.js` lines 564 to 565, `trip-sync.js` lines 114 to 115, 197 to 198). Without a marking rule the check either fails on every Open and Delete button with no lift, or the regex skips dynamic elements and the check is hollow | No (serious 2) |
| P1 part 3: an on-map control naming an id the manifest lacks, or a manifest id no control names | Any implementer: add the id or remove the helper | Not stated |
| "The owner's table comes first" | Only the owner, by filling the Category column; no agent can lift it; the first step (map options, a Map category both clients already have) does not need it | A human gate with no documented lift (serious 3) |
| `MapOptions.parse` 400 on a bad value | Existing; unchanged | Yes (names the key and the accepted values) |

## Pre-mortem

Ten years on, the registry is the part everyone works around. It started as the map options and grew, as the
proposal's second step said it would, to hold every phone setting; each setting that did not fit a `Control` (the
per-activity readout entries, the typed turn-back time, the pace fit's encoded string) added a kind, and each kind
added a branch to the devtool generator, the phone's iterator and the test that checks the JSON, until the "one
source" was a small schema language with three interpreters: Kotlin on the phone, the page's script, and the Swift
reader the iOS team wrote when they decided not to take `shared`. The web page carried a 300-item document of which
it read nineteen. The first time a setting needed a parse the registry could not express, the implementer kept the
old constant beside the registry entry and the "no fact twice" promise was gone; the staleness test kept passing
because it compared the JSON with the registry, not with the code that still had the constant. The line that should
have caught it is B's own risk sentence, "the subset grows into a schema engine; per-activity keys … need pattern
properties", which the proposal wrote for the JSON file and did not carry to the Kotlin registry that faces the same
keys; and the Constraints bullet that found most of the phone's rows to be flow actions, which should have led to the
conclusion that the shared surface is small and the registry should be scoped to it.

## Objections

Severity: blocking (do not promote), serious (resolve before the ADR), minor (fix in revision).

1. **Serious. C's refactor of the phone-only settings has no cross-client payoff and carries B's schema-engine
   risk.** Evidence: 19 of the 44 settings rows' items are shared (claim 7); the web reads only generated JSON, so for
   the web C equals B; the composite and typed keys in `SettingsRoutes` (`turnDeadline`, `readout.<activity>.<entry>`,
   `trackProfile.<activity>`, `paceFit`) do not fit a `Control` of type, choices, default and range; the proposal
   states the risk for B (line 117) and not for C. Resolution: recommend C narrowed to the shared surface (the case
   above): the registry holds what two clients have, phone-only items are placed by `SettingsRow.id` without a
   behaviour refactor, and the second step ("moves the phone's other settings") is dropped or re-filed as a
   phone-internal idea. Carry B's risk sentence to C with the mitigation.
2. **Serious. P1 part 2 has no exemption rule for the web's flow actions, so the check fails closed with no lift or
   is hollow.** Evidence: the table above. Resolution: define the convention in the proposal: every placed web
   element carries `data-control="<id>"`; a flow action carries `data-control="flow:<parent-id>"` (or lives inside an
   element that does); the test fails on an interactive element (`input`, `select`, `button`, `summary`,
   `addControl`) with neither, and its message names the element, the file and line, and both ways to fix it. Say how
   dynamically created elements are found (the regex reads `createElement("button")` sites, or the convention
   requires the attribute at creation).
3. **Serious. "The owner's table comes first" gates the build on the owner, against ADR 0031.** Evidence:
   Recommendation, last bullet; ADR 0031 decides that interface changes in alpha are built from the idea and adjusted
   afterwards; the first step (map options) needs only the Map category, which both clients already have
   (`SettingsGroup.MAP`, the page's `#options`). Resolution: the first step uses today's six phone groups as the
   placeholder categories (they are already data in `SettingsGroup`); the owner's Category column re-orders and
   renames afterwards; D1 to D6 are listed as post-build adjustments, not preconditions.
4. **Serious. Ids are sync and preference keys; the proposal has no rule against renaming them for the manifest.**
   Evidence: `SettingsSync.syncedKey` builds `app.<key>` from the settings key; `AppSettings` stores under the same
   names; the web's ids differ (`text`, `hill`, `unitsel`). Resolution: one sentence in the recommendation: an item's
   id is its existing phone settings key; a web id maps to it in the manifest; no stored or synced key is renamed for
   the manifest's sake, and a rename, if ever wanted, is its own ADR 0033 task with a migration.
5. **Serious. S3 silently re-keys the help registry.** Evidence: today's help keys (`map.*`, `settings.*`, `nav.*`,
   `longPress.*`, `storage.*`) are not item ids; `HelpRegistryTest.theFirstPlacementsAreAllThere` lists about sixty of
   them verbatim; `HelpText` declarations sit in each screen file. Resolution: the manifest carries `help` per
   platform as a reference to the platform's existing key (S2's help mechanism), and `HelpRegistryTest` gains one
   assertion: every manifest item with a help reference names a declared key. No re-keying.
6. **Minor. "25 items exist on both" overcounts by six.** Evidence: claim 7. Resolution: 19 in the inventory's
   section 6 and in the proposal's Constraints; keep the 25 rows but add a column or mark for one-sided rows.
7. **Minor. "Three places" and "six places" undercount; what C removes is overstated.** Evidence: claim 4 (ten
   places). Resolution: list the places; state that C removes `KEYS`, `current()` and `publish()` lines and leaves the
   `Parsed.Ok` field, `apply` branch, row, page case, help text and two test lists, unless `Parsed.Ok` becomes a map
   (a further refactor the proposal should either include or exclude by name).
8. **Minor. S1 to S3 have no exit cost or moving parts lines.** The template requires them for every option.
   Resolution: add them; S3's exit is not "none": labels once unified either stay unified or drift again, and the
   label test is deleted.
9. **Minor. C's exit cost is understated.** Evidence: "it is code; leaving means inlining constants again" omits the
   page's fetch or inline, the `data-control` attributes, the Compose helper on about thirty controls, the generated
   file, the route and the devtool command. Resolution: list them; the cost stays low to medium, but the line should
   not be boilerplate.
10. **Minor. No refusal message names its lift.** Evidence: the table above. Resolution: the proposal states, per
    check, the message's content (element or id, file, the command or edit that lifts it).
11. **Minor. Prior art over-reads the survey in two places.** Evidence: claim 6. Resolution: "OsmAnd's global
    settings and Garmin's map settings"; D1 reads "the survey agent proposes", and the Prior art section says which
    common-practice lines rest on seven cited apps and which on three partial lists.
12. **Minor. Where the committed `controls.json` lives and how the static page reaches it are unstated; iOS zones
    will change the document's schema.** Evidence: the static demo serves the repository tree; the live server
    answers `/controls.json` from the registry; `injectLive` already rewrites the page per request. Resolution: name
    the path (so `/tools/demo/` can fetch it relatively), or inline the document through `injectLive` and ship the
    committed file for the static page; state that the zone vocabulary is versioned per platform.
13. **Minor. B was under-explored on validation.** Evidence: claim 3 (devtool and serverlib already depend on
    kotlinx-serialization-json; a JVM test-scope JSON Schema validator under a permissive licence is plausible and was
    not checked against `dependency.md`). It does not change the outcome, since the recommendation does not rest on
    B's weakness there; the sentence "a general JSON Schema validator in commonMain does not exist" should say that a
    test-scope one on the JVM was not evaluated.
14. **Minor. The phone side has no debug route for the manifest.** Evidence: the web gets `/controls.json`; the phone
    gets semantics tags only. `CLAUDE.md` › Rules: every feature agent-testable with a route or command. Resolution:
    `GET /controls` on the debug port listing the placed ids with zone and whether each is on screen now, as
    `GET /help` does for help keys.
15. **Minor. The one falsifiable exit condition is ambiguous.** Evidence: "Six months of the check catching nothing:
    P2 is enough". A check that never fails may be doing its job (every implementer adds the id because the check
    exists). Resolution: measure drift the check missed (a verifier or the sweep finds a control the manifest does not
    place, or two clients disagree on a label the registry holds) rather than the count of failures; and give the ADR
    two dated predictions: the first iOS task reads the generated document or the registry unchanged, and no stored
    key is renamed by the manifest's first three tasks.

Verified and not objections: no new dependency in C; `tools/user-text-check.py` scans `shared/src/*Main/**/*.kt`, so
labels moved into `shared` stay under the product-name rule, and the generated JSON derives from them; the stale-golden
pattern (`./gradlew updateGoldens`, `devtool/build.gradle.kts` line 64) exists; `GET /settings/layout` is documented in
`docs/reference/android-debug-routes.md`; the numbering 0053 follows 0052.

## Recommendation

Promote the shape and narrow the scope. C with P1 and S3 is the right answer to the owner's question, provided the
registry is the cross-client contract and not a rewrite of the phone's settings code: the registry in `shared` holds
the items two clients share, generates `controls.json` (the contract the iOS and web clients read, PLANNING hard rule
2), and grows only when a second client gains an item; phone-only settings are placed by their existing `SettingsRow`
ids; ids are the existing keys and are never renamed by this work; help stays under each platform's keys, referenced
from the manifest; the web check has an explicit flow-marking convention before it is built; the first step (map
options) starts now with today's groups and the owner adjusts the categories afterwards as ADR 0031 says. With those
five changes I would promote; without objection 2 the web check should not be built, and without objection 1 the
second step should not be scheduled. Confidence: high (about 0.85) that the narrowed scope is the better plan;
moderate (about 0.7) that the owner will fill the Category column at all, which is why the build must not wait on it.

Residual risks if the revised proposal is promoted: the source-reading tests (regexes over `index.html` and Kotlin)
fail with a parse error rather than a message when a constant is renamed, as `SlopeLegendTest` would today; the zone
vocabulary is invented here and will be revised for iOS; the owner's categories, when they come, may move items
between the web panel and the map (D2), which the manifest records but the page's layout code must still implement by
hand; and the label unification (S3) will surface wording the owner wants changed on one client only, which the
registry then cannot express without a per-platform override, an escape hatch the proposal should either allow by
name or forbid.
