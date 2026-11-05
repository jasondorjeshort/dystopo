# 0056. Critic review: one name and one list for downloaded maps, and what the status chip says

Date: 2026-11-04
Reviewed: `0056-one-name-one-list-for-offline-maps.md` in the T225 worktree at `a48541c5` (the proposal on top of the
fork `96b5e019`; every code citation below is at that fork); read-only (no Gradle run, no server, no emulator). Files
read in the brief's order: `CLAUDE.md`, the proposal, ADR 0023, 0031, 0033, 0044, 0053 (and 0025), `docs/TASKS.md` ›
T225 and the wave 28 paragraph's Owns lines, the gitignored reports `usability-audit.md` (findings 5 and 10),
`offline-map-vocabulary.md` and `critic-wave28.md` (objections 2, 3, 4, 9, 15), then the code: `<app>/packs/PacksScreen.kt`,
`areas/AreasScreen.kt`, `areas/Areas.kt`, `areas/AreaPicker.kt`, `areas/AreaDownload.kt`, `areas/TripCorridors.kt`,
`areas/ResumeCheck.kt`, `online/OnlineOverlay.kt`, `online/OnlineView.kt`, `online/OnlineTiles.kt`, `online/OnlineService.kt`,
`online/OnlineFetcher.kt`, `online/RegionDownload.kt`, `online/TileCache.kt`, `TileService.kt`, `packs/PackManager.kt`,
`packs/PackDownloader.kt`, `packs/PackCatalogClient.kt`, `packs/CatalogOverlay.kt`, `share/PeerSources.kt`,
`share/ShareScreen.kt`, `firstrun/Screens.kt`, `firstrun/FirstRun.kt`, `firstrun/FirstRunCard.kt`, `settings/SettingsPages.kt`,
`storage/Storage.kt`, `storage/StorageScreen.kt`, `help/HelpRoutes.kt`, `MainActivity.kt`, `TopChips.kt`,
`shared/.../areas/AreaPins.kt`, the tests `ScreenNamesTest`, `OnlineOverlaySnapshotTest`, `TripCorridorStatusTest`,
`SettingsLayoutTest`, `MediumDetailTest`, `RegionPickerTest`, `tools/android-ui.py`, `tools/android-firstrun.py`,
`tools/demo/index.html`, `trips.js`, `trip-sync.js`, `controls.json`, and a search of every string literal under
`<app>` for the retired nouns. `<app>` is `androidApp/src/main/kotlin/.../app/`. The second survey (apps where users
handle files by hand) is not on disk and is taken as the proposal summarises it.

Verdict in one line: the direction is right (one noun, one list, a two-part chip whose words do not depend on the noun)
and the owner's V1/V2 question is fairly laid out; what is not yet right is the specification underneath, which has
holes a build would fall into: the join table and the state-word table are not total over the states the code has,
the retired-noun guard as written would see 190 literals and silence them by file, the V1 Storage labels are false for
a real kind of pack, and B1 is two tasks. 0 blocking, 6 serious, 10 minor.

## Claims to verify

1. **Only `recordArea` and a peer's area write a record naming a pack.** Verified. A search for `Areas.put(` and
   `Areas.Record(` outside `Areas.kt` finds three writers: `areas/AreaDownload.kt:346` (tile records, `pack` null),
   `online/RegionDownload.kt:231` (`recordArea`) and `share/PeerSources.kt:480` (`area()`). `PackManager.import`,
   `PackDownloader`, `PackCatalogClient` and `ImportPicker` never name `Areas`. One nuance: `PeerSources.area` starts the
   download (`:479`) and then writes the record (`:480`), the reverse of the proposal's "writes the record first";
   immaterial to the join.
2. **`PackManager.delete` never touches `Areas`; `Areas.remove` deletes the pack under a second record.** Verified.
   `delete` (`packs/PackManager.kt:154-181`) cancels the job, closes and deletes files, forgets indexes and graphs; no
   `Areas` call. `Areas.remove` (`areas/Areas.kt:360-373`) deletes the pack when `PackManager.find(r.pack) != null`.
   `coverage` of a record whose pack is gone (`:496-499`) answers `present=false, share=0.0`, and the card then reads
   "KEPT AS A PACK" (`AreasScreen.kt:202`) with "0% on this phone" (`:219`). The generated name avoids every pack and
   record name (`RegionDownload.kt:99`, `takenNames` `:124-128`). A typed name is checked for characters only
   (`PackNames.isValidName`, `:100`), so a repeat is not refused; the join's one-row-per-name rule covers it.
