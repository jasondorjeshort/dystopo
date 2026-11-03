# 0055. The dwell box: a place kept offline after you linger zoomed in

Date: 2026-11-02
Status: under review (revised on the critic's review of 2026-11-02; the decision is the owner's, ADR 0031)

## Question

When the user browses the map online and lingers zoomed in on a place, the phone should fetch and keep the
elevation, contour and vector data for that place without being asked, so that online and offline blend
(`docs/ideas/2026-10-13-auto-download-when-zoomed-in.md`; the owner, 2026-10-23: "the route corridor first
... the dwell box after it as a short proposal"). T130 built the corridor. This proposal decides what a dwell
box is (a cache kind or a pinned area), what triggers it, how big it is and to what detail, what bounds the
data it costs (ADR 0023: a visible limit, never a network-type gate), what the user sees, and which of these
are settings (ADR 0044). In ten years the rule should still read as one sentence a hiker believes: "the
places you look at closely stay on your phone, inside a budget you can see".

## Constraints that matter here

- Platforms: JVM and Android now, iOS later (`shared` commonMain stays pure Kotlin); licensing
  (`docs/PLANNING.md` › Licensing: permissive dependencies, LGPL only in `tilebuild`, no GPL);
  offline first; every feature agent-drivable (devtool command, debug route); agents write all code;
  the design goals (ADR 0025).
- No Wi-Fi gates (ADR 0023): a new feature that downloads comes with a visible limit the user can raise, never a
  network-type check. The direct-sources session limit (`directSessionMb`, 300 MB by default, per device,
  "Online data limit reached for this session") counts what the phone's own source reader fetches for the view;
  area downloads run exempt from it (`AreaDownload.run`, ADR 0045 §5), so it is not a brake on an area path.
- Areas are first-class (ADR 0025 principle 4, ADR 0027): a box, corridor or polygon with a zoom range expands
  to a versioned manifest. A box is a region pack (`AreaDownload.start` sends it to the region build); a
  corridor or a polygon is tiles fetched into the online cache and pinned by a cover (`AreaPins`), one
  `AreaDownload` job at a time, handed to a WorkManager chain with a "Downloading" notification and resumed
  for days (ADR 0046), recorded as an `Areas.Record` whose reader and writer use fixed field sets.
- The pinned total is one number: `TileCache.pinnedBytes` sums every entry `keep(key)` holds, and `keep` is
  `Areas.isPinned` over one `AreaPins` of every pinned record plus the transient covers. `pinnedCacheMb`
  (2 GB by default, up to 1 TB, per device) is checked against it before an area downloads and refused visibly
  (ADR 0030); a trip's corridor (T130, `tripCorridorM`, 1 km each side, 0 off, synced) is such a record linked
  to its trip and counted there (the owner's choice of 2026-10-23); raising the budget retries a refused one.
- The one existing automatic fetch from how the map is used is the ahead prefetch (T75, `AheadPrefetch`):
  1 km segments, 300 m buffer, z12 to z15, fetched in process through the online fetcher, pinned by
  `Areas.pinTransient` with no record, released by prefix when navigation stops.
- Pinned tiles keep the content version they were fetched at; refresh is explicit or on the route (ADR 0030).
  The manifest and the cover grow an area by one tile at every zoom (contour tiles sample past their edges) and
  list the DEM from the source's minimum zoom (8 direct, 6 server). The medium detail is a per-kind zoom cap
  (`demMaxZoom`, ADR 0047).
- Without a server (the default) the phone renders DEM tiles itself to `directMaxZoom`, 12 by default, and
  synthesises deeper DEM tiles from the z12 ancestor; the fetcher caches those too, so a z14 or z15 DEM tile in
  direct mode carries no detail past z12 and costs cache bytes but no network bytes of its own.
- The online overlay is a column of short lines under one chip ("Online", "Paused", "Download this region");
  the Offline areas sheet lists every record with Pin, Unpin, Remove and the corridor width row, under an intro
  that says pinned areas stay until unpinned or removed (T75, T130).
- Everything on the phone is a cache in front of a source and every cache is bounded (ADR 0025 principles 1
  and 3; one cache budget with per-kind ceilings for the derived and online caches, pinned bytes outside it,
  ADR 0034, whose §6 keeps the `TileCache` API unchanged).
- What humans see and touch is built in alpha and shown in the report; the owner adjusts (ADR 0031). This
  proposal is such a thing: the decision waits for the owner.

## Prior art

From the benchmark survey of 2026-11-02 (ADR 0039; a read-only Sonnet agent on public documentation, launched
before the options were written; the sources are listed per row, third-party ones marked). Its headline: no app
it could verify keeps a viewed area as a pinned, user-visible offline area from dwell or zoom alone; what
exists is a silent, evictable view cache, or automation attached to areas the user already chose. No source
publishes a dwell time, a zoom threshold, a daily cap or a "kept automatically" badge.

| App | Keeps what you viewed? | Lifetime and limits | Cellular | Indicator, setting | Source |
|---|---|---|---|---|---|
| Google Maps | A small silent cache of recently viewed areas (third-party); offline areas come from "recommended maps" built from trips and the user's own downloads, which the user confirms | downloads expire and auto-update on Wi-Fi when 15 days or fewer remain; the expiry figure is disputed between sources | Wi-Fi only vs mobile for downloads; auto-update on Wi-Fi | an "Update offline maps" notification; "Auto-update offline maps" toggle | support.google.com/maps/answer/6291838 (official; silent on the view cache); howtogeek and mikegravel.org (third-party) |
| Apple Maps (iOS 17) | No automatic cache of browsing found; downloads are a box the user adjusts, size shown first | "Optimise Storage" deletes unused offline maps after an unstated period; Automatic Updates on by default | a Wi-Fi-only option under Downloads | none for a cache; "Only Use Offline Maps" switch | support.apple.com/en-au/105084 |
| Gaia GPS | Viewing online fills a temporary cache the vendor says not to rely on | cleared with the app cache or on low storage | n/a | none; Saved › Maps lists true downloads only | overlandbound.com forum (third-party, snippet) |
| CalTopo | Areas viewed online are cached: "even if you haven't pre-downloaded an area, it may still be available when offline" | not stated | n/a | none | blog.caltopo.com, 2018-09 (official) |
| onX Backcountry | Official pages: manual areas at a chosen resolution, device storage the only limit; a retailer says viewed areas cache | not stated | n/a | none | onxmaps.com offline-maps feature page; eastmans.com (retailer) |
| OsmAnd | Every displayed online raster tile is stored in a per-source SQLite cache | per-source expiry in minutes (blank: never); kept until cleared; no size cap, per-source "Calculate Size" | n/a | a Cache category in the Local tab with a size per source; "Clear all tiles" | osmand.net/docs/user/map/raster-maps; docs.osmand.net personal/maps-resources |
| Locus Map | "Cache maps" stores displayed online maps in an internal database; "Cached maps only" shows no network | a per-map cache timeout in the map description; no size figure | n/a | a blank map where nothing is cached; both are toggles, defaults not found | docs.locusmap.app maps_settings, maps_download |
| Organic Maps, maps.me | No viewed-area cache found; zooming into an undownloaded region suggests the region download | downloads do not expire until updated | n/a | a download prompt | search snippets only; unverified |
| Komoot | No automatic download: a tour's offline toggle, regions by hand | until removed | Wi-Fi "recommended" | a download icon on the tour | support.komoot.com article 10356476920986 |
| Mapy.com | No viewed-area cache found; downloaded regions auto-update while charging (2026-09), Wi-Fi-only switch below it | replaced about seven times a year | a Wi-Fi-only switch | settings text | help.mapy.com/offline-maps; svetandroida.cz (Czech tech press) |
| AllTrails | Manual "Download custom areas" (paid tiers) | until deleted | n/a | the Saved tab | support.alltrails.com article 37758009767444 |
| Not established | Outdooractive, Avenza, Topo GPS, Garmin Explore, FATMAP, Backcountry Navigator, OruxMaps, Windy Maps: no public source reached | | | | |

**Common practice.** (1) A silent, evictable cache of everything viewed, with undocumented limits and
lifetime; the honest vendors tell users not to rely on it and to download. (2) Persistent offline areas are
explicit downloads with the size shown first. (3) Automation attaches to what the user already chose:
auto-update of downloaded areas, recommendations from trips that the user still confirms, unused-area cleanup.
(4) Wi-Fi and cellular switches sit on downloads and updates (ruled out here, ADR 0023).

**Outliers.** OsmAnd and Locus give the strongest user control over the view cache: a per-source expiry or a
cache toggle and a visible size. Google is the only one that proposes areas on its own, and it keeps them as a
list the user confirms.

**The survey's recommendation** (its numbers are its own; no source publishes any): trigger on dwell, not any
view (a view at z12 or deeper held about 20 s, or a second visit within 7 days); a box of the viewport plus a
margin, about 25 MB at most; cache, not pin, with a longer life and a "kept" mark, promotable to a pinned area
in one tap, so the pinned budget keeps meaning "I asked for this"; a visible daily limit of 100 MB by default
(50, 100, 300 MB, off) shown as "Used 42 of 100 MB today" with a limit-reached message mirroring the session
limit's; a quiet indicator ("Saving this area for offline", then "saved for offline until <date>. Keep it");
settings for on/off, the daily limit and the keep-time (7, 30, 90 days, default 30); one behaviour for the
trigger numbers, the box and cache-not-pin. Nothing in it needs a network-type check. The critic could not
check the rows against their sources (no web access; the report is gitignored research output); they stand as
the survey's.

## Options

The dimensions the options share, decided once in the recommendation: the trigger, the cell, the detail, the
daily limit and the indicator. Options B to E differ in what the kept thing is and how it is accounted.

### A. Nothing new: the viewed tiles stay in the online cache by recency

What it is. The online cache (`onlineCacheMb`, LRU) already holds every tile the user looked at, and the
cache guard of ADR 0034 never evicts what is in view; raise the default and let recency do the keeping. Cost:
none. Risks: it fails the idea on both counts. The deeper zooms and the margin around the view were never
fetched, so offline the place is the screens the user saw and nothing beside or beneath them; and one long
browse elsewhere evicts the place once it leaves the screen (T85 measured a cold z12 view at 22 to 28 MB at
Rainier and 39 to 46 MB at Mont Blanc; a 256 MB cache holds under a dozen such views). No indicator is
possible because nothing is kept on purpose.
Exit cost: none; it is the state today, and the baseline the other options' lines are measured against.
Moving parts: none.
Settings: none beyond `onlineCacheMb`.

### B. A dwell box as an automatic pinned area record, against the pinned budget

What it is. A dwell writes an `Areas.Record` marked as automatic (an `origin` field, as `tripId` marks a
corridor): a polygon of the cell (a box is a region pack, so the tiles path needs the polygon kind of ADR 0041,
manifest version 2), downloaded through `AreaDownload`, pinned like a drawn area, counted against
`pinnedCacheMb`, refused with a note when the budget would be passed (the corridor's rule, T130), with Keep on
the sheet making it an ordinary area. It reuses the sheet, the versions rule and the resume chain as they are.
Risks: a refusal for something the user did not ask for is noise, and an automatic record can fill the budget
ahead of a trip corridor the user does want; the WorkManager chain shows a "Downloading" notification for
every cell and resumes a cut-short cell for days, past the daily limit and the background exclusion; the
record's reader and writer have fixed field sets, so an older build drops `origin` at its next save and the
cell becomes a nameless permanent pinned area (ADR 0033 §3); `plan` offers it the drawn-area wording; a cell
whose edges are tile edges doubles its cover unless shrunk by an epsilon that then becomes part of every id.
Exit cost: Remove the automatic records (one control on the sheet); their tiles fall into the LRU; the
`origin` field stays readable by every build that wrote it.
Moving parts: the record fields, the dwell trigger, the sheet's section, the indicator, and the chain, the
notification and the resume as they are today for every record.
Settings: the daily limit (below); the pinned budget is one already.

### C. A dwell box as a cache kind, "Recent places": its own ceiling, oldest-viewed released first

What it is. B's record, but accounted as its own kind: a ceiling `recentPlacesMb` on the Storage screen
beside "Online tiles" and "Offline areas (pinned)", whole places released oldest-viewed first when a new one
would pass it, never refused, promoted to an ordinary pinned area by Keep. The user-pinned areas and the
corridors would never be crowded because `pinnedCacheMb` would count only what the user asked for.
Why it does not hold as written (the critic's objection 1): the pinned total is one number computed from a
boolean `keep(key)`; a record pinned by the same cover counts against `pinnedCacheMb` by construction and
shows under the pinned row. Making the split real is a cache change: a kind-returning `keep`, per-kind pinned
bytes, a rule for a key under two covers (a corridor and a cell overlap routinely; the margins guarantee it),
and changes to `recountPinned`, the eviction skip, the Storage rows and `budgetRefusal`, against ADR 0034 §6's
unchanged `TileCache` API. It also adds a fourth Storage row with its own ceiling outside the total that
ADR 0034 spent a task creating, and its per-record `bytes` never add up with the pinned row's.
Exit cost: as B, plus the Storage row, the key and the cache change reverted.
Moving parts: as B, plus the kind-aware cache and the release pass.
Settings: the daily limit and `recentPlacesMb`.

### D. The trigger is repeated visits, not dwell

What it is. B, C or E's storage, but a place is kept only when the same cell has been viewed closely on two
or more distinct days. Far less data for one-off browsing and no dwell timer to tune. Risks: it misses the
case the idea is for, planning in the evening and walking there tomorrow with no signal, viewed on one day
only. It is a refinement on top of a dwell rule (a second-day view refreshes and promotes), not a replacement.
Exit cost: the visit log.
Moving parts: a per-cell visit log by day.
Settings: none; the visit count a constant.

### E. A recent-places store in its own file, pinned through the same list, fetched like the ahead prefetch

What it is. The shape the ahead prefetch already has, with persistence. A dwell writes a cell into
`files/areas/recent-places.json` (as T130 keeps its state in `trip-corridors.json`): its z12 tile index, the
deepest zoom kept, the cover's versions, bytes fetched, first and last viewed. The store's covers are added to
the one `AreaPins` list beside the records and the transient pins, so the cache's `keep` and the pinned total
are unchanged in kind: recent places count inside `pinnedCacheMb`, the number the cache already computes, and
the Storage screen shows them as a line under the pinned row ("of which 12 recent places, 180 MB · Remove
all"). The policy makes them never crowd what the user asked for: whenever a user's download (a corridor, a
drawn area, a country) would not fit the pinned budget, recent places are released oldest-viewed first before
any refusal; and the store keeps itself under a soft ceiling by the same order. The tiles are fetched in
process through the online fetcher (the prefetch's loop: four threads, skipped while an `AreaDownload` job
runs, not exempt from the session limit), with no record, no manifest file, no WorkManager hand-over, no
notification and no resume cursor: a cell cut short stays partial in the store and the next dwell on it
finishes it (cached tiles cost nothing). Keep on a cell writes an ordinary polygon record of the cell (ADR
0041), its size and the budget shown first (ADR 0030), and drops it from the store. Older builds never read the
file; `areas.json` does not change.
How it meets the constraints: ADR 0025 principles 1 and 3 (a cache in front of a source, bounded by a budget
the Storage screen shows), one pinned total and one refusal sentence (ADR 0030, the corridor's `refusals`
retry untouched), no cache change (ADR 0034 §6), no record format change (ADR 0033), no notification for a
fetch the user did not ask for, the pure parts (the cell of a view, the trigger, the release order) in `shared`
with tests. Cost: a second fetch loop beside `AreaDownload` (the prefetch already is one), a store with its own
file and its own release, a section on the sheet fed from the store rather than from records.
Risks: the store's bytes and the pinned total are both honest but the line under the row is a share of it, not
a separate budget, which has to be said in the help text; a browsing cache without a resume cursor fetches a
cell in one go or leaves it partial, and the user never sees a progress bar for it (by design); the list of
looked-at places is a browsing history on the phone (named as such, with Remove all).
Exit cost: delete the file and its key (one Remove all); the tiles fall into the LRU at the next recount; no
data migrates; the sheet's section and the Storage line go.
Moving parts: the store and its file, the dwell trigger on the camera-idle hook, the fetch loop, the release
policy in `budgetRefusal`'s path, the battery-saver read (`PowerManager.isPowerSaveMode`, new to the app), the
section on the sheet, the overlay's lines, two settings keys, the debug routes.
Settings: the daily limit and the soft ceiling (below).

## Recommendation

**Option E, with D's second-day refresh folded in as a detail, and the shared dimensions as follows.** The
decision is the owner's (ADR 0031: this is what the user sees and touches); the build waits for it.

1. **The trigger.** The camera comes to rest (`OnlineService.onViewChanged`, the idle hook T41 added) at zoom
   12 or deeper and stays within one z12 cell for 20 s (the survey's figures), or the same z12 cell has been
   in view at zoom 12 or deeper for 60 s in total over the last ten minutes (the pan-and-zoom reading of a
   basin, which never sits still for 20 s). Below z12 the user is finding the place, not reading it; from z13
   the contours take the DEM at full resolution and the trails read (T26). A second close view of a cell on a
   later day refreshes it and moves it to the newest (D folded in). Exclusions, each reported by name in
   `GET /areas/dwell` as the last decision's reason: navigating (the ahead prefetch covers it), the app in the
   background, the view's tiles from a pack, every tile of the cell already pinned (`Areas.pins().contains`),
   an area download running (skipped, not queued), the session limit reached, the daily limit reached, the
   phone's battery saver on. The seconds and the zoom are constants with a debug override, not settings: no
   app publishes them, none lets the user set them, and a wrong value costs seconds, not data.
2. **The cell.** The z12 tile cells the viewport touches, at most four (a phone at z13 touches two to four; a
   tablet is clipped to the four nearest the centre). One store entry per cell, keyed by its tile index, so a
   second dwell beside the first adds only the new cells, and the release and the accounting are per cell. The
   cover is built from the tile index (`TileSet` rows at each zoom, grown by the one-tile margin as the
   manifest does), never from a geometry, so there is no edge to floor; Keep writes the polygon of the cell
   inset by one constant epsilon, stated once beside the store's version. A z12 cell is 6 to 8 km on a side at
   mid latitudes (6.7 km at 47°, 7.8 km at 37°).
3. **The detail: what you looked at, capped.** DEM zooms from the source's minimum to the deepest zoom viewed
   during the dwell, at most 14 (the first draft's own "What would change" bullet fired on the critic's
   arithmetic: a z15 cell lists about 197 DEM tiles, about 31 MB, with the margins; capped at z14 it lists
   about 97, about 15 MB, of which the 36 z14 tiles are the marginal cost when the shallower ones are in the
   cache from the browse); in direct mode the DEM stops at `directMaxZoom` (12), because deeper tiles there
   are copies of the z12 ancestor and the map makes them again offline; vector to `min(zoom viewed, 14)`.
   A z12 dwell lists about 45 DEM tiles with margins (nine at each of z8 to z12), nearly all already cached
   by the browse, so it costs little beyond the vector; a z13 dwell lists 61 DEM tiles, about 10 MB, of which
   the 16 z13 tiles are new. The daily limit counts bytes fetched, not bytes listed.
4. **The daily limit (ADR 0023).** `dwellKeepMbPerDay`: what the dwell rule may fetch between one local
   midnight and the next, 100 MB by default (the survey's figure; six to twelve z14-capped cells, or an evening
   of planning at z12 and z13), the choices Off, 50 MB, 100 MB, 300 MB, 1 GB (the session limit's ladder plus
   the survey's 50); Off is the feature's switch, so on/off and the limit are one row. When reached, the
   indicator says so and names the lift (below). One row on the Offline areas sheet under the corridor width
   row, "Keep places you look at", per device like `directSessionMb` and the budgets (a data cap is the
   phone's plan's, not the person's), with a help text (`HelpRegistryTest`). It is a setting because the cost
   of a wrong default falls on metered users and not on others (ADR 0044), and because ADR 0023 wants the
   limit raisable where it is seen. The ladder's 50 MB is there for the owner to pick if 100 reads as too much.
5. **The ceiling and the release.** Recent places count inside `pinnedCacheMb`. `recentPlacesMb` is the soft
   ceiling on their share, 500 MB by default (a quarter of the default pinned budget; about thirty z14-capped
   cells), a line under the pinned row on the Storage screen with the budgets' choices, per device. Release is
   oldest-viewed first, whole cells, in two cases: the store passes its ceiling after a dwell, and a user's
   download would not fit the pinned budget (released before `budgetRefusal` refuses, so an automatic cell
   never costs the user a refusal). Release is a pin-list change and one recount, not an index pass per cell.
   There is no expiry: a place read in October and walked in February is still there unless the space was
   needed, and the ceiling says when it was. A release is visible: the section's intro ("Places you looked at
   closely are kept here until the space is needed for newer ones; Keep makes one permanent"), a line "Released
   N places since <date>" under the ceiling, and `GET /areas/dwell`.
6. **Versions (ADR 0030, amended for this store only).** A cell records the versions its tiles were fetched at
   and is served at them like any pinned cover. A new dwell on a cell whose source has moved on fetches the
   current version and replaces the entry (then `dropUnheld` frees the old tiles): the refresh is "on a dwell",
   beside "explicit or on the route", because a browsing cache that never shows new data defeats its purpose.
   It counts against the daily limit.
7. **The indicator.** The online overlay's column, where "Online" and "Download this region" already are, one
   short line each: "Keeping this place offline · 24 MB" while the cells fetch, "Kept offline" for a few
   seconds after, nothing when nothing happens. At the daily limit: "Not keeping more places today (100 MB).
   Raise it in Offline areas." Under battery saver: "Keeping places paused while battery saver is on." The
   Offline areas sheet gains a section "Recent places" fed from the store: one line with the count, the bytes
   and the release line, opened to the cells by name (the search index's name for the cell's centre, as
   `defaultName` does) and last viewed, each with Keep and Remove, and Remove all; outlines and per-cell detail
   only while the section is open. The help text says what the section records (a list of places looked at
   closely, on this phone only) and that Remove all clears it. The strings are the owner's (ADR 0031).
8. **Agent path.** `POST /areas/dwell?lat=&lon=&zoom=[&seconds=]` plays a dwell; `GET /areas/dwell` reports
   today's bytes and the limit, the ceiling, the store's bytes and cells, releases since, and the last trigger
   decision with its reason; `POST /settings?dwellKeepMbPerDay=&recentPlacesMb=`; a debug override of the
   trigger constants. In `shared`: the cells of a viewport, the trigger as a pure state machine fed timestamps,
   the release order, each with tests; and a JVM test or devtool report that lists a cell's manifest at z12, z13
   and z14 at Rainier's latitude, direct and server, so the figures in (3) are the manifest's before the build.

**First step.** The shared pure parts (the cells of a viewport, the trigger, the release order, the cell
manifest report) with tests; then the store, the fetch loop, the release policy in the budget path and the
routes; the sheet's section and the overlay's lines last, screenshots and the new strings in the report. A
finding to record outside this task: T130's corridors in direct mode already fetch synthesised z14 and z15 DEM
tiles.

**Where it departs from the prior art, and why.** Every app that keeps viewed tiles does so silently, for
every view, with no stated limit; this proposal keeps on dwell only, fetches beyond what was viewed (the whole
cell at the detail read), shows what it kept and bounds it twice (a daily data limit and a ceiling), because
the design goal is an offline map the hiker can rely on, which the vendors of silent caches say theirs is not.
From the survey's own recommendation it departs in three places: the ceiling replaces the keep-time setting
(the ceiling and oldest-viewed release bound storage and age together; a timer would also drop the
October-to-February place, the idea's own case); one entry per z12 cell replaces "the viewport plus a margin",
so repeated dwells dedupe and the release is fine-grained; and the detail follows the zoom viewed, capped at
z14 and at the direct renderer's zoom, instead of a fixed range under a 25 MB cap. From the owner's corridor
rule it departs in one place, deliberately: a corridor is refused when the budget is full because the user
asked for it and should hear the answer; a recent place is released instead, because nobody asked.

## What would change this recommendation

- The owner wants recent places as records on the sheet like corridors, with a refusal: then B, with the
  release-the-automatic-first policy kept, the chain's notification suppressed for automatic records and the
  `origin` field protected by a manifest version older builds keep opaque.
- The cell manifest report (8) shows a z14-capped cell well over 20 MB at Rainier's latitude from a server:
  then the default cap drops to z13 (ADR 0047's medium) and the daily default to 50 MB.
- A real phone shows the 20 s rest at z12 firing on idle panning several times an hour, or never on a planning
  evening (the debug override measures it): then the constants move, or the trigger becomes cumulative only.
- The owner reads the daily limit as a second gate on data rather than ADR 0023's own steer of visible limits:
  then the daily row stays as the switch and the figure, and the ceiling alone bounds the rest.
- The owner wants no kept list of looked-at places on the phone at all (the browsing-history reading): then
  A, with `onlineCacheMb` raised and the idea closed.

## Claims to verify

Each checked by the critic on 2026-11-02 against the file and line named in its review; the figures corrected
here are the critic's.
- `Areas.Record` has `tripId`, `pinned`, `bytes`, `versions`, `cursor`, `autoResume`; `read` and `recordJson`
  use fixed field sets and only an opaque record is written back raw (`androidApp/.../areas/Areas.kt`).
- `TileCache.pinnedBytes` is one total over the entries `keep(key)` holds; `keep` is `Areas.isPinned` over one
  `AreaPins` of every pinned record plus the transient covers (`TileCache.kt`, `Areas.changed`).
- A `Box` goes to `service.region.start`; `plan` refuses a box; the region-as-tiles path is a polygon ring
  (`AreaDownload.kt`, `AreaPicker.regionArea`; ADR 0027).
- `AreaDownload.run` wraps a direct-mode download in `direct.exempt` (ADR 0045 §5).
- `DirectSources` renders DEM to `DEFAULT_MAX_ZOOM` 12 and synthesises deeper tiles from the z12 ancestor,
  which the fetcher caches.
- `AreaManifest.tileItems` and `AreaPins.cover` grow the area by one tile at every zoom; `localManifest` lists
  DEM from the source's minimum zoom (8 direct, 6 server); `AreaManifest.estimate` is 160 kB a DEM tile (the
  Rainier pack's 1404 tiles at z8 to 14); T142 measured the 5 km disc at Paradise at "About 61 MB (520 tiles)".
- `TileCover.box` floors the east and south edges into the neighbours for a box whose edges are tile edges.
- `AheadPrefetch` fetches in process and pins through `pinTransient` with no record (1 km segments, 300 m
  buffer, z12 to z15, four threads).
- Every `AreaDownload.start` hands over to the WorkManager chain; the worker shows "Downloading <name>";
  `ResumeCheck.unfinished` resumes partial tiles records (ADR 0046).
- `pinnedCacheMb` is 2048 MB by default, range to 1 TB, not synced; `tripCorridorM` is synced;
  `directSessionMb` defaults to 300 with the choices 100, 300 and 1000, not synced by design.
- `OnlineService.onViewChanged` runs on camera idle with the view and its source; `onMapIdle` at most once a
  second; the overlay is a column of short lines under one chip.
- A z12 tile is 40,075 km / 4096 times the cosine of the latitude on a side: 6.67 km at 47°, 7.81 km at 37°.
- The Prior art rows, each against its cited source (not verified by the critic; a residual).

## Critic's objections and responses

The review is `0055-critic-review.md` (2 blocking, 7 serious, 5 minor; its recommendation: B's accounting
with C's release policy, the store in its own file, fetched the prefetch's way). Every objection is answered;
the recommendation moved from C to the new option E, which is the critic's shape.

1. **Blocking: the split accounting does not exist in the cache.** Accepted with change. C is kept as the
   record with the cache change stated honestly; the recommendation is E, one pinned total with the release
   policy over the automatic share.
2. **Blocking: a box is a region pack; the record must be a polygon with unstated consequences.** Accepted with
   change. E keeps no record and builds the cover from the tile index (no edge to floor); Keep writes a polygon
   inset by one stated epsilon; B's text carries the consequences; the Constraints paragraph is corrected.
3. **Serious: the byte figures are the proposal's arithmetic, not the manifest's.** Accepted with change. The
   figures are the critic's (197 tiles and about 31 MB for a z15 cell, 61 and about 10 MB at z13, with margins
   and the source minimum); the first draft's own bullet fired, so the DEM is capped at z14 by default; the
   daily limit counts bytes fetched; a cell manifest report on the JVM is the build's first step so the defaults
   rest on `tileItems`.
4. **Serious: in direct mode the detail rule buys nothing past z12 and the limit is spent on copies.** Accepted:
   the DEM stops at `directMaxZoom` in direct mode; the same fact for T130's corridors goes to a finding.
5. **Serious: "two optional fields older builds ignore" is false; the downgrade is a silent misread.** Accepted:
   E keeps the store in its own file; `areas.json` does not change; the Exit cost is rewritten.
6. **Serious: the chain, the notification and the resume defeat the exclusions; the job model is unstated.**
   Accepted: E fetches in process, no hand-over, no notification, no resume; a dwell during a job is skipped,
   not queued; a cut-short cell stays partial and the next dwell finishes it.
7. **Serious: area downloads are exempt from the session limit.** Accepted: the dwell fetch is not exempt, the
   session limit is an exclusion reported by name, and the Constraints paragraph is corrected.
8. **Serious: the 90-day expiry defeats the idea's use case and is redundant.** Accepted: no expiry; the
   ceiling releases oldest-viewed first when space is needed, and the departure paragraph says why no timer.
9. **Serious: the ceiling's release has no message under an intro that promises the opposite.** Accepted: the
   section's own intro sentence, the "Released N places since <date>" line, and the route report it.
10. **Minor: the daily cap should be per device.** Accepted: both keys per device, like `directSessionMb` and
    the budgets.
11. **Minor: the battery-saver pause is an unlisted moving part with its lift outside the app.** Accepted:
    listed in E's Moving parts; the line says the pause is while battery saver is on; the route reports it.
12. **Minor: factual slips.** Accepted: the T85 figures by area, `Areas.pins().contains` as the primitive, the
    overlay as a column of short lines, the `SettingsGroup` quote dropped, the unfireable bullet replaced.
13. **Minor: the sheet and the release scale with cells.** Accepted: one section fed from the store with a
    summary line; outlines and per-cell detail only while open; release is a pin-list change and one recount.
14. **Minor: the section is a browsing history and should say so.** Accepted: the help text and the section
    say what it records; Remove all stays; the "no kept list at all" case is a What-would-change bullet for
    the owner.
