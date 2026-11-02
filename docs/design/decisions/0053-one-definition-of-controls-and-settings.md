# 0053. One definition of the controls both clients share, a placement manifest with a failing check, one short label

Date: 2026-11-02
Status: accepted (the technical decision after the critic; the categories, their order and the wording are the
owner's adjustments after the build, ADR 0031)

Record: proposal `../proposals/0053-one-definition-of-controls-and-settings.md` (T206, wave 26), its inventory
`../proposals/0053-controls-inventory.md` and `../proposals/0053-critic-review.md` (0 blocking, 5 serious, 10 minor;
all answered in the proposal). Prior art: the benchmark survey of nine topo and navigation apps, summarised in the
proposal.

## Context

The owner, 2026-11-01: the web page and the phone should have "a consistent design between them that put things in
generally the same categories and places. Be that buttons on screen, settings categories, etc.", not a faithful
mirror; the cost is keeping two clients, later three, in step by hand; "something like OpenAPI docs that both stay in
sync with" (`docs/ideas/2026-11-01-one-definition-of-controls-and-settings-across-platforms.md`).

The inventory, pinned to one commit, counted 62 controls on the web page (`tools/demo/index.html`, `trips.js`,
`trip-sync.js`) and 245 on the phone (57 on the settings sheet, 188 on the map, its cards, menus, sheets and screens).
Only 19 items exist on both, most of them the map options `shared` already defines once (`MapOptions`: keys, defaults,
`parse` with a 400 naming the key). Most of the phone's 188 are actions inside a flow, which the idea leaves to each
platform. The survey found no surveyed app sharing one definition between web and phone; CalTopo and Gaia GPS keep the
same names and split on both with different chrome, which is what the owner described.

Verified (proposal, Claims to verify, and the critic's review): the web page is sent without a build step, with one
script inserted per request by `injectLive` (`serverlib/.../LiveTiles.kt`); source-reading tests across clients
already run in `./gradlew check` (`SlopeLegendTest`, `MarkerTableTest`, `HelpRegistryTest`); a phone setting lives in
ten places and two test lists; a settings key is also its stored and synced key (`SettingsSync.syncedKey`), so a
rename is a migration (ADR 0033); the phone's help texts have their own key space (`map.*`, `settings.*`, `nav.*`).
PLANNING's hard rule 2 requires what clients exchange to be usable by a future iPhone app, so the definition must exist
as a document, not only as Kotlin.

## Decision

1. **The registry holds the shared surface only (option C2).** A typed registry in `shared` commonMain holds the items
   two clients have (the map options, units, text scale, the scale bar's both-units key, the position and orientation
   controls, print, the trip flow's entry points): id, kind (setting, action, link, display), value type, choices,
   default, range, sync key, platforms. It gains an item only when a second client gains it. `MapOptions` takes its
   keys and defaults from it and keeps its parse. Phone-only settings keep `AppSettings`, `SettingsRoutes` and
   `SettingsSync` as they are. A shared item whose values the registry cannot express keeps its parse in code and the
   registry records only its id, kind and placement, never a second copy of its values.
2. **The document.** `devtool controls` writes the committed `tools/demo/controls.json` [no-check: written by build
   step 1], the contract the web page and a later iOS app read; `serve --live` inlines it through `injectLive` as
   `window.DYSTOPO_CONTROLS`, the static demo fetches it relatively, the standalone build copies it into `web/` with
   the page. A test fails when the committed file differs from the registry; `./gradlew updateGoldens` refreshes it.
3. **Ids.** An item's id is its existing phone settings key (`textScale`, `hillshade`, `scaleBar.both`); a web id
   (`text`, `hill`, `unitsel`) maps to it in the manifest. No stored or synced key is renamed for the manifest; a
   rename, if ever wanted, is its own task under ADR 0033 with a migration.
4. **Placement (P1).** A manifest beside the registry: ordered categories (id, title, order) and, per item, its
   category, order and zone per platform (web: `panel`, `map-corner`, `trips`, `map`; phone: `settings`, `map-top`,
   `map-right`, `map-lower-right`, `map-lower-left`, `card`, `menu`, `screen`; versioned per platform, so iOS zones are
   a document version when they come). Phone-only items are placed by their `SettingsRow.id` or a control id. It places
   every stored option wherever its screen is, the controls that stay on the map, the entry points to screens and the
   long-press menu's entries; it does not place actions inside a flow. The categories start as today's six phone
   groups (`SettingsGroup`), with the web's items placed in them.
5. **The check, in `./gradlew check`.** (a) Every `SettingsRow` id is a manifest item and every phone item in zone
   `settings` has a row (`SettingsLayoutTest`). (b) Every placed web element carries `data-control="<id>"`; a flow
   action carries `data-control="flow:<parent-id>"` or sits inside an element that does. A devtool test reads the
   three web files: elements by tag (`input`, `select`, `button`, `summary`) in markup and template strings, each
   `createElement` call of those tags and the attribute set after it, and `addControl(new maplibregl.X)` calls mapped
   by class name; it fails on an interactive element with neither attribute, an id the manifest lacks, or a manifest
   web item the page lacks. `demo-check.py` repeats the first rule on the live DOM in the sweep. (c) Each persistent
   phone control and entry point passes its id to `Modifier.control("<id>")`, also a semantics tag for the device
   tour; a source-reading test checks both ways. `GET /controls` on the phone's debug port lists the placed ids, their
   zone and whether each is on screen now.
6. **Strings (S3).** The manifest carries one short label per item (a few words, no units), checked against the page's
   label text and the phone's row label. A per-platform label is allowed only as a named override with a reason, which
   the label test lists instead of failing on. Help stays per platform under its existing keys: the manifest
   references them (`help.android: "map.hillshade"`) and `HelpRegistryTest` checks every reference is declared.
7. **Build steps, each a task.** (1) The map options: the registry for `MapOptions.KEYS`, the generated document, the
   check's parts (a) and (b) for them, the page's `data-control` attributes; no user-visible change. (2) The rest of the
   shared surface and every placed item of both clients, part (c), the Compose helper and `GET /controls`. (3) The
   owner's adjustments as they come.

Out of scope: the phone-only settings registry (re-filed as `docs/ideas/2026-11-02-phone-settings-registry.md`,
revived when a second client gains most of the phone's settings); an OpenAPI document of the server's parameters
(revived when a third party builds against the public site; it would be generated from the registry); generated
screens.

Adjustments for the owner after the build (ADR 0031; none is a precondition): D1 the categories and their order (the
inventory's Category column); D2 map appearance as a Layers control on the map in both clients; D3 the web panel's
sections under the phone's category names; D4 whose wording wins where the labels differ; D5 optional on-map buttons
as a setting; D6 the settings the phone keeps outside the sheet (trip corridor, cache budgets, sync server and token).

## Consequences

- A control added to either client without a place fails `check`, and every refusal names its lift; any implementer
  lifts it in its own task's files while the session runs:

| Refusal | The message names |
|---|---|
| Stale `controls.json` | the file and `./gradlew updateGoldens`, then view the diff |
| A row with no item, or a settings item with no row | the id, `SettingsLayout.kt` and the manifest's file |
| A web element with neither attribute | the element, its file and line, and both fixes (`data-control="<id>"` with an item, or `data-control="flow:<parent-id>"`) |
| A web or phone id the manifest lacks, or an item no control names | the id, the file and line or the manifest entry, and the edit on either side |

- Costs: the registry and generator, three tests and one `HelpRegistryTest` assertion, the attributes on the page,
  the helper on the phone's 30 or so persistent controls, one debug route. The regex readers fail with a message
  naming the pattern they could not find. A new phone button that names no id passes `check`; the device tour's
  semantics pass in the sweep catches it.
- The owner's category edits become an agent's transcription into the manifest; moving an item between the panel and
  the map (D2) is still layout code written by hand on each client.
- The phone's ten-place chore for phone-only settings is unchanged.

What would supersede this: an iOS app that cannot use the generated document (B, a hand-kept JSON file every client
reads); the owner editing categories and labels in a file rather than the table (B with P1 and S3); a third party
building against the public site (an OpenAPI contract versioned on its own); a second client gaining most of the
phone's settings (C, the full registry); the owner asking for a mirror (generated settings screens); drift the check
missed, found twice in a year by a verifier or the sweep (P1's rules widened).

Rejected: **A** (OpenAPI by hand): covers 8 of the 57 phone settings items and no placement except in `x-` keys
tooling ignores. **B** (a JSON Schema file): every fact twice, file and code, and a schema subset to interpret in
commonMain. **C** (the registry for every setting): no cross-client gain over C2 and B's schema-engine risk on the
phone's composite settings. **P2** (the inventory, diffed): reports drift, never prevents it. **P3** (generated
screens): a mirror, against the owner's words, and the phone's rows are not generic. **S1** (labels and help in the
definition): help texts depend on units and gestures; a template language and sixty re-keyed texts. **S2** (keys
only): the wording keeps drifting as it does today.

Predictions (the trigger scan reads these):
- Prediction, check when the first iOS task lands: the iOS client reads the generated document or the registry unchanged, with no format change.
- Prediction, check when build step 2 and the next two tasks touching the manifest have merged: no stored or synced key was renamed for the manifest.
- Prediction, check by 2027-05-01: the registry holds fewer than forty items, because it grows only with a second client.