3. **`OnlineTiles.kt:117` records a pinned area's tile as `CACHED`; nothing clears the memo.** Verified. `recordDem(z, x,
   y, if (f.cached) CACHED else ONLINE)` at `:117`; the memo `demSources` (`OnlineService.kt:93-95`, LRU of 4096) is
   written only there and read by `demSource`; `configure` (`:134-146`) replaces the fetcher and leaves the memo.
   `viewSource` (`OnlineTiles.kt:337-348`) reads the memo at four zooms (zt, zt−1, zt+1, zt−2), so the stale word can
   come from a neighbouring zoom as well as an earlier fetch.
4. **`Areas.isPinned` is cheap off the UI thread and the key is formable in `OnlineUi.make`.** Half verified.
   `Areas.isPinned` → `AreaPins.isPinned` (`shared/.../areas/AreaPins.kt:63-70`): a split, a parse and
   `covers.any { holdsRoute }`, linear in the pinned covers; once a second is nothing. The key is
   `"${source.prefix("dem")}/$z/$x/$y.png"` with the content version in the prefix (`OnlineFetcher.kt:161-167`), which
   `OnlineUi.make` can build from `service.fetcher?.sourceList`; but `viewSource` returns a word, not the tile it
   matched, so B3 must extend it or recompute the tile. And `current` includes the navigation's transient pins
   (`Areas.kt:400-404`, `AheadPrefetch`), so `isPinned` is true for a tile no row holds (minor 9).
5. **The "n/a" cells.** Verified as far as they go. `notice` is set only when `isDirect` (`OnlineOverlay.kt:101`);
   `tilesFor` is null without a fetcher (`OnlineService.kt:167-168`), `TileService.kt:308` takes it, and `sourceFor`
   (`OnlineView.kt:36-37`) then answers `PACK` or `NONE`; Connecting after a server change reads the surviving memo.
   One combination the table does not know: a fetcher of peers alone, no server set and direct off
   (`OnlineService.kt:146`), has `isDirect == false`, so its offline state is the red row with "your server" cells, and
   `fetcher.server` is then a `peer://` address (minor 8). "No server" with `running` needs the server removed mid-job
   (`RegionDownload.start` requires one, `:90`), so that row is reachable only there.
