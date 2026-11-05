# 0056. One name and one list for downloaded maps, and what the status chip says

Date: 2026-11-04
Status: promoted to ADR 0056

Paths without a root are under `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/`. Every `file:line` is
read at the fork of this task, commit 96b5e019. T217, T218 and T220 to T224 are changing some of these files while this
is written, so the build tasks re-read every site after wave 28's merges.

## Question

A user meets five names for one thing (pack, region pack, offline area, region, keep offline) on two screens. The Packs
screen, which the map's chip opens (`packs/PacksScreen.kt:104`), lists every pack however it arrived. The Areas screen,
one tap further (`packs/PacksScreen.kt:221`), lists only `Areas` records, and only a region download
(`online/RegionDownload.kt:228-238`) or a peer's area (`share/PeerSources.kt:480`) writes one for a pack. So a pack that
was imported, chosen from the server's catalog or fetched by URL is on the first list and not on the second, while the
status chip beside them says "Online · pack" or "Online · from cache" (`online/OnlineOverlay.kt:71-76`). What should the
noun and the verbs be, one list or two, what does the chip say, and what stays as it is underneath? In ten years a
user should still think of one kind of thing, a map kept on the phone, whatever storage the app uses for it.

## Constraints that matter here

- Platforms, licensing, offline first, agent-drivable, the design goals: as in the template; this is wording and one
  screen, with no dependency, and the row model is pure Kotlin with a debug route.
- `CLAUDE.md`, Decisions: no other product's name in user-visible text. The strings below name none; Prior art may.
- ADR 0023: "the online indicator says Paused"; Connecting and the red dot of an unreachable server stay (T192,
  `online/OnlineOverlay.kt:60-69`). ADR 0033: no stored or synced key is renamed; ADR 0053's prediction says the same.
- `ScreenNamesTest` ties every screen named in a message to `firstrun/Screens.kt` and to a chip or link with that label;
  its `ScreenRefs.WORD` is one capitalised word (`ScreenNamesTest.kt:100`).
- The critic of wave 28 ruled out a string sweep without this design: the list of places omits every imported,
  catalogued or shared pack, the retired nouns sit in a dozen files of five owners, the sweep's tool taps the old labels.

## Prior art

Two surveys of 2026-11-04 (`out/wave28-research/offline-map-vocabulary.md`, gitignored; a second one on the apps where
users handle files by hand, launched for this proposal, summarised here). Many vendor pages refused the fetch, so
several cells rest on snippets.

| App | The downloaded thing | The file brought in by hand | Lists | Import, delete |
|---|---|---|---|---|
| OsmAnd | maps | no noun of its own | one (Local) | Import, Remove (strong) |
| Organic Maps | maps | no map import | one | none, Delete Map (strong) |
| Locus Map | offline maps | imported maps | one (Offline tab) | Import; delete not found |
| Avenza Maps | maps | custom maps | one (My Maps) | Import; delete not found (medium) |
| Guru Maps | offline maps | custom offline maps | not established | a paid add-on (weak) |
| Backcountry Navigator | maps | none (files placed in a folder) | one (weak) | none |
| Gaia GPS | downloaded maps | a map, under Layers | two places | Import File (medium) |
| Google Maps, AllTrails, Komoot, Mapy | offline maps | no map import found | one | Delete |
| CalTopo | downloaded layers | no import found | n/a | Delete Tiles |

Common practice: one plain noun; nobody says "pack" and nobody uses "map file" as a menu noun; an imported file lands in
the same list as the downloads; the action is the word "Import". The only qualifier found is an adjective marking where
it came from ("custom"). Gaia alone keeps imports elsewhere. No surveyed app has a split like ours, so the merge rests on
that absence, not on a source that argues for it.

Indicator: only CalTopo was found showing one: the word "Offline" in a map corner while a user-chosen Offline Mode is on
(`training.caltopo.com/all_users/mobile/offline`). Nothing found says where the current view came from. **Our chip is
original design**; the wording below rests on the code and the audit's findings 5 and 10, not on practice.

Setting or one behaviour (ADR 0044): one behaviour on every axis. Apps do not split on the noun or the one list, and a
wrong word hurts no group of users. No setting is proposed anywhere here.

## What the code says the user needs to tell apart

The survey's note holds that the storage is no business of the user's, but the code shows two differences that do reach
a user, and any wording must keep them true:

1. **May it be cleared?** A pack is never evicted (`areas/AreasScreen.kt:375`); a tile area is kept while pinned, and an
   unpinned one is cleared first (`:352-353`). Pinned tiles have a budget and a download past it is refused
   (`areas/AreaDownload.kt:825`); packs do not count in it (`areas/AreaPicker.kt:339-340`).
2. **Does it route and search?** A pack carries trails and places (`areas/AreaPicker.kt:494`); tile areas do not
   (`:495-496`; `areas/AreaDownload.kt:773`). The survey's "one property" missed this one.

So a row says the first in its state word and the second behind a tap ("With routing and place search" or "Map only").
Neither needs a second noun.

## Options

### Noun V1. One noun: "offline map", and "file" only as an ordinary word

Every row, screen, count and message says offline map. "Area" stays only for the thing being drawn or fitted before it
becomes a map ("Draw an area", "Download this area", which `online/DirectSources.kt:122` already says). "File" appears
only where a file is really in the user's hand, as plain English with no row, screen or heading of its own: the picker's
sentence and a row's detail line ("Imported from files"). The strings already lean this way
(`settings/SettingsPages.kt:719`, `viewshed/ViewshedText.kt:23` say "offline maps").

