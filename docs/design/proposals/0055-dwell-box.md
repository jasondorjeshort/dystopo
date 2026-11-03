# 0055. The dwell box: a place kept offline after you linger zoomed in

Date: 2026-11-02
Status: under review

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
  network-type check; `directOnMobileData` and the direct-sources session limit (`directSessionMb`, 300 MB by
  default, "Online data limit reached for this session") already apply to every fetch the phone makes itself.
- Areas are first-class (ADR 0025 principle 4, ADR 0027): a box or corridor with a zoom range expands to a
  versioned manifest; a box downloaded on the phone is tiles pinned in the online cache by a cover (`AreaPins`);
  the download is one `AreaDownload` job at a time, resumable (ADR 0046) and recorded as an `Areas.Record`.
- Pinned bytes have their own budget, `pinnedCacheMb` (2 GB by default, up to 1 TB), checked before an area
  downloads and refused visibly (ADR 0030); a trip's corridor (T130, `tripCorridorM`, 1 km each side, 0 off)
  is such a record linked to its trip and counted there (the owner's choice of 2026-10-23).
- Pinned tiles keep the content version they were fetched at; refresh is explicit or on the route (ADR 0030).
- The medium detail exists as a per-kind zoom cap on the area (`demMaxZoom`, ADR 0047).
- The online overlay already owns the view's status line ("Online", "Paused", "Download this region") and the
  Offline areas sheet already lists every area with Pin, Unpin, Remove and the corridor width row (T75, T130).
- Everything on the phone is a cache in front of a source and every cache is bounded (ADR 0025 principles 1
  and 3; one cache budget with per-kind ceilings, ADR 0034).
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
trigger numbers, the box and cache-not-pin. Nothing in it needs a network-type check.

## Options

Every option fetches the place through the existing area path (an `AreaOfInterest` box, its manifest, the
`AreaDownload` queue, the cover pinned in the online cache), so each is a retention and budgeting rule, not a
second download engine. The dimensions the options share, decided once in the recommendation: the trigger, the
box, the detail, the daily limit and the indicator.

### A. Nothing new: the viewed tiles stay in the online cache by recency

What it is. The online cache (`onlineCacheMb`, LRU) already holds every tile the user looked at; raise its
default and let recency do the keeping. Cost: none. Risks: it fails the idea on both counts. The deeper zooms
and the margin around the view were never fetched, so offline the place is exactly the screens the user saw
and nothing beside or beneath them; and one long browse elsewhere evicts the place (T85 measured a cold z12
view at 20 to 35 MB; a 256 MB cache holds about a dozen such views). No indicator is possible because
nothing is kept on purpose.
Exit cost: none; it is the state today.
Moving parts: none.
Settings: none beyond `onlineCacheMb`.

### B. A dwell box as an automatic pinned area with an expiry, against the pinned budget

What it is. A dwell writes an ordinary `Areas.Record` marked as automatic (an `origin` field, as `tripId`
marks a corridor), downloaded and pinned like a drawn area, counted against `pinnedCacheMb`, refused with a
note when the budget would be passed (the corridor's rule, T130), released after an expiry unless the user
taps Keep on the sheet, which makes it an ordinary area. It mirrors T130 exactly, so the Offline areas
sheet, the versions rule, the resume chain and the budget refusal are reused as they are.
Risks: a refusal for something the user did not ask for is noise, and worse, an automatic box can fill the
budget ahead of a trip corridor the user does want (a corridor made the next evening is refused because last
week's browsing holds the budget). Both need a release-the-automatic-first rule, which is option C's budget
in disguise with a shared ceiling.
Exit cost: Remove the automatic records (one line on the sheet); their tiles fall into the LRU.
Moving parts: the `origin` and `viewedAt` fields on the record, the dwell trigger, the expiry sweep, the
sheet's section, the indicator; no cache change.
Settings: the daily limit (below) and the expiry would be settings; the pinned budget is one already.

### C. A dwell box as a cache kind, "Recent places": its own ceiling, oldest-viewed released first, never refused

What it is. A dwell writes the same automatic record as B, pinned by the same cover, but accounted as its own
kind: a ceiling `recentPlacesMb` on the Storage screen beside "Online tiles" and "Offline areas (pinned)",
with whole places released oldest-viewed first when a new place would pass it. Nothing is ever refused: the
newest place always fits by releasing the oldest; the user-pinned areas and the trip corridors are never
touched and never crowded, because `pinnedCacheMb` counts only records the user asked for. Keep on a recent
place promotes it to an ordinary pinned area (the size and the pinned budget shown first, ADR 0030). Viewing a
recent place again moves it to the newest, so the places that matter stay without a timer; an expiry is still
there as a backstop so a phone kept at the ceiling does not hold a year-old box forever.
How it meets the constraints: it is ADR 0025 principle 1 taken literally (a cache in front of a source, with a
retention smarter than LRU), bounded by a ceiling the Storage screen shows (principle 3, ADR 0034), built from
the area objects (principle 4), with no refusal to lift because nothing fails closed except the daily data
limit, which says how to lift it. Cost: one more kind on the Storage screen and in `Storage.json`; the
release order; the accounting (a record's `bytes` sums tiles a corridor may also hold, so the kind's bytes
are an upper bound, stated on the screen as the pinned row's are today).
Risks: a second budget to understand (the Storage screen has four rows already); the release is silent, so a
place the user believed kept can be gone (the sheet shows it, and Keep is the answer); the accounting bound.
Exit cost: as B, plus removing the Storage row and the key; no data migrates, because the record is the
ordinary area record with two optional fields older builds ignore.
Moving parts: as B, plus the `recentPlacesMb` ceiling and the release pass before a download.
Settings: the daily limit and `recentPlacesMb` (below); the expiry a constant.

### D. The trigger is repeated visits, not dwell

What it is. B or C's storage, but a place is kept only when the same cell has been viewed closely on two or
more distinct days. Far less data for one-off browsing (a look at a far-away range costs nothing) and no dwell
timer to tune. Risks: it misses the case the idea is for: planning in the evening, walking there tomorrow
with no signal, viewed on one day only. It can be a refinement on top of a dwell rule (a second-day view at any
zoom refreshes and promotes), not a replacement.
Exit cost: the visit counter on the record.
Moving parts: a per-cell visit log by day.
Settings: none; the visit count would be a constant.

## Recommendation

**Option C, with D's second-day refresh folded in as a detail, and the shared dimensions as follows.** The
decision is the owner's (ADR 0031: this is what the user sees and touches); the build waits for it.

1. **The trigger.** The camera comes to rest (`OnlineService.onViewChanged`, the idle hook T41 added) at zoom
   12 or deeper and stays within one z12 cell for 20 s (the survey's figures), or the same z12 cell has been
   in view at zoom 12 or deeper for 60 s in total over the last ten minutes (the pan-and-zoom reading of a
   basin, which never sits still for 20 s). Below z12 the user is finding the place, not reading it; at z12
   the detail rule (3) makes a dwell nearly free, and from z13 the contours take the DEM at full resolution
   and the trails read (T26). A second close view of a cell on a later day refreshes it and moves it to the
   newest (D folded in). Not while navigating (the ahead prefetch, T75, covers that), not with the app in the
   background, not when the view's tiles come from a pack or a pinned area already covers the box
   (`Areas.coverage`), not under the phone's battery saver, not when the daily limit is reached. The seconds
   and the zoom are constants with a debug override, not settings: no app publishes them, none lets the user
   set them, and a wrong value costs seconds, not data.
2. **The box.** The z12 tile cells the viewport touches, at most four (a phone at z13 touches two to four; a
   tablet is clipped to the four nearest the centre). One record per cell: a cell's id is its geometry and
   zooms (ADR 0027), so a second dwell beside the first adds only the new cells, and the release and the
   accounting are per cell. A z12 cell is 6 to 8 km on a side at mid latitudes.
3. **The detail: what you looked at.** DEM zooms 8 to the deepest zoom viewed during the dwell (at most 15),
   vector to `min(that, 14)`, through the area's own range, no `demMaxZoom`. A dwell at z13 costs about 1 MB
   a cell (four z13 DEM tiles at about 160 kB and the vector); a dwell at z15 about 15 MB a cell (the sixty-four
   z15 and sixteen z14 DEM tiles: `AreaManifest.estimate`, the Rainier average). The user keeps the detail
   they read and pays for nothing deeper; the 5 km disc of T142 (61 MB at full detail) is the comparison.
4. **The daily limit (ADR 0023).** `dwellKeepMbPerDay`: what the dwell rule may fetch between one local
   midnight and the next, 100 MB by default (the survey's figure: about six z15 cells, two close looks, or
   an evening of planning at z12 and z13), the choices Off, 50 MB, 100 MB, 300 MB, 1 GB (the session limit's
   ladder plus the survey's 50); Off is the feature's switch, so on/off and the limit are one row. When
   reached, the indicator says so and names the lift (below). One row on the Offline areas sheet under the
   corridor width row, "Keep places you look at", synced like `tripCorridorM`, with a help text
   (`HelpRegistryTest`). It is a setting because the cost of a wrong default falls on metered users and not
   on others (ADR 0044), and because ADR 0023 wants the limit raisable where it is seen.
5. **The ceiling.** `recentPlacesMb`, 1 GB by default (about seventy z15 cells, or a season of evenings),
   a row on the Storage screen as the other kinds, with the same choices; whole cells released oldest-viewed
   first before a new one downloads; a cell unviewed for 90 days is released at the next run (a constant).
   Whether the kind sits under ADR 0034's one total follows that ADR's rule for a non-derived kind, as the
   online tiles do.
6. **Versions (ADR 0030, amended for this kind only).** A recent place records its versions like any area and
   is served at them. A new dwell on a cell whose source has moved on fetches the current version and replaces
   the record (then `dropUnheld` frees the old tiles): the refresh is "on a dwell", beside "explicit or on the
   route", because a browsing cache that never shows new data defeats its purpose. It counts against the
   daily limit.
7. **The indicator.** The online overlay's status line, where "Online" and "Download this region" already
   are: "Keeping this place offline · 24 MB" while the cells download, "Kept offline" for a few seconds after,
   nothing when nothing happens. At the daily limit: "Not keeping more places today: 300 MB since midnight.
   Raise it in Offline areas." Under battery saver: "Keeping places paused: battery saver". The Offline areas
   sheet gains a section "Recent places (kept from browsing)": one line with the count and the bytes, opened
   to the cells by name (the search index's name for the cell's centre, as `defaultName` does) and last
   viewed, each with Keep and Remove, and Remove all. The strings are the owner's (ADR 0031).
8. **Agent path.** `POST /areas/dwell?lat=&lon=&zoom=[&seconds=]` plays a dwell; `GET /areas/dwell` reports
   today's bytes and the limit, the ceiling and its bytes, the records, and the last trigger decision with its
   reason (which exclusion fired); `POST /settings?dwellKeepMbPerDay=&recentPlacesMb=`. In `shared`: the cell
   snapping, the trigger as a pure state machine fed timestamps, the release order, each with tests.

**First step.** The shared pure parts (the cells of a viewport, the trigger, the release order) with tests;
then the Android record fields, the budget pass and the routes; the sheet and the overlay last, screenshots in
the report.

**Where it departs from the prior art, and why.** Every app that keeps viewed tiles does so silently, for
every view, with no stated limit; this proposal keeps on dwell only, fetches beyond what was viewed (the whole
cell at the detail read), shows what it kept and bounds it twice (a daily data limit and a storage ceiling),
because the design goal is an offline map the hiker can rely on, which the vendors of silent caches say theirs
is not. From the survey's own recommendation it departs in three places: the storage ceiling replaces the
keep-time setting (the ceiling and oldest-viewed release already bound storage and age together; a second knob
for the same thing costs a row, a help text, a sync key and a test, ADR 0044), with 90 days as a constant
backstop; one record per z12 cell replaces "the viewport plus a margin", so repeated dwells dedupe by id and
the release is fine-grained; and the detail follows the zoom viewed rather than a fixed z10 to z15 range, so a
z12 look costs well under a megabyte a cell instead of the survey's 25 MB cap for every box.

## What would change this recommendation

- The survey finds the established apps keep viewed areas as user-visible saved areas with a refusal when
  full, and users expect to manage them: then B, the corridor's rule, for consistency with what hikers know.
- The owner wants one budget to think about: then B with release-the-automatic-first, the ceiling becoming a
  share of `pinnedCacheMb`.
- Measured dwell boxes come out far from the estimates (a z15 cell well over 30 MB in mountain terrain): then
  the default detail caps the DEM at z14 (`demMaxZoom`, ADR 0047) and the daily default drops to 50 MB.
- The direct-sources path (no server) cannot render a z15 cell's DEM within the heap guard in reasonable time
  (T127, T138): then the dwell rule applies to a configured server only until it can, stated on the sheet.
- The owner reads the daily limit as a second gate on data (ADR 0023 is about network type, not about
  limits; this proposal reads it as the ADR's own steer, "visible limits"): then the daily row stays as the
  switch and the figure.

## Claims to verify

- `Areas.Record` has `tripId`, `pinned`, `bytes`, `versions`, `cursor`, `autoResume` and the opaque rule for
  unknown kinds only; unknown optional fields are read and written back unchanged (`androidApp/.../areas/Areas.kt`,
  ADR 0041 §2).
- The online cache's eviction skips any key a pinned cover holds, and transient pins exist (`AreaPins`,
  `Areas.pinTransient`, used by `AheadPrefetch` for 1 km segments, 300 m buffer, z12 to z15).
- `pinnedCacheMb` is 2 GB by default, range to 1 TB, checked in `AreaDownload` before a download and by
  `TripCorridors` for a corridor; a refusal is a note on the trip card (`TripCorridors.kt` KDoc, ADR 0030).
- `AreaManifest.estimate`: DEM 160 kB a tile (the Rainier pack's 1404 tiles at z8 to 14), vector 10 kB at
  z13 and z14; T142 measured the 5 km disc at Paradise at "About 61 MB (520 tiles)" at full detail
  (`docs/PLANNING.md`, the 2026-10-25 T142 entry).
- `OnlineService.onViewChanged` runs on camera idle with the view and its source (pack, online, cached);
  `onMapIdle` runs at most once a second (`OnlineService.kt`, T41, T110, T144).
- `directSessionMb` defaults to 300 MB with the choices 100, 300 and 1000 (`DirectPrefs.SESSION_MB_CHOICES`).
- The Offline areas sheet carries the corridor width row and its help; `SettingsGroup.DATA` is "Sources,
  offline areas, storage" (`AreasScreen.kt`, `SettingsLayout.kt`).
- A z12 tile is 40,075 km / 4096 times the cosine of the latitude on a side: 6.6 km at 47°, 7.8 km at 37°.
- The Prior art table's rows, each against its cited source.

## Critic's objections and responses

(none yet)