6. **A pack gives routing and search, a tile area does not.** Verified for a pack with a vector archive:
   `REGION_PACK_LINE` (`AreaPicker.kt:494`), `AreaDownload.kt:773`. Not true of every pack: a terrain-only pack (no
   vector archive; `PacksScreen.kt:382`, the catalog's `profile ?: "terrain only"` at `:325`) has neither routing nor
   search and is still never cleared. The row's detail line ("Terrain only") handles it; the V1 Storage label does not
   (serious 4).
7. **Every string in the table reaches a user; every one in the Stay list does not.** Mostly verified. The literal
   search found 186 code-line literals with a retired noun outside Log lines and `*Routes.kt`; every user-visible one
   I could trace is in the table or reworded by B1's screen rewrite (`PacksScreen.kt:233-234,271-273` "Deleting
   <name>" are the verb's progressive, not listed, but the screen goes). I found no developer-only string listed as
   user-visible. What the Stay list does not name is the long tail of JSON keys, preference keys, constants and thread
   names (`Areas.kt:89,100-101,141-142,155-156,388,559`; `MapViewport.kt:88,181,187`; `NavSources.kt:70`;
   `RequestLog.kt:23`; `RouteExposure.kt:129`; `RouteWater.kt:136`; `TileService.kt:409,503-504`; `CatalogOverlay.kt:49`;
   `PackCatalogClient.kt:41,341`; `Packs.kt:140`; `MapController.kt:727`; `FirstRun.kt:179`; `Sources.kt:30,40`;
   `LiveReadout.kt:397`; `OnlineTiles.kt:421`; `ImportPicker.kt:86`), which matters for the guard (serious 1). The
   `background()` call sites were read (`PacksScreen.kt:233-234,271-273,288`).
8. **`PacksUi.visible` can delegate to `Areas.screenOpen` with the callers unchanged in behaviour.** Not verified; the
   behaviour changes. `Areas.screenOpen` is set true by every picker mode (`AreaPicker.kt:150,160,175,187,220`: countries,
   a country, drawing, around a point, the region card), and `PacksOverlay` draws the Settings and Packs chips only
   while `!PacksUi.visible` (`PacksScreen.kt:92`), so after the delegation the top chips vanish during every picker
   flow, and `FirstRunCard` (`:86,91`) hides whenever a picker is up, which it does not today. `POST /packs/ui` answers
   `firstVisibleItem` and `localRows` from the Packs screen's `LazyColumn` (`PacksRoutes.kt:56`, `PacksScreen.kt:167-171`);
   the Areas sheet is a `Column(verticalScroll)` (`AreasScreen.kt:169`), so those answers need a new source (serious 6).
9. **`ScreenRefs.WORD` extended misreads nothing.** Verified for today's strings: no literal contains "(Offline maps)",
   "in Offline maps" or "the Offline maps list", and lowercase "offline maps" never matches `[A-Z]`. Two corrections:
   `ScreenRefs` has four regexes, not three (`arrow`, `paren`, `preposition`, `noun`, `ScreenNamesTest.kt:102-105`); and
   once the Settings row's label is "Offline maps", a message saying "Settings › Offline maps" would be flagged unless
   "Offline maps" joins `Screens.ALL[SETTINGS]`'s sections (none says it today).
10. **"About ten strings" between V1 and V2.** Fair. The table has 10 rows, 15 literal sites in 9 files (two for the
    Import action, five on the web page), plus a help sentence and one allow-file term for V2, as the proposal says.
    Both costs lists read honest; V1's "Imported from a file" should be "from files" (an import takes two,
    `PackManager.kt:322-328`).

## The case against the recommendation, and the best alternative