What it costs: the import sentence still has to say that two files make one map (`packs/PacksScreen.kt:292`), so the
purity is one of headings, not of every sentence. The Storage screen has two rows for two stores and must name them by
the clearing rule that is true of every pack and every area ("Offline maps, outside the budget" and "Offline maps, in the
budget"; a name by capability would be false of a terrain-only pack). A user who passes files around has no word for the
file to search help with, and someone who downloaded a map and wants to copy it to another phone by hand finds no
sentence saying a map is a file (that flow does not exist in the app today).
Who else: OsmAnd, Organic Maps, Google Maps, Mapy; Avenza and Locus with an adjective.
Exit cost: about ten strings to reach V2 (table below); no data. Moving parts: none.

### Noun V2. Two nouns: "offline map", and "map file" for what a user handles by hand

The same, except that where a file is the subject the text calls it a map file, as a named thing: the import action, the
link box, the import result, the Storage row of the archives, the Share screen's list, the web page's operator panel, and
the docs. The row in the list is still an offline map, whichever way it arrived.

What it costs: a second word and a sentence in help saying how they relate ("an offline map can come from a map file").
A map downloaded in the app is on the Storage row "Map files" though the user never held a file, or that row has to say
why. It departs from every surveyed menu; the nearest precedent is an adjective. The retired-noun test gains a term with
allowed sites. What it buys: the owner's and a tester's real workflow gets a name; the Share screen's list is honest,
since a phone sends files; the server's catalog and the docs have a user word to meet ("map file" where they say pack).
Who else: nobody in the sample as a noun; Locus's "imported maps" and Avenza's "custom maps" are the nearest.
Exit cost: the same ten strings back, no data. Moving parts: one more allowed term in the test.

The strings that differ, and all that differ:

| Site | V1 | V2 |
|---|---|---|
| Import action (`packs/PacksScreen.kt:294`, `firstrun/FirstRunCard.kt:122`) | Import | Import map files |
| Link box label (`packs/PacksScreen.kt:279`) | Download from a link | Download a map file from a link |
| Import sentence (`packs/PacksScreen.kt:292`) | Pick a map's files on this phone | Pick map files on this phone |
| First-run body (`firstrun/FirstRunCard.kt:115`) | ...or import maps you have. | ...or import map files you have. |
| No-map note (`MainActivity.kt:182`) | ...or import one (Offline maps). | ...or import map files (Offline maps). |
| Import result (`firstrun/ImportPicker.kt:82`) | Imported <name> | Imported map file <name> |
| Row detail | Imported from files | Imported map files |
| Storage row (`storage/Storage.kt:178`) | Offline maps, outside the budget | Map files |
| Share list empty (`share/ShareScreen.kt:313`) | No offline maps on this phone | No map files on this phone |
| Web operator panel (`tools/demo/index.html:112,113,118,119`) | Build a map here, Map name | Build a map file here, File name |

T220's first-run sentence "Use map files you already have" reads correctly under both: under V1 "files" is the ordinary
word. Rejected variant: "maps" alone as the noun (Organic Maps, OsmAnd). In a map app it does not separate what is kept
from what is on screen, and "No maps" reads as a broken app. It stays open for the chip's label only (below).

### List L1. One list: "Offline maps"

The chip, the Settings row, the Storage rows and the trip card all open one list. It is the Areas sheet's composable
grown, because that already holds the picker's modes, the outlines on the map and the fit above it
(`areas/AreasScreen.kt:99-174`), and every `Areas.screenOpen` caller keeps working; it takes the two heights the Packs
screen has (below). Specification below. Cost: two implementers in sequence, a pure join with its test. Risk: the merged
list carries a lot at the largest font. Exit cost: B1b's inverse, restoring the Packs screen and its fold, the chip's
target, the three links, `POST /packs/ui`'s answers and the tool's taps; the join's one file stays useful; no data.
Moving parts: one debug route.

### List L2. Two lists with clear names

The Packs screen is renamed "Offline maps" and shows every archive; the Areas screen becomes "Map tiles" or similar. A
strings-only change, and the cheapest by far. But a place sits on one list or the other according to a path the picker
chose (`areas/AreaPicker.kt:337`), a region download is on both, and the user has to learn the difference the survey says
no user wants. It keeps the question this task exists to remove. Exit cost: none. Moving parts: none.

### List L3. One list, plus a separate "Add a map" screen

As L1, with the catalog, the link box and the import on a screen of their own. A shorter list, and a home for rare
actions. But it adds a screen name for three rare actions, and the catalog needs the map above it for its outlines
(T53, `packs/PacksScreen.kt:210-215`), which a second sheet would have to reproduce. Exit cost: moving a section. Moving
parts: one more screen in `Screens.ALL`.

### List L4. The list of places only (recorded, ruled out)

The plan's first form: the chip opens the area records. The critic's objection 2 of wave 28 stands: an import opens an
empty list. Listed so that nobody proposes it again.

### Chip C1. Two parts in plain words, with a tap that explains

The state word stays first and the second part says where this view's map is, in words independent of the noun. Table
below. Cost: one task, in one file and its test, plus the tap and two inputs from the phone's network and the records'
covers. Exit cost: strings, and the card's composable. Moving parts: three parameters on `OnlineUi` (`kept`, `network`,
`serverSet`) and one debug route.

### Chip C2. One word per state (the first survey's list)

"Offline map", "Online", "Online, live", "No map data here", "Paused". Shorter, but critic objection 9 stands: it gives
four words to a space of five by five, "Offline map" while the phone is online reads as the connectivity, and the phone's
state disappears exactly where a server has stopped answering. Exit cost: strings. Moving parts: none.

### Chip C3. Shown only when the view is not coming straight from the network

Second survey's suggestion, from CalTopo's single word. It removes a control from a crowded row (audit, finding 9). But
the column under the search box would jump as the user pans in and out of coverage (`TopChips.onlineColumn`,
`MapInsets.onlineChipsAt`, which catalog fits read), and a hiker checking before leaving signal gets no answer from an
absent chip. One app is thin ground. It can be reversed cheaply if the owner regroups the top row next wave. Exit cost:
strings and one condition. Moving parts: none.

## The one list, specified (L1)

**Row model.** `areas/OfflineMapList.kt`, new and pure: `rows(packs, records, coverage, jobs, regionJob, catalog, active, now)`
gives `Row(id, name, bytes, state, detail, actions, bounds, pack, recordId)`, tested in `OfflineMapListTest`. It reads
nothing itself; the sheet's 1.5 s poll calls `PackManager.list()` (cached by size and time, `packs/PackManager.kt:72-76`),
the job snapshots and `PackCatalogClient.rows()`, which answers from the catalog it holds and fetches nothing
(`packs/PackCatalogClient.kt:214-215`), off the UI thread, and asks `Areas.coverage` only for records with no pack, since a
pack-backed record's coverage is a `find` over the same list (`areas/Areas.kt:497-500`). The test iterates every record
state, pack presence and job kind and asserts one row with one word, so both tables below are total by test, and a failure
prints the input that failed.

**The join.** One row per pack name, one per record without a pack:

| Input | Row | Remove does |
|---|---|---|
| Record naming pack P, P on the phone | `pack:P`, named by the newest such record | every record naming P, then P (`Areas.remove`, `areas/Areas.kt:360-373`) |
| Two or more records naming P | one row, as above. Today `Areas.remove` of one deletes P under the other | as above |
| Pack P with no record (import, catalog, link, a nearby phone's pack) | `pack:P`, named P | `PackManager.delete(P)` |
| Record naming P, P absent, a download of P running (`share/PeerSources.kt:479-480`) | `pack:P`, reading the job's progress | cancels the job, removes the record |
| P present, with or without a record, and a job running for P: a fresh download whose vector archive landed first (`packs/PackDownloader.kt:114-115`), an update, a graph-only job (T189) | one row `pack:P`, by the job: Downloading, Updating or "Fetching the trail network". Today it is two rows (`packs/PacksScreen.kt:231,237`) | cancels the job; for a fresh download also deletes P; an update or a graph-only job leaves P until a second Remove |
| Record naming P, P absent, nothing running | `pack:P`, "Not on this phone", Remove only | removes the record |
| Record with no pack (a drawn area, a disc, a corridor, a trip's) | `area:<id>` | `TripCorridors.userRemove`, as today (`:137-142`), so a trip records it |
| Unreadable record (`opaque`) | `area:<id>`, the existing `NeedsNewerCard` | as today |
| A pack download with no pack yet (`PackDownloader`) | `pack:<name>`, its progress or T222's paused or stopped reading | T222's Remove |
| The region job, building or downloading (`service.region`) | `pack:<name>`, its progress; T222's reading if cut off | its cancel |

The "Not on this phone" row exists on phones today: `PackManager.delete` (`packs/PackManager.kt:154-181`) never touches
`Areas`, so a region pack deleted on the Packs screen leaves a card reading "KEPT AS A PACK" at 0% (`areas/Areas.kt:497-500`,
`areas/AreasScreen.kt:202,219`). One removal path through the row ends new ones; old ones show plainly and go on a tap.
The record is not rewritten (ADR 0033: nothing migrates). `POST /packs/delete` keeps its behaviour for the tools.
A typed region name is checked for characters only (`online/RegionDownload.kt:100`), so two records naming one pack can
arise; hence the second row.

**Order.** Running or stopped first, then newest first (`createdAt`, or the pack file's time).

**A row** shows a name, a size and one state word, by the first that holds:

| State word | When |
|---|---|
| Removing 30% | T221's in-memory removal |
| Needs a newer app | `opaque` |
| Downloading 40%, Updating 40%, Building, Fetching the trail network | a job for this row is running, the word by its kind |
| Paused at 40% | T222's persisted pack download |
| Waiting | a record `pending`, or a trip's corridor queued (`areas/TripCorridors.kt:418`) |
| Download stopped | T222's stopped pack job; a record `failed`, `cancelled` or `refused`; any record with an `error` and no job (today's rule, `areas/AreasScreen.kt:230`); the error behind the tap |
| Not on this phone | the join's row of that name |
| Part saved | `partial` (the resume line behind the tap, `areas/AreasScreen.kt:228`) |
| Incomplete | `!info.usable`, except a map-only pack (`packs/PacksScreen.kt:383`) |
| Update available | `Areas.older` for a record (T95), or `CatalogStatus.UPDATE` for a pack in the catalog held; `Areas.older` never fires for a pack, whose record has no `versions` |
| Saved | a pack on the phone, or a pinned record `done` with no error |
| May be cleared | an unpinned record `done` with no error |

**Behind a tap on the row, on the same list:** what it holds ("With routing and place search", "Map only", "Terrain
only"); whether it counts in the budget ("Never cleared; outside the budget", "Counts in the budget"); its shape ("Within 5 km of a point", "Area of 12 km²", the corridor line); the detail level with the zooms and
bounds T221 and T222 fold this wave; the attribution; where it came from, when recorded (`r.source`; a pack with no record
says nothing rather than guessing); "Shown first where maps overlap" for the primary pack. Actions: Show on map, Show
first (`PackManager.activate`, for a usable pack that is not primary), Update, Download again, Retry, Dismiss, Rename (as
today, not for a pack), Keep on this phone or Allow clearing (the Pin toggle, for a record with no pack), Remove.
A row's tap today on the Packs screen jumps to the pack, makes it primary and closes (`packs/PacksScreen.kt:233,368`);
that moves to the two actions, and is on the owner's list. Show on map fits the camera above the list's lower height
without closing it and without changing the primary, as an area row's Show does today (`areas/AreasScreen.kt:232`).

**The list, top to bottom.** Title "Offline maps", the help icon, Close. "Choose a country", "Draw an area". The rows,
or "No offline maps yet. Choose a country or draw an area." The trip width row (`areas/AreasScreen.kt:323-341`). A fold
"More ways to add a map" holding "From server" (the catalog as today, with its map outlines and the fit; `Screens.ALL`'s
section keeps that name), the link box, and Import. It opens by itself when the list is empty. The fold's catalog is the
server's list, so a map already on the phone is on it too, reading "installed", as today (`packs/PacksScreen.kt:267-275`).

**Two heights, the Packs screen's own** (`packs/PacksScreen.kt:210-215`). Full screen when it opens, since every surveyed
app has a full-screen list and this one carries a lot at the largest font; 0.6 of the window (`PacksUi.CATALOG_SHEET`, the
one height kept; `SHEET`'s 0.55 goes, `areas/AreasScreen.kt:282`) when the catalog is open or after Show on map, with the
outlines of all rows on the map above it (a pack with no record gets a box from `info.bounds`) and a "Full list" action to
go back. The country list keeps its 0.8 (`:285`). This replaces the first draft's one sheet at 0.6, on the critic's argument.

**State.** `PacksUi.visible` gets a getter `Areas.screenOpen && !AreaPicker.onMap()` (`areas/AreaPicker.kt:266`), and setting it
sets `Areas.screenOpen`, so `POST /packs/ui` and `POST /areas/ui` both open the one list, and `PacksUi.catalogOpen` opens the
fold and the catalog. A plain delegation would hide the top chips and the first-run card during every picker mode
(`areas/AreaPicker.kt:150,160,175,187,220`). What changes with this getter, listed: the top chips hide over the list where
they stayed over the Areas sheet (`packs/PacksScreen.kt:92`) and over the country list; they stay during a card on the map,
as today; the first-run card hides over the list and the country list where it hid only over Packs (`firstrun/FirstRunCard.kt:86,91`),
and stays during "Download the map around me" (T220 is rebuilding that card, so the build re-reads it). The list becomes a
`LazyColumn`, which the catalog needs anyway, so `POST /packs/ui`'s `firstVisibleItem` and `localRows` (`app/PacksRoutes.kt:56`)
keep their source, `localRows` now counting all rows (a reported change). New debug route: `GET /areas/rows` gives the rows as
JSON, `{rows:[{id,name,bytes,state,detail,actions,pack,recordId}]}`, in the developer's words as every route is (What stays as it
is, below). A row's actions run through the existing routes.

**Control ids (ADR 0053's build step 2 transcribes these; an id is neither stored nor synced).** The chip `packs`, the fold
`packs.add`, its entries `packs.catalog`, `packs.link`, `packs.import`; the Settings row stays `offlineAreas`; the status chip
`online.chip`.

**Verbs.** Download to get one; Remove to delete it (`Delete` at `packs/PacksScreen.kt:344,345,377` goes; OsmAnd says the
same, Organic Maps says Delete, so either is defensible and one must win); Update; Retry; Import. "Keep" stays only in
"Keep on this phone", the toggle, never as a verb for getting. Pin, Unpin, PINNED and NOT PINNED go.

**Screen names.** `Screens.PACKS` becomes `Screens.OFFLINE_MAPS = "Offline maps"`, `ALL` keeps its section "From server".
`ScreenRefs.WORD` becomes an alternation of the multi-word names in `Screens.ALL` with the one-word pattern, in all four
of its regexes (`ScreenNamesTest.kt:102-105`), so "(Offline maps)" and "in Offline maps" are checked and
`theCheckerCatchesUnknownScreens` still holds. "Offline maps" also joins `Screens.ALL[SETTINGS]`'s sections, since the
Settings row bears that label and "Settings › Offline maps" would otherwise fail. The one-word rule is not a user's rule;
"Maps" for the chip alone would break the rule that a chip's label is the screen's name. The Offline areas Settings row's
label comes from the constant (`settings/SettingsLayout.kt:35`; `id` stays `offlineAreas`, ADR 0053). **The chip** says "Offline maps" (`packs/PacksScreen.kt:104`) and opens the sheet. It costs about
50 dp of the search box beside it (`TopChips.searchWidth`, `TopChips.kt:93`; an estimate from the label's length, the
build measures it with T224's `GET /layout`), and the owner's choice on the top row next wave may make it moot. **The
Sources link** (`settings/SettingsPages.kt:539`) opens the catalog, so it says what the first-run card says for the same
act, "Choose from server ›", and calls `FirstRun.chooseFromServer` (`firstrun/FirstRun.kt:127-131`), not its own copy of
those lines. **The Storage rows' links** (`storage/StorageScreen.kt:323,327`) both say "Offline maps ›".

## The status chip, specified (C1)

What the code does today (`online/OnlineOverlay.kt:64-76`): the chip reads `<state word> · <source phrase>` and has no tap
(`:142-149`). `state` is `online`, `offline`, `unknown` or `none` (`online/OnlineFetcher.kt:228-233`,
`online/OnlineService.kt:188`). The source is a memo of how each DEM tile was last served (`online/OnlineTiles.kt:117`, an
LRU of 4096 at `online/OnlineService.kt:93-95`), never cleared when the state changes. That is why "Offline · live from
server" appears: the phrase describes an earlier fetch. And a tile of a downloaded area is a cache hit, so **a user
standing in a map they downloaded reads "from cache"**, the same as a tile they happened to view once. The chip is hidden
with no server and nothing running (`:57`). `notice` is set only in direct mode (`:101`), and Paused needs `online` (`:65`).

The table: the same two-part form, a word per state and a phrase per source, and a new split of the cache source. The dots
are today's. "n/a" is a combination the code cannot reach: Paused needs direct mode; red and "your server" need a server; with no
fetcher there is no `OnlineTiles` (`online/OnlineService.kt:167-168`), so `none` reads only pack or no data (`online/OnlineView.kt:36-37`).

| State (word today → proposed), dot | Pack | Network, public sources | Network, your server | Cache, inside a downloaded map | Cache, otherwise | No data |
|---|---|---|---|---|---|---|
| Online → Online, green | Online · on this phone | Online · from public sources | Online · from your server | Online · on this phone | Online · viewed earlier | Online · no data here |
| Paused → Paused, grey | Paused · on this phone | Paused · viewed earlier | n/a | Paused · on this phone | Paused · viewed earlier | Paused · no data here |
| Offline, a server set, the phone has a network → Server offline, red | Server offline · on this phone | n/a | Server offline · viewed earlier | Server offline · on this phone | Server offline · viewed earlier | Server offline · no data here |
| Offline, a server set, no network → Offline, red (today's word and dot) | Offline · on this phone | n/a | Offline · viewed earlier | Offline · on this phone | Offline · viewed earlier | Offline · no data here |
| Offline, no server set → Offline, grey | Offline · on this phone | Offline · viewed earlier | n/a | Offline · on this phone | Offline · viewed earlier | Offline · no data here |
| Connecting → Connecting, grey | Connecting · on this phone | Connecting · viewed earlier | Connecting · viewed earlier | Connecting · on this phone | Connecting · viewed earlier | Connecting · no data here |
| No server → No server, grey (only while a download runs) | No server · on this phone | n/a | n/a | n/a | n/a | No server · no data here |

Why these: "from the network" is said only under Online, because that is the only time it is true of the next tile. Every
stale network memo reads "viewed earlier", which removes the audit's contradiction. "On this phone" needs no noun, so the
chip does not depend on V1 or V2, and reads rightly beside "Online" where "Offline map" would not (critic objection 9).
"Server offline" says what the red dot has meant since T192, and only when the phone has a network (read as
`online/DirectSources.kt:84-85` reads it), so it never blames a server for a phone out of signal; the dot stays red wherever
it is red today, as this task requires. "No data here" is plain and stays. A fetcher of peers alone (no server, direct off,
`online/OnlineService.kt:146`) has `isDirect` false and is red today with a `peer://` address; it takes the grey row, and its
network column reads "from a nearby phone". The "inside a downloaded map" column asks the records' covers only, a new
`Areas.isRecorded(key)` beside `isPinned`, because `isPinned` also holds navigation's transient pins (`areas/Areas.kt:396-405`),
which have no row to link to. `viewSource` returns the word only (`online/OnlineTiles.kt:337-348`), so it also gives the tile it
matched, and `OnlineUi.make` forms the versioned key off the UI thread. `view.source` in `GET /online` keeps its values and
gains a `kept` boolean.

**A tap** opens one card under the chip, in the overlay's column; a second tap closes it. Two sentences and at most one link:

| State | First sentence | Link |
|---|---|---|
| Online | The phone can reach the map sources. | none |
| Paused | The notice as it reads today (ADR 0023; it moves from its own line, `:151`, only when the card is open) | Settings › Sources › |
| Server offline | Your server at <address> is not answering. It may be off or out of reach. | Settings › Sources › |
| Offline, red | No connection, so your server at <address> cannot be reached. The map still works where it is on this phone. | Settings › Sources › |
| Offline, grey | No connection. The map still works where it is on this phone. | none |
| Connecting | Checking the connection. | none |
| No server | No server is set and the public sources are off. | Settings › Sources › |

| Source | Second sentence | Link |
|---|---|---|
| On this phone | This part of the map is saved on this phone. | Offline maps › |
| From public sources, from your server | This part of the map is coming over the network and is kept only until space is needed. | none (the Download button is already below) |
| Viewed earlier | You viewed this part earlier. It stays until space is needed. | Offline maps › |
| No data here | Nothing on this phone covers this view. | Offline maps › |

`offerDownload` keeps its rule (T220 adds one this wave); over a downloaded tile area the button still offers the box,
because that is the only path to a map with routing. Report: `GET /online` gains `chip: {text, dot, open, detail, link}`;
`POST /online/chip?open=0|1` opens it, so a screenshot can catch it. `OnlineOverlaySnapshotTest` pins the whole table.

Also in this file: `:92` "Saved as pack <name>" → "Saved as an offline map: <name>"; `:156` "Download this region" →
"Download this area" (from `REGION_TITLE`).

## Every user-visible string, by file

Generated by reading the string literals of the app's sources for the retired nouns, then sorted by hand. **B1b**, **B2** and **B3**
are the build tasks below; "V1" text is shown, and V2 differs only by its table above. T220, T221 and T222 reshape several
of these this wave; the build re-reads them.

| File | Lines | Today → proposed | Task |
|---|---|---|---|
| `packs/PacksScreen.kt` | 104, 220, 221, 228, 257, 258, 279, 292, 294, 343-345, 374, 375, 377 | Packs, Region packs, Offline areas chip → the list above; "No packs..." → the empty line; "has no packs" → "has no maps"; "N packs on" → "N maps on" with a singular; Delete → Remove; PRIMARY, ON MAP → the detail | B1b |
| `areas/AreasScreen.kt` | 137, 168, 202, 205, 222, 233, 251, 308, 311, 351-355, 357, 369-371, 375 | Offline areas → Offline maps; "No areas yet."; state words; "Region pack x" → the detail; Pin → the toggle; intro → "Saved maps stay on this phone until you remove them. Every trip keeps the map along its route here."; "Keep each trip offline:" → "Download each trip's map:"; help and `PACK_NOTE` in those words | B1a (row words), B1b |
| `firstrun/Screens.kt` | 12, 41 | `PACKS` → `OFFLINE_MAPS` | B1b |
| `MainActivity.kt` | 182 | → "No offline map here. Download the map around you or choose one from your server (set it in Settings › Sources), or import one (Offline maps)." | B1b |
| `settings/SettingsLayout.kt` | 35 | row label | B1b |
| `settings/SettingsPages.kt` | 539 | link → Choose from server › | B1b |
| `storage/StorageScreen.kt` | 323, 327 | links → Offline maps › | B1b |
| `online/OnlineOverlay.kt` | 64-76, 92, 156 | the chip's table, above | B3 |
| `areas/AreaPicker.kt` | 492, 494-496, 565 | Download this area; "With routing and place search"; "Tiles only: ..." → "Map only: ..."; "Keep offline around here" → "Download around here" (also reaches `tracking/LongPressMenu.kt:181`, `search/SearchOverlay.kt:183`) | B2 |
| `areas/TripCorridors.kt` | 415-419, 426, 429, 432 | Keeping offline → Downloading; Kept offline → Downloaded; Kept offline in part → Downloaded in part; Waiting to keep offline → Waiting to download; Not kept offline → Not downloaded; "(Offline areas)" → "(Offline maps)" | B2 |
| `trips/TripControls.kt` | 273, 274, 276 | Stop keeping → Remove offline map; Keep offline → Download for offline; `CORRIDOR_AREAS` → Offline maps | B2 |
| `trips/Trips.kt` | 566, 569 | "its map pack" → "its offline map"; "the map pack" → "the offline map" | B2 |
| `areas/AreaDownload.kt` | 825 | "Offline maps in the budget would use about X, over its N GB: remove one or raise the budget in Settings › Storage" | B2 |
| `areas/DownloadWorker.kt` | 295 | channel name "Area downloads" → "Map downloads"; the channel id stays | B2 |
| `firstrun/FirstRun.kt` | 77, 108 | "choose a pack from the server" → "choose a map from the server"; "import pack files" → "import a map" | B2 |
| `firstrun/FirstRunCard.kt` | 75, 115, 122 | "Downloading a map..."; the V1 and V2 rows; T220 holds the card's sentences | B2 |
| `firstrun/ImportPicker.kt` | 82 | the V1 and V2 row | B2 |
| `firstrun/Sources.kt` | 41 | "Connected: 1 map on ..." | B2 |
| `about/AboutActivity.kt` | 88, 89, 95, 109 | Region pack → Offline maps; "No offline map is on this phone."; "not recorded in the map"; "$it map" | B2 |
| `options/MapOptionsSection.kt` | 126, 397 | "an offline area" → "an offline map" | B2 |
| `routing/RouteControls.kt` | 907 | "(first use of this map)" | B2 |
| `routing/RouteSession.kt` | 347 | "this map has no trail data" | B2 |
| `search/SearchOverlay.kt` | 79, 81 | "No map data here to search"; "(N maps)" | B2 |
| `settings/SettingsLayout.kt` | 12 | "...offline maps, storage" | B2 |
| `settings/SettingsPages.kt` | 97, 100, 196, 566, 567, 630 | packs → offline maps or maps; "their size, pin, update or remove" → "their size, update or remove" | B2 |
| `share/ShareScreen.kt` | 301, 313, 335, 336 | "N maps"; the V1 and V2 row; "Area: x" → x; "Downloading the area x" → "Downloading x"; T220 holds the counts | B2 |
| `storage/Storage.kt` | 178, 191 | the V1 and V2 row; "Offline areas (pinned)" → "Offline maps, in the budget" | B2 |
| `storage/StorageScreen.kt` | 180, 375-377, 384-387, 403, 409, 418 | "Offline maps are not part of it."; the two help texts, their titles and `at(...)`; "N on this phone; remove one in Offline maps"; "N saved"; "Update in Offline maps." | B2 |
| `update/UpdateOverlay.kt` | 152 | "Your tracks, trips, maps and settings stay." | B2 |
| `viewshed/ViewshedText.kt` | 24 | "download an offline map around this point first" | B2 |
| `reports/ReportRow.kt` | 170 | "place names and map names" | B2 |
| Messages that reach a screen through `$label failed:` (`packs/PacksScreen.kt:205`) or `Import failed:` (`firstrun/ImportPicker.kt:84`): `packs/PackManager.kt` | 108, 158, 328, 330, 373 | pack → map | B2 |
| the same: `packs/PackCatalogClient.kt` | 230, 244, 255, 262, 263, 267 | pack → map | B2 |
| the same: `packs/PackDownloader.kt` | 158, 178, 180, 203, 220 | pack → map; 220 also loses its route ("is already on this phone; remove it first") | B2 |
| `shared/src/commonMain/.../routing/Router.kt` | 392, 566, 618, 619, 674, 675, 918, 937 | "map packs" → "offline maps" on a phone reads wrongly on the server's route, so "the maps" | B2 |
| `shared/src/commonMain/.../routing/ExposureAlong.kt` | 322 | "the map's elevation model" | B2 |

Stay, for developers only: Log lines and thread names; `areas/AheadPrefetch.kt`; `areas/AreaDownload.kt:287,320,484,773`;
`areas/Areas.kt:265,331,666`; `online/RegionDownload.kt:89,100,182,214`; `packs/PackManager.kt:115,155,204,206,241,243,355,358`;
`packs/PackCatalogClient.kt:292,293` and `packs/ShippedGraphs.kt:81,105,123` (JSON reasons, read by the owner of a debug
build); `share/PeerServe.kt`, `share/PeerSources.kt:373`; `TileService.kt:422`; `DystopoApp.kt:116`; `storage/Storage.kt:250,315`;
`routing/RoutePlanner.kt`, `search/PlaceSearch.kt`; `reports/Scrub.kt`; every `*Routes.kt` file.

The Stay list is the sentences; the JSON keys, preference keys, constants' tokens and thread names (`"pack"`, `"pack.name"`,
`"packs"`; about a hundred more literals by the critic's count) are not listed, because the guard's shape rule below skips them.

**The guard.** `RetiredNounsTest` beside `ScreenNamesTest` reads the same literals, by a shape rule: a literal with a space or
a capital first letter (so a `const val` holding a sentence is read, and a lone JSON key is not), skipping `*Routes.kt`, the
arguments of `Log.*(` and `Thread(`. It fails on `\bpacks?\b`, "region pack", "offline area", "keep offline" and the other
retired phrases outside an allow file `src/test/resources/retired-nouns-allow.txt`, whose lines are `file | a fragment of that
sentence | reason`, never a bare word for a whole file, so a new user-visible "pack" in an allowed file still fails. Its message
names the file, the line and both ways out: reword it, or add a line with a reason, which any implementer may do in its own
task, without a hook or a grant. It replaces wave 28's objection 4's grep by a verifier, which would catch one wave only.

**Tests pinned to the old words, by task.** B1b: `firstrun/ScreenNamesTest.kt:70-71,100-105`, `settings/SettingsLayoutTest.kt:48`,
`areas/TripCorridorStatusTest.kt:44` (`corridorHelp`, in `areas/AreasScreen.kt`). B2: `settings/SettingsLayoutTest.kt:89,90`,
`areas/TripCorridorStatusTest.kt:16,20,21,23`, `areas/PolygonAreasTest.kt:112`, `trips/OfflineOfferTest.kt:30,39`,
`areas/RegionPickerTest.kt:47-50`, `areas/MediumDetailTest.kt:90`. B3: `online/OnlineOverlaySnapshotTest.kt:24-34,58`. The keys
`HelpRegistryTest` reads stay.

**Labels the tools tap or read** (T219 owns `tools/android-ui.py` and T220 `tools/android-firstrun.py` this wave; each
new form accepts old and new until the sweep passes, T219's rule):

| Tool line | Today | Becomes | Task |
|---|---|---|---|
| `tools/android-ui.py:1854` | `^Packs$` | `^(Packs\|Offline maps)$` | B1b |
| `tools/android-ui.py:1855-1857` | "From server", tapping its `▸` | taps two headers in order: the fold "More ways to add a map", then "From server" (or `POST /packs/ui?catalog=1`) | B1b |
| `tools/android-ui.py:1865`, `:52`, `:689` | a message and two docstrings | the new names | B1b |
| `tools/android-ui.py:1900` | "INSTALLED", "ACTIVE", "ON MAP", "IN USE", `^(Installed\|Saved as pack)` | adds "Saved", `Saved as` | B1b |
| `tools/android-ui.py:2010` | `Packs` in the chips' names | `Offline maps` | B1b |
| `tools/android-ui.py:1099`, `:1088`, `tools/android-firstrun.py:434`, `:22` | `Connected: \d+ packs? on`, two docstrings | `Connected: \d+ (packs?\|maps?) on` | B2 |
| `tools/android-firstrun.py:518`, `:29` | `^Import pack files$` | `^(Import pack files\|Import\|Import map files)$` | B2 |
| `tools/android-firstrun.py:543` | `Imported pack`, `Import failed` | `Imported` | B2 |
| `tools/android-firstrun.py:476`, `:485` | `Saved as pack` | `Saved as` | B3 |

`Download`, `Resume` and `Retry` (`tools/android-ui.py:1858,1892`), `^Download the map around me$` and `^Show the map around
me$` or T220's new label for it are not in this proposal's scope.

**The web page.** It has no offline list and stays out of the noun on the viewer's side. User-visible: `tools/demo/trips.js:245,269`
"Outside every pack" → "Outside every map", "On pack x" → "On map x"; `:252` "Planned across N packs" → "N maps"; `:563` the
tooltip → ", map x" (B2). Operator-only since T200 (`tools/demo/index.html:807`): `:112,113,118,119,122` "Build a pack here",
"Pack name", "replace a pack of that name", "Build pack here", "for building packs" → the V1 or V2 row (B2), and it keeps its
element ids and `data-control` values. `tools/demo/controls.json` has no such label, and ADR 0053's manifest gains nothing here.

## What stays as it is

Code: `PackManager`, `Areas`, `AreaPicker`, `PacksUi`, `RegionDownload`, file and package names, `Screens` apart from the one
constant. Routes: `/packs*`, `/areas*`, `/online*`, `/peer/areas`, all answers and parameters (`pack=`, `path: pack`). JSON keys:
`pack`, `packs`, `kind`, `content`, `PATH_PACK`, Storage's `PACKS` and `PINNED`. Stored data: `areas.json`, `areas2.json` with their
`pack` field, the packs directory and its `-dem.pmtiles`, `-outdoors.pmtiles` names, the `pack.name` preference, a trip's `pack`.
Synced keys: `pinnedCacheMb`, `tripCorridorM` (`sync/SettingsSync.kt:71`). Ids: the `offlineAreas` row, help keys `areas.offline`,
`storage.packs`, `storage.pinned`. Docs, `docs/SERVER.md`, the server's catalog and the log keep "pack" as the developer's word.
Nothing migrates (ADR 0033), and ADR 0053's prediction that no stored or synced key was renamed holds.

Changes a report must list (ADR 0033): `GET /storage`'s `label` values; `GET /online` gains `chip` and `view.kept`; `POST
/online/chip` and `GET /areas/rows` are new; `POST /packs/ui`'s `localRows` counts all rows. Docs in the build: `docs/reference/android-app.md`, `android-debug-routes.md`
(by T224's rule the coordinator adds lines at the merge if that still holds), `android-online.md`.

## Recommendation

**V1 for the noun, for the owner to choose; L1 and C1 for the rest**, built after wave 28's merges and re-reading every site.
V1 because every surveyed app that has a plain word has only that word, the storage difference has its own honest place (a
state word and a detail line), and the choice is cheap to reverse: V2 is ten strings away. The case for V2 is real and is the
owner's to weigh: the owner and testers do handle these files, which the code cannot show. The rest of the proposal holds under
either. L1 because the join is small and it is the only option that removes the question "which list is it on?". C1 because it
keeps the two facts the chip exists for and states them truthfully, independent of the noun.

**Build tasks, for one wave** (four tasks, by the `wave` skill's sizes; each re-reads its sites after wave 28's merges. Order:
B1a, then B1b; then B3 and B2 at once, B3 merging first, so the guard B2 adds finds B3's words already changed. Each task's
Owns are its own while it runs; a file named twice is named by called-out lines, and a later task takes it after the earlier
one's merge):

- **B1a. The rows.** Owns: new `areas/OfflineMapList.kt` with `OfflineMapListTest`, `areas/AreasScreen.kt`'s `AreaRow` and the
  sheet's list, `areas/AreaRoutes.kt` (`GET /areas/rows`). The Packs screen and its chip stay. Done when: the test is total over
  both tables and prints a failing input; an imported, a catalog, a link, a nearby phone's and a region pack and a drawn area each
  show once on a device, read through `GET /areas/rows` and a screenshot; Remove leaves no record behind and its pack gone.
- **B1b. One list, one name.** Owns: `packs/PacksScreen.kt`, `app/PacksRoutes.kt`, `firstrun/Screens.kt`, `ScreenNamesTest.kt`, the
  remaining strings of `areas/AreasScreen.kt`, the two heights and the fold, `settings/SettingsLayout.kt:35` with
  `SettingsLayoutTest.kt:48`, `settings/SettingsPages.kt:539`, `storage/StorageScreen.kt:323,327`, `MainActivity.kt:182`,
  `areas/TripCorridorStatusTest.kt:44`, the `tools/android-ui.py` rows marked B1b. Done when: `ScreenNamesTest` is green with four
  regexes; screenshots of the list, the fold and the catalog at both font scales and in landscape, at the full height and the
  lower one; every entry of the Settings row, the chip, the Storage links, the Sources link and the trip card opens it; the
  sweep's server step passes.
- **B2. The words.** Owns: every row marked B2 in the string table, `RetiredNounsTest` with its allow file, the test lines marked
  B2, `tools/demo/trips.js`, the five lines of `tools/demo/index.html`, `tools/android-firstrun.py` apart from `:476,485`, and
  `tools/android-ui.py:1088,1099`. Takes the owner's V1 or V2. Done when: `RetiredNounsTest` passes with an allow file of sentences
  only, each with a reason, its line count in the report; `demo-check.py` and the first-run tool with its import step pass.
- **B3. What the status chip says.** Owns: `online/OnlineOverlay.kt`, `online/OnlineView.kt`, `online/OnlineTiles.kt`'s `viewSource`,
  `areas/Areas.kt`'s new `isRecorded`, `app/OnlineRoutes.kt`, `OnlineOverlaySnapshotTest`, `tools/android-firstrun.py:476,485`, one
  `// end` block in `help/Help.kt` if help is added. Done when: the snapshot test pins every cell of both tables; `GET /online`
  reports `chip`; a device reads Online, Paused (the session limit), Connecting and the Offline rows with the network off and on, with
  a screenshot of the card open.

First step: the owner's V1 or V2. **For the owner:**

1. **The noun: V1 (one noun, "offline map") or V2 (a second, "map file", where a file is handled by hand).** The one thing B2
   waits on; the table under Noun V2 is all that differs. Recommended: V1. Is the file you pass around something you want a
   name for on screen?
2. The chip's label "Offline maps" costs about 50 dp of the search box; "Maps" would keep the width, and would be the one screen
   whose title is not its chip. Built as "Offline maps" unless you say otherwise, and moot if you regroup the top row next wave.
3. A row's tap no longer jumps to the map and makes it primary; it opens the row's details, with Show on map and Show first in
   them. Built that way and shown in the report (ADR 0031).
4. The red dot's word becomes "Server offline" when the phone has a network and your server does not answer; with no network it
   stays "Offline", still red as today. Whether a phone simply out of signal should show red at all is yours to change (T192 made
   it grey with no server set; grey here too is one line of B3). The chip also gains a tap.
5. "Remove" wins over "Delete" for an offline map, and "Keep on this phone" with "May be cleared" replaces Pin and PINNED. The
   surveys split on the first; the second is this proposal's own wording. Adjust after the build.
6. The server's operator panel on the web says "Build a map here" and no longer "pack", while `docs/SERVER.md` keeps "pack" as
   the developer's word. If you would rather the operator panel kept "pack", say so; it is one line of B2.
7. The list opens full screen, as the Packs screen does and every surveyed app's list does; it drops to 0.6 of the window, with the
   outlines on the map above, for the catalog and after Show on map. The Areas sheet's map-above-the-list on opening goes.

Predictions (for the ADR; the trigger scan reads these):

- Prediction, check at the first sweep after B2 ships: no screenshot of that sweep's `ui/` set shows a retired noun.
- Prediction, check when the owner has adjusted the build's strings (or by 2027-05-01): fewer than a third of the strings in the
  table above were reworded again, and the V1 or V2 choice was not reversed.
- Prediction, check by 2027-05-01: `retired-nouns-allow.txt` has fewer than sixty lines. If it passes that, the shape rule is wrong.

## What would change this recommendation

- The owner says the file is part of how the app is meant to be used, or testers ask for it by a name: V2.
- A flow arrives that exports a map to a file or sends one by hand: V2 becomes cheap to justify, and the row gains the action.
- B1b's screenshots show fewer than three rows visible at the largest font in landscape at the full height, or the fold's first
  row below the window with few rows: L3, the add actions on their own screen.
- The top row regroups behind a Menu next wave: the chip's label choice falls away and C3 can be weighed again.
- `Areas.isRecorded` per view proves too slow once a second on a full cache: drop the "inside a downloaded map" column to a bounds
  test on the records.
- A third kind of offline data with its own clearing rule or budget (the dwell box of proposal 0055; whoever promotes it checks
  this line): another state word and another Storage row, not another noun; if a user cannot tell from the row what may be
  cleared, this reopens.
- A second client that needs the list or the chip: the pure row model and table move to `shared`.

## Claims to verify

- Only `recordArea` (`online/RegionDownload.kt:228-238`) and a peer's area (`share/PeerSources.kt:480`) write a record naming a
  pack; imported, catalog and link packs write none.
- `PackManager.delete` never touches `Areas`, so a record with an absent pack exists on phones today, and `Areas.remove` of one of
  two records naming a pack deletes it under the other. Two such records need a name given twice: a generated name avoids every
  name in use (`online/RegionDownload.kt:99,125-128`), and whether the picker's Name field can repeat one was not established; the
  join handles it either way.
- `online/OnlineTiles.kt:117` records a pinned area's tile as `CACHED`, so the chip reads "from cache" over a downloaded tile area;
  and nothing clears the memo when the state changes, so a stale `ONLINE` explains "Offline · live from server".
- `Areas.isPinned` can be asked for a DEM tile's key off the UI thread at once a second without a cost that matters, and the key is
  formable where `OnlineUi.make` runs (the critic: yes, once `viewSource` gives the tile; revised to `isRecorded`).
- The "n/a" cells: `notice` is direct only (`online/OnlineOverlay.kt:101`); `none` has no `OnlineTiles` (`online/OnlineService.kt:167-168`,
  `TileService.kt:308`), so it reads only pack or no data; Connecting can read a stale memo after a server change.
- A pack gives routing and search and a tile area does not, as `REGION_PACK_LINE` and `areas/AreaDownload.kt:773` say.
- Every string in the table reaches a user, and every one in the Stay list does not; the list of what reaches a screen through
  `background()` was read at its call sites in `packs/PacksScreen.kt` only.
- `PacksUi.visible` can delegate to `Areas.screenOpen`. **False as first written** (the critic's claim 8); the State paragraph now
  gives the getter and lists what changes.
- `ScreenRefs.WORD` extended to multi-word names misreads no existing string (the critic: holds; four regexes, not three).

The critic verified the rest against the code, with `file:line`, in `0056-critic-review.md`.

## Critic's objections and responses

`0056-critic-review.md`, at a48541c5: 0 blocking, 6 serious, 10 minor.

1. **Serious. The guard would see 190 literals and is lifted by file.** Accepted with a change. The shape rule and per-sentence
   allow lines are in The guard. The critic's "skip a `const val`'s value" is not taken: `REGION_PACK_LINE`, `CORRIDOR_KEEP` and
   `PACK_NOTE` are sentences users read; a `const val` holding a lone token is skipped by the shape anyway. A prediction bounds the file.
2. **Serious. The join misses a present pack with a job, and Update has nothing to go on for a pack.** Accepted. A join row, the
   job's three words, and `catalog` as an input from what the client already holds, which fetches nothing.
3. **Serious. The state-word table is not total.** Accepted. Rows for `failed`, `cancelled`, `refused`, `pending` and an `error`
   without a job; "finished" means `done` with no error; `OfflineMapListTest` iterates the combinations.
4. **Serious. V1's Storage labels and the budget sentence name stores by capability.** Accepted with a change: named by the clearing
   rule, but not as "downloaded areas", since "area" is kept for what is being drawn: "Offline maps, outside the budget" and
   "Offline maps, in the budget", and the budget sentence says "in the budget". V2's row changes only for the first.
5. **Serious. B1 is two tasks.** Accepted: B1a and B1b, with the critic's split.
6. **Serious. The delegation changes behaviour the proposal said it kept.** Accepted: the getter excludes a card on the map, and the
   State paragraph lists the chips, the first-run card and the two route answers.
7. **Minor. Control ids unnamed.** Accepted: named in the specification.
8. **Minor. "Server offline" at the two edges.** Accepted with a change. The critic's grey for a phone with no network is not taken
   as the build: T225's brief keeps the red dot of an unreachable server, so the word changes and the dot does not; the grey is put
   to the owner (item 4). The peers-only fetcher takes the grey row and "from a nearby phone".
9. **Minor. The "downloaded map" column counts navigation's transient pins, and `viewSource` gives no tile.** Accepted: `isRecorded`,
   and `viewSource` gives the tile.
10. **Minor. Test files and the merge order are split without saying so.** Accepted: test lines by task, `:20` added, B3 merges before B2.
11. **Minor. An installed catalog pack shows twice with the fold open.** Accepted: the fold is the server's list and says so; B1a's
    "once" is about the rows.
12. **Minor. Tool lines missed.** Accepted: added to the table.
13. **Minor. Four regexes, and the row's label as a section.** Accepted.
14. **Minor. The pure parts sit in `androidApp`.** Rejected for now: the row model reads `Areas.Record` and `PackManager.Listed`, which
    are app types, so `shared` means new input classes and adapters for a client with no date; PLANNING's hard rule 2 covers what
    clients exchange, not this. The move is one file with its test, and "What would change" names the trigger. Likewise the critic's
    aside on a list of sources for the dwell box: one parameter in one file with one caller, when 0055 is chosen.
15. **Minor. `GET /offline-maps` uses the user's word.** Accepted: `GET /areas/rows`.
16. **Minor. No falsifiable prediction, two boilerplate lines.** Accepted: three predictions, L1's exit cost rewritten, the goldens line
    dropped from B2. The critic's pre-mortem (the stores invisible where it mattered, the allow file growing unread) is answered by the
    "Counts in the budget" detail line, the Storage labels, the third-store line with who checks it, and the allow file's prediction.

The critic's alternative to L1's first form, the Packs screen's two heights with a full-screen list, is taken; the first draft's single
sheet at 0.6 stays here as the record of what was changed.