Against V1: the two stores keep needing names in two places the proposal cannot avoid, the Storage rows and the budget
sentence (`AreaDownload.kt:825`), and V1 gives them capability names ("with routing", "map only") that the code makes
false for terrain-only and vector-only packs. The honest axis is the clearing rule (the proposal's own section "What the
code says the user needs to tell apart", point 1). V2 does not help here either; this is a V1 wording defect, not a
reason for V2. The real case for V2 is the one the proposal states: the owner and testers pass these files around, and
the Share screen sends files. I have nothing to add to it; it is the owner's.

Against L1 as specified: a 0.6 sheet must hold a title row, two picker buttons, the trip-width row, the rows (each with
a name, a size and a state word), an empty line, and a fold with a server status line, the catalog rows, a link box and
an import row, at font scale 2.0 in landscape (ADR 0031's accessibility rule). The proposal names the risk and a
fallback; it does not say what measurement triggers the fallback. Every surveyed app has a full-screen list. The
proposal's reason for the sheet is sound (the outlines and the catalog's fit need the map above), and the Packs screen
already has the shape that solves it: full screen for the list, half-height while the catalog is open
(`PacksScreen.kt:210-215`). The best alternative is therefore not L3 but L1 built on the Packs screen's two heights: a
full-screen "Offline maps" list that drops to 0.6 when a Show, the catalog or a picker needs the map. Either base works
once `PacksUi.visible` is `Areas.screenOpen`; the proposal should state the trigger ("fewer than three rows visible at
font scale 2.0 in landscape, or the fold's first row below the window") and let B1 choose.

Against C1: nothing structural. "On this phone" and "viewed earlier" are the right pair. Two words are wrong at the
edge: "Server offline" when the phone has no network at all blames the server (the card explains, the chip is what a
hiker reads), and a peers-only fetcher gets the red row with no server set (claim 5). Both are cheap to fix (minor 8).

## Hidden coupling

- **ADR 0053's manifest.** The `offlineAreas` row id stays (good); the chip and the fold's entry points will need
  `Modifier.control("<id>")` ids when build step 2 lands. The proposal names none, so step 2 will pick them, and a
  rename later is the migration ADR 0053 forbids. Name them now (minor 7).
- **Proposal 0055's dwell box.** The join's signature `rows(packs, records, coverage, jobs, regionJob, active, now)` has no
  third input; the dwell store (option E, a file of its own) would be one. The proposal's "What would change" names
  the case; the row model should take a list of sources rather than two named ones so the store is a third list, not a
  signature change.
- **A later iOS client.** The join's precedence, the state words and the chip's table are product behaviour, pure, and
  placed in `androidApp` (`areas/OfflineMapList.kt`, `online/OnlineOverlay.kt`). An iOS client would write them twice.
  PLANNING's hard rule 2 and the `shared` constraint do not require it, but they make it cheap now and dear later
  (minor 5).
- **The route name `GET /offline-maps`.** Every other route uses the developer's words (`/packs`, `/areas`) and the
  proposal keeps "pack" as the developer's word in docs and routes; this one route would use the user's noun, a second
  vocabulary in the API (minor 6).
- **The one-list rule decides the Storage screen's shape.** Once there is one list, the Storage screen's two rows are
  the only place a user sees that two stores exist, and the budget sentence is the only place they learn one is
  bounded. Those three strings carry more than the proposal gives them (serious 4).

## Mechanisms that refuse or fail closed

| Mechanism | Who lifts it, from where, while a session runs | Message says so |
|---|---|---|
| `RetiredNounsTest` (B2) | any implementer, by rewording the literal or appending `file: phrase` with a reason to `src/test/resources/retired-nouns-allow.txt`; a unit test in `check`, no hook | yes, as specified: file, line, both ways out |
| `ScreenNamesTest` with the widened `WORD` (B1) | as today: add the screen or section to `Screens.ALL`, or reword | yes (`ScreenRefs.problem` names the screen and the list) |
| `OfflineMapListTest` covering the join (B1) | the implementer that changes the join | not stated; a table-driven test should print the input row that failed |
| The pinned budget refusal (`AreaDownload.kt:825`, reworded by B2) | the user: remove a map or raise the budget in Settings › Storage | yes |

Two gaps. First, in the build wave B2 alone owns the allow file; B3 merges "at once" with it and B1 before it. B3 needs
no allowance if it rewords both its literals (`OnlineOverlay.kt:72,92`), and B1 merges before the guard exists, so the
order holds, but only by luck of the content; the proposal should say B3 may append to the allow file or that B3 merges
first (minor 10). Second, the lift as specified is too easy to be a guard (serious 1): a `file: phrase` line silences
every literal of that phrase in that file, so `Areas.kt: pack` (needed for nine JSON-key literals) also silences a
user-visible "pack" added to `Areas.kt` later.

## Pre-mortem

Ten years on, the regret is that the one list made the two stores invisible exactly where it mattered. The list showed
"Saved" and "May be cleared" as state words most users never read, the Storage rows were renamed twice as new kinds of
offline data arrived (the dwell store in 2027, group-shared maps later), each with its own clearing rule and budget, and
the budget sentence was the only warning that a store was bounded. Users reported "my map disappeared" for years; the
answer was always "it was a cleared kind", and nobody could point to the screen where that had been said. Meanwhile the
retired-noun guard's allow file grew to four hundred lines because it matched JSON keys, and every task appended to it
without reading it, so when "pack" came back in a help text in 2029 the guard was green. Two lines should have caught
this: "another state word, not another noun; if it cannot be one, this reopens" (What would change this recommendation),
which names the trigger but gives it no owner, no check and no prediction, and "an allow file holding only the Stay
list" (B2's Done-when), which assumes the Stay list is closed when claim 7 shows it is not.

## Objections

1. **Serious. The retired-noun guard as specified sees 190 literals and is lifted by file.** `\bpacks?\b` on code-line
   literals under `<app>` matches 190 times at the fork: 50 single-token literals (`"pack"`, `"packs"` as JSON keys and
   constants, `OnlineTiles.kt:421`, `Areas.kt:89`), 27 in `*Routes.kt`, 17 Log lines, plus preference keys
   (`MapViewport.kt:181,187` `"pack.name"`), thread names (`ImportPicker.kt:86`, `PackCatalogClient.kt:41`), the packs
   directory (`Packs.kt:140`) and route paths. The Stay list names about fifteen files and "every `*Routes.kt` file"; as
   `file: phrase` lines that is a coarse allow list that also silences future user-visible uses in those files, and as
   per-line entries it is well over a hundred lines B2 must write and every later task must tend. The Done-when "an
   allow file holding only the Stay list" cannot be met as the list is written. **Resolve:** the guard reads only
   literals that contain a space (a sentence), skips `*Routes.kt`, `Log.*(` arguments, `Thread(` names and literals that
   are a `const val`'s value; the retired phrases are the multi-word ones plus `\bpacks?\b` inside such a sentence; the
   allow file lists a file, a line's text fragment and a reason, never a bare word per file; the Done-when says "the
   allow file holds sentences only, each with a reason, fewer than N lines".
2. **Serious. The join table misses the rows where a pack is present and a job runs for it, so a row is doubled or a
   state is unreachable.** `PackDownloader` streams each archive to `<file>.part` and installs them one at a time
   (`:33,71`; the vector archive is the first `FileJob`, `:114-115`), so once the vector lands `catalog()` lists the pack
   while the DEM is still downloading; an update job (`startCatalog(update = true)`) and a graph-only job (`startGraph`,
   T189) run against a present pack; today's screen shows both a `PackRow` and a `JobRow` for the same name
   (`PacksScreen.kt:231,237`). The table has "a pack download with no pack yet" and "record naming P, P absent, a
   download running", not "P present, job running". Also "Update available" is defined as `Areas.older` (T95), which never
   fires for a pack record (`recordArea` writes no `versions`), and the row model takes no catalog status, so a catalog
   pack with a newer build on the server reads "Saved" and the row's Update action has nothing to go on. **Resolve:** add
   the rows (present pack with a fresh, update or graph-only job; the state word says which: "Downloading 40%",
   "Updating 40%", "Fetching the trail network"); add `catalog: Map<String, CatalogStatus>` to the inputs or state that
   Update lives in the fold's catalog rows only.
3. **Serious. The state-word table is not total over the record states the code writes.** `Record.state` takes
   `pending`, `downloading`, `done`, `failed`, `cancelled`, `partial` and `refused` (`AreaDownload.kt:459`,
   `DownloadWorker.kt:217`, `ResumeCheck.kt:263-303,322-339`, `TripCorridors.kt:191-217`), and `error` may be set with any
   of them (`ResumeCheck.kt:127`). `AreasScreen.kt:230` treats `error != null || failed || partial` as one failed state
   with Retry and Dismiss. The table's "Download stopped" is scoped to T222's pack downloads and "Part saved" to
   `partial`; a tile area that is `failed`, `cancelled`, `refused` or `pending`, or `done` with an `error`, falls through
   to "Saved" or "May be cleared", which is wrong, and a `pending` trip corridor waiting for the queue has no word.
   **Resolve:** rows for tile areas too: "Download stopped" for `failed`, `cancelled`, `refused` and any `error` without
   a job, "Waiting" for `pending` or queued; define "finished" as `done` with no error; have `OfflineMapListTest` iterate
   every state × pack-presence × job combination and assert a word, so the table is total by test.
4. **Serious. The V1 Storage row labels and the budget sentence name the stores by capability, which is false for a
   real kind.** "Offline maps with routing" (`Storage.kt:178`) would hold terrain-only packs (no vector archive, no
   routing, no search; `PacksScreen.kt:382`, `PackCatalogClient` `profile` null → "terrain only") and vector-only packs
   (T65) alike; "Offline maps, map only" (`:191`) describes tile areas by what they lack. The difference that reaches a
   user is the one the proposal itself names first: one store is never cleared and has no budget, the other has a
   budget and is cleared past it (`AreasScreen.kt:352-353,375`, `AreaPicker.kt:339-340`). The budget message
   (`AreaDownload.kt:825`) rewritten as "Offline maps would use about X, over their budget" is wrong for the same reason:
   packs do not count in it. **Resolve:** name the rows by the clearing rule ("Offline maps, kept whole, never cleared" and
   "Offline maps, downloaded areas, N GB budget" or the owner's shorter form), put capability behind the row's tap as
   the proposal does for list rows, and let the budget sentence name the budget ("Downloaded areas would use about X,
   over their N GB budget"). "Area" survives here as the drawn thing's name, which the proposal already allows.
5. **Serious. B1 is more than one implementer's pass.** It merges two screens (426 and 540 lines), writes the join and
   its table test, the fold with the catalog's rows, outlines and fit, the link box and import, two route files and a new
   route, renames the screen constant with four regexes and a test, edits five called-out lines in four other files,
   changes six tool lines, and its device check needs six kinds of pack (one from a second phone, one from a server with
   tilebuild) at two font scales and in landscape. Wave 28's critic blocked T224 on the same ground. **Resolve:** B1a, the
   row model and `OfflineMapListTest`, `GET /offline-maps`, the rows and Remove on the Areas sheet, the Packs screen kept
   and its chip still "Packs"; B1b, the fold (catalog, link, import), the Screens rename and `ScreenRefs`, the chip and the
   three links, the removal of the Packs screen, the tool rows. B1b merges before B2.
6. **Serious. Claim 8 is false as stated: delegating `PacksUi.visible` to `Areas.screenOpen` changes behaviour the
   proposal says it keeps.** Every picker mode sets `Areas.screenOpen = true` (`AreaPicker.kt:150,160,175,187,220`); the top
   chips are drawn only while `!PacksUi.visible` (`PacksScreen.kt:92`), so Settings and the new chip disappear while a
   user draws, picks a radius or reads the region card; `FirstRunCard` hides on `PacksUi.visible` (`:86,91`) and so hides
   during the "Download the map around me" flow it starts, which T220 is rebuilding this wave; `POST /packs/ui`'s
   `firstVisibleItem` and `localRows` come from the Packs screen's `LazyColumn` (`PacksRoutes.kt:56`), which the Areas
   sheet does not have. Probably all three are improvements or harmless, but a design should say what changes.
   **Resolve:** state the three changes; keep the chips' hiding tied to the sheet being shown (not to a picker card), or
   say the picker hides them too and why; give the two route answers a source or retire them with the ADR 0033 list.
7. **Minor. ADR 0053 control ids are not named.** The chip, the Settings row (`offlineAreas` stays, good), the two Storage
   links, the fold and its three entries will each need a `Modifier.control("<id>")` id in build step 2. **Resolve:** name
   them in the specification (`packs` for the chip if that is today's, `offlineMaps.add`, ...) so step 2 transcribes.
8. **Minor. "Server offline" at the two edges.** With a server set and the phone out of signal the chip blames the
   server; the card's sentence covers it but the chip is what is read. A peers-only fetcher (`OnlineService.kt:146`: no
   server, direct off, peers present) has `isDirect == false`, so it takes the red row and "Your server at peer://...".
   T83 already reads the network state for `directOnMobileData`. **Resolve:** red and "Server offline" only when a network
   is up and the server does not answer; "Offline" grey when the phone has none; the peers-only fetcher reads as the
   grey row or its own word.
9. **Minor. The "inside a downloaded map" column counts the navigation's transient pins.** `Areas.current` is built
   from the pinned records and the transient covers (`Areas.kt:400-404`), so `isPinned` is true for a tile pinned only for
   the segments ahead; the card would say "This part of the map is saved on this phone" with a link to a list that has no
   row for it. And `viewSource` returns a word, not the tile, so the key cannot be formed from its answer. **Resolve:**
   `viewSource` returns the tile it matched (or `OnlineView` carries it); the column asks the records' covers only (an
   `Areas.isRecorded(key)` beside `isPinned`), or falls back to the bounds test the proposal already names.
10. **Minor. Test files and the merge order are split between tasks without saying so.** `TripCorridorStatusTest.kt:44`
    tests `corridorHelp` in `AreasScreen.kt` (B1's file) and `:16-23` test `TripCorridors.kt` (B2's); neither Owns line
    names the file, and `:20` ("Waiting to keep offline") is missing from the pinned list. B3 merges "at once" with B2 but
    does not own the allow file. **Resolve:** name the test lines per task; say B3 merges before B2, or give B3 an
    appended-lines right on the allow file.
11. **Minor. A saved catalog pack shows twice when the fold is open.** The catalog rows stay "as today", so an installed
    pack is a list row and an `INSTALLED` catalog row (`PacksScreen.kt:267-275`); B1's Done-when "each show once" is
    then ambiguous. **Resolve:** say the fold is the server's list and may repeat a saved map, or hide installed catalog
    rows and move Update to the list row (which objection 2's catalog input makes possible).
12. **Minor. Tool lines missed.** `tools/android-firstrun.py:476` matches `Saved as pack` in the progress regex (B3, beside
    `:485`); the docstrings at `tools/android-firstrun.py:22` and `tools/android-ui.py:1088` say "N packs on". The
    `From server` step taps the `▸` header (`android-ui.py:1856-1857`); with the new fold it taps two headers in order,
    which the row for `:1855` implies but should state. **Resolve:** add the lines.
13. **Minor. `ScreenRefs` has four regexes, and the Settings row's label becomes a screen name.** The proposal says
    "all three"; `arrow` (`ScreenNamesTest.kt:102`) is the fourth and carries the lookbehind. Once the row reads "Offline
    maps", a message "Settings › Offline maps" is a Settings section reference and would fail unless the section is
    listed. **Resolve:** say four; add "Offline maps" to `Screens.ALL[SETTINGS]`'s sections or forbid the form.
14. **Minor. The pure parts sit in `androidApp`.** `OfflineMapList.rows` and the chip's table are pure functions of plain
    inputs and product behaviour a second client would repeat. **Resolve:** place them in `shared` (`areas/OfflineMapRows.kt`
    with a small input data class; `online/OnlineChipText.kt`) and keep the Android files as adapters; the tests move with
    them and run on the JVM as today.
15. **Minor. `GET /offline-maps` is the one route in the user's vocabulary.** Every other route and JSON key keeps the
    developer's words by the proposal's own rule. **Resolve:** `GET /areas/rows` or `GET /packs/rows`, or one sentence
    saying why this route is the exception.
16. **Minor. No falsifiable prediction, and two boilerplate cost lines.** The proposal carries no prediction the trigger
    scan could read; L1's exit cost "restoring two composables" omits the chip, the routes' answers and the tools' taps
    (B1b in objection 5 is the true exit); B2's Done-when "the goldens pass" names a check no string can fail. **Resolve:**
    two predictions for the ADR (for example: the owner's post-build string adjustments touch fewer than a third of the
    new strings; no sweep screenshot after the release carrying B2 shows a retired noun), the exit cost as B1b's inverse,
    and the goldens line dropped.

## Recommendation and confidence

Accept the direction and revise the specification before the ADR: V1 recommended to the owner as the proposal does
(with objection 4's labels, which fix V1's one false sentence and make the V1/V2 choice purely about the file), L1 with
the two heights the Packs screen already has and a stated trigger for full screen, C1 with the two edge words of
objection 8. Make the join table and the state-word table total by test (objections 2 and 3), split B1 (5), and cut the
guard to sentences with a per-line allow file (1); the rest are an afternoon of edits. The proposal is unusually honest
about what rests on thin ground (the chip is original design; the second survey is summarised, not on disk; the sheet's
room), and I found no code claim that was wrong in a way that changes the design, only ones that were incomplete.
Confidence that the revised design is right for the next five years: high for the noun and the chip, medium for the
one list, because the sheet's room at font scale 2.0 and the arrival of a third store (0055) are the two things that
could reopen it, and both are named in the proposal's own "What would change" list. Residual risks after the revision:
the second survey cannot be re-read; the 50 dp the chip costs the search box is an estimate until T224's `GET /layout`
measures it; and the owner may still prefer the full-screen list every surveyed app has.
