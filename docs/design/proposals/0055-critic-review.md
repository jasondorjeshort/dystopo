# Critic review of proposal 0055: the dwell box

Date: 2026-11-02
Reviewed: `docs/design/proposals/0055-dwell-box.md` at commit d877608e (Status `under review`), read-only, against the
code on branch task/T215, the ADRs it cites, `docs/PLANNING.md`'s dated log and the JVM arithmetic. The Prior art
table could not be checked against its sources (no web access in this review; the survey's report is gitignored), so
its rows are taken as the survey's and not verified here.

## Verdict in one paragraph

The question is right, the trigger design is careful, the daily limit is the ADR 0023 brake done properly, and the
agent path is complete. The recommendation (option C) fails on two facts the proposal did not check in the code: the
pinned bytes it wants to split into two kinds are one number the cache computes from a boolean (`TileCache.keep`), so
"`pinnedCacheMb` counts only records the user asked for" is a cache rewrite the option says it does not need; and an
`AreaShape.Box` is a region pack in this code base, not pinned tiles, so the "dwell box" has to be a polygon record
with the file, version and wording consequences that brings. Below those, the byte figures the defaults rest on are two
to ten times under what `AreaManifest.tileItems` would list, the default no-server configuration synthesises every DEM
tile past z12 (so the detail rule buys nothing for terrain there), and the "two optional fields older builds ignore"
are dropped by every readable-record save, turning a recent place into a permanent pinned area on a downgrade. Option
B's accounting (one pinned total) is what the code measures; C's release order is the good idea in C, and it fits on top
of B as a policy over automatic records. Revise, do not build as written.

## Claims verified

| Claim | Found | Where |
|---|---|---|
| `Areas.Record` has `tripId`, `pinned`, `bytes`, `versions`, `cursor`, `autoResume`; the opaque rule for unknown kinds only | True for the fields. **False** that unknown optional fields on a readable record are written back unchanged: `read` takes a fixed set and `recordJson` writes a fixed set; only `opaque?.raw` is written back raw | `<repo>/androidApp/.../areas/Areas.kt` 58 to 88 (fields), 97 (`opaque?.raw ?: recordJson()`), 99 to 110 (`recordJson`), 152 to 165 (`read`) |
| Eviction skips keys a pinned cover holds; transient pins exist and `AheadPrefetch` uses them for 1 km segments, 300 m, z12 to 15 | True | `Areas.kt` 202 to 205, 421 to 432; `AheadPrefetch.kt` 38 to 41, 181 to 185; `TileCache.kt` 27 to 28, 183 to 199 |
| `pinnedCacheMb` 2 GB default, range to 1 TB, checked in `AreaDownload` and by `TripCorridors`, refusal a note on the trip card | True. Default 2048 (`AppSettings.kt` 517), range to `BudgetPlan.RANGE_MB.last` = 1 TB (471; `BudgetPlan.kt` 48); checked at `plan` and twice in `start`/`run` (`AreaDownload.kt` 289, 334, 393); `TripCorridors.notes` and its `refusals` map (KDoc lines 13 to 15, fields `notes`, `Refusal`). Also: raising `pinnedCacheMb` retries a refused corridor (`SettingsRoutes.kt` 31) |
| `AreaManifest.estimate`: DEM 160 kB (Rainier, 1404 tiles z8 to 14); T142's 5 km disc "About 61 MB (520 tiles)" | True | `AreaManifest.kt` 312 to 318; `docs/PLANNING.md` line 698 |
| `onViewChanged` on camera idle with the view and its source; `onMapIdle` at most once a second | True | `OnlineService.kt` 105 to 106, 524 to 534, 546 to 558 (`< 1000` at 549); `OnlineView.sourceFor` returns pack, online, cached or none |
| `directSessionMb` 300 by default, choices 100, 300, 1000 | True. And **not synced by design** ("what one phone may download is that phone's business") | `DirectPrefs.kt` 7, 30 to 31; `DirectSources.kt` 999 |
| The sheet carries the corridor width row and help; `SettingsGroup.DATA` is "Sources, offline areas, storage" | Row and help true (`AreasScreen.kt` 159, 311, 323 to 335, 357 to 371). The summary string is "Sources (server, mobile data), offline areas, storage" (`SettingsLayout.kt` 12); a misquote |
| A z12 tile is 6.6 km at 47°, 7.8 km at 37° | True: 40,075.017 / 4096 × cos φ gives 6.67 km and 7.81 km |
| T85 measured a cold z12 view at 20 to 35 MB | Rainier only: 21.6 to 28.0 MB after T85; Mont Blanc 38.8 to 46.4 MB; T88 then 17.5 MB at Rainier | `docs/PLANNING.md` lines 963 to 966 and the T88 row |
| "A box downloaded on the phone is tiles pinned in the online cache by a cover" (Constraints) | **False.** A `Box` goes to `service.region.start` (a pack); `plan` refuses a box; the tiles path for a region is a polygon ring of the box | `AreaDownload.kt` 287, 319 to 323; `AreaPicker.kt` 468 to 475 (`regionArea` → `AreaDraw.area(regionRing(box))`); ADR 0027 "A box is a region pack" |
| The session limit "already appl[ies] to every fetch the phone makes itself" (Constraints) | **False for area downloads**: in direct mode the walk runs under `direct.exempt` | `AreaDownload.kt` 363 to 366; ADR 0045 §5 |
| The medium detail exists as `demMaxZoom` | True | ADR 0047; `AreaOfInterest.kt` 81, 112 to 121 |
| Not stated, found: direct mode renders DEM to `maxZoom` 12 by default and **synthesises deeper tiles** from the z12 ancestor; the fetcher caches them | `DirectSources.kt` 165 to 167, 319, 332, 337, 473 to 481, 904 |
| Not stated, found: `tileItems` and `AreaPins.cover` use a margin of one tile at every zoom; `localManifest` lists DEM from the source's minimum (8 direct, 6 server), not the area's `minZoom` | `AreaManifest.kt` 290 to 293; `AreaPins.kt` `cover(area, margin = 1)`; `AreaDownload.kt` 786 to 788; `OnlineTiles.kt` 415; `DirectSources.kt` 897 |
| Not stated, found: `TileCover.box` floors `lonToX(east)` and `latToY(south)`, so a box whose edges are tile edges takes the east and south neighbours at every zoom | `TileCover.kt` 266 to 275 |

## The strongest case against C, and for B (or the shape the proposal missed)

**Against C.** The proposal's one argument for C over B is that the pinned budget should "keep meaning I asked for
this", so an automatic box never crowds a corridor. But the pinned total is not a sum over records: it is
`TileCache.pinnedBytes`, the bytes of every entry for which `keep(key)` is true, recomputed by `recountPinned` whenever
the pins change (`TileCache.kt` 14 to 16, 59 to 73), and `keep` is `Areas.isPinned`, a boolean over one `AreaPins` built
from every record with `pinned && pack == null` plus the transient covers (`Areas.kt` 395 to 404). `budgetRefusal` reads
that total (`AreaDownload.kt` 289, 334, 393); the Storage screen's "Offline areas (pinned)" row shows it (`Storage.kt`
155, 191). A recent-place record "pinned by the same cover" therefore counts against `pinnedCacheMb` by construction and
refuses the user's next corridor, which is the harm C exists to prevent, and the Storage screen shows its bytes under
the wrong row. To make C true, `keep` has to answer *which kind* holds a key, with a rule for a key under two covers (a
corridor and a recent cell overlap routinely; margins guarantee it), and `pinnedBytes`, `byPrefix(pinned = )`,
`evictOldest`'s skip, `clearUnkept`, `recountPinned`, the Storage rows and `budgetRefusal`'s inputs all change. That is a
cache change, which option B's Moving parts line says it does not make and option C inherits ("as B, plus..."). The
proposal's own words on the "accounting bound" are the tell: a number known to be wrong on the screen, written down as a
cost rather than a stop.

A second cost of C is a second budget to explain. ADR 0034 spent a task collapsing two caps into one total because two
knobs "had to add up"; C adds a fourth Storage row with its own ceiling outside the total, beside a pinned row outside
the total, and the ADR's rule for "a non-derived kind" is invoked without saying what it decides.

**For B with C's release order.** B's accounting is what the cache already measures. Its stated risk (last week's
browsing refuses tomorrow's corridor) is real, and the proposal's answer to it, "release the automatic records first",
is correct and cheap: at `budgetRefusal` time, when a user's download would not fit, release automatic records
oldest-viewed first until it fits, then refuse only if that was not enough; at dwell time, keep the automatic records
under a soft ceiling (a share of `pinnedCacheMb`, or a number shown as a line under the pinned row: "of which N recent
places, X MB · Remove all") by the same release order. One total, one row, one refusal sentence, and the corridor's
`refusals` retry (`TripCorridors`) works unchanged because the total is the same number. The proposal lists this as what
"the owner wants one budget" would yield; it should be the recommendation on the code's facts, not on the owner's taste.

**The shape the proposal missed.** `AheadPrefetch` is the project's one existing automatic fetch from how the map is
being used: it fetches through the online fetcher, pins through `pinTransient` with no `Areas.Record`, no manifest file,
no `AreaDownload` job, no WorkManager hand-over, no notification, and it releases by prefix. A dwell is closer to that
than to a trip corridor. A "recent places" store could be `files/areas/recent-places.json` (as `trip-corridors.json`
holds T130's state): cells with cover, versions, bytes, last viewed; pinned by adding their covers to the `AreaPins`
list; fetched in process with the prefetch's loop (skipped, not queued, while an area download runs); promoted to an
ordinary `Areas.Record` only when the user taps Keep. Older builds never read the file, nothing in `areas.json` changes,
no notification, no chain, no per-cell manifest files, and the sheet gets one section fed from one object. The cost is
a second fetch loop beside `AreaDownload` (the prefetch already is one) and the loss of the resume cursor, which a
browsing cache does not need. The proposal rules out "a second download engine" in one line; the engine already exists.

**Against A.** The proposal's case against A is right as far as it goes (no margin, no deeper zooms, LRU evicts). Two
corrections: ADR 0034's `CacheGuard` never evicts what is in view or ahead on a navigated route, so the "exactly the
screens the user saw" are in fact protected while on screen; and A's honest exit cost and moving parts (none) are the
baseline the other options' lines should be measured against, not boilerplate.

## Hidden coupling: what this decision quietly decides

- **The cache's `keep` hook becomes kind-aware** (C only; above). That is a decision about `TileCache`'s contract that
  ADR 0034 §6 said is unchanged.
- **The record is a polygon**, version 2, in `areas2.json`, id `polygon:...`, and `userShape()` is true for it, so
  `plan` offers it the drawn-area refusal sentence and the less and medium alternatives (`AreaDownload.kt` 292 to 298,
  841 to 845, 857 to 858). Either the dwell path skips `plan` (then the budget check at `start`, 334, still uses the
  drawn-area sentence) or the wording of ADR 0041 §5 is reused for an area the user never drew.
- **The tile-edge trap.** A cell whose geometry is exactly a z12 tile is a box whose east and south edges floor into
  the neighbours (`TileCover.kt` 266 to 275): 2 × 2 at z12 before the margin, 3 × 3 at z13, so the cover and the
  manifest are roughly double the proposal's count unless the cell is shrunk by an epsilon or the cover is built from
  tile indices. Then "a cell's id is its geometry" is the id of a slightly smaller box, and a later change to the epsilon
  changes every id (the `MAX_BUFFER_M` lesson of ADR 0041).
- **The WorkManager chain, the notification and the resume** (ADR 0046). Every `AreaDownload.start` hands over to the
  chain (`AreaDownload.kt` 357); the worker asks for the foreground with "Downloading <name>" on every run
  (`DownloadWorker.kt` 121, 304; ADR 0046 §4); `ResumeCheck.unfinished` resumes any tiles record left `downloading` or
  `partial` (`ResumeCheck.kt` 283), in the background, for days. So a dwell cell interrupted by the daily limit, the
  background or a lost network is resumed by a chain that knows nothing of the daily limit, battery saver or the
  foreground exclusion; and each cell shows a system notification for a fetch the user did not ask for. One job at a
  time (`AreaDownload.kt` 324) also means a dwell during a country download is refused with a 409 sentence meant for
  routes, and four cells are four sequential jobs.
- **The session limit.** Area downloads are exempt from `directSessionMb` (`AreaDownload.kt` 365; ADR 0045 §5), so a
  dwell in direct mode fetches after the user has seen "Online data limit reached for this session". The daily limit is
  then the only brake, and the proposal's Constraints paragraph believes there are two.
- **Direct mode's DEM.** Every DEM tile past `DirectPrefs.maxZoom` (12) is an upsampled copy of its z12 ancestor,
  cached as a tile. A z15 dwell's 136 z14 and z15 DEM tiles in direct mode carry no detail past z12 and could be made
  offline from the z12 tile; the daily limit is spent on them. T130's corridors already pay this (a finding to record,
  outside this task), but a per-day budget makes it visible.
- **`AREAS_INTRO`** reads "Pinned areas stay on this phone until you unpin or remove them" (`AreasScreen.kt` 308). A
  record on that sheet that the ceiling releases silently makes the sentence false.
- **The sheet's cost.** `Areas.coverage` is a manifest walk per record, polled by the sheet (`Areas.kt` 476, ADR 0045
  Consequences); every area's outline is drawn on the map while the sheet is open (`AreasScreen.kt` 77 to 78);
  `Areas.remove` is one pass over the whole cache index per record (`Areas.kt` 376 to 386). Thirty to seventy cells
  multiply all three; a release of N cells is N index passes unless batched.
- **Sync.** `tripCorridorM` is synced (`SettingsSync.kt` 69, 143); `pinnedCacheMb` and `directSessionMb` are not. A
  daily data cap synced to a tablet on another plan is the per-device case ADR 0044 names.
- **The Storage total.** C's new kind sits outside ADR 0034's total, like the pinned row; the proposal defers the
  question to "that ADR's rule for a non-derived kind", which is a rule about floors and eviction order inside the
  total, not about whether a kind is inside it. The decision is made by omission.

## Refusals, pauses and fail-closed paths: who lifts them, from where, and whether the message says so

| Mechanism | Lift | Where | While running | Message names it | Objection |
|---|---|---|---|---|---|
| Daily limit reached | Raise or Off on the Offline areas sheet; `POST /settings` | sheet, route | yes | yes ("Raise it in Offline areas") | none; but a dwell under `exempt` runs past the *session* limit with no message (obj. 7) |
| Battery saver pause | The phone's battery saver (system quick settings) | outside the app | yes | "battery saver", not where | minor (obj. 11): say the lift is the phone's setting; no code reads `isPowerSaveMode` today |
| Exclusions: navigating, background, pack view, already pinned | None needed; no-ops | `GET /areas/dwell` reason | yes | no user message | acceptable; the resume defeats "background" (obj. 6) |
| One job at a time (a download running) | Wait | none | yes | none | minor: say "skipped, not queued" (obj. 6) |
| The ceiling's silent release | Keep (promote) before it happens; raise `recentPlacesMb` | sheet, Storage | yes | **none when it happens**; the sheet's intro promises the opposite | serious (obj. 9) |
| The 90-day expiry | None but a visit; Keep before it | none | yes | none | serious (obj. 8) |
| The four-cell clip | None | none | yes | none | minor: harmless, say so |
| Older build drops the fields | None; the record is now an ordinary pinned area | none | n/a | none | serious (obj. 5) |

## Pre-mortem

It is 2036. "Recent places" is the row on the Storage screen that support answers most questions about: its number
and the pinned row's never add up, because a key under a corridor and a cell is counted to whichever cover was asked
first, and the kind-aware `keep` hook that made the split possible grew a third and fourth case when drawn areas and
synced areas arrived. Nobody extended ADR 0034's total to it, so a 1 GB default ceiling plus a 2 GB pinned budget plus
a 1 GB cache budget is the footprint a new phone gets. On the phones that never set a server, which is most of them, the
daily 100 MB was spent for years on z14 and z15 PNGs upsampled from z12 terrain, until someone measured a cell and
found half its bytes were copies. And the first-week bug report was a downgrade: a tester went back one alpha, the
`origin` field vanished at the next save, and forty nameless "Drawn area" records sat pinned in the budget with no
expiry. The line that should have caught all of it is in option C's Cost: "the accounting (a record's `bytes` sums tiles
a corridor may also hold, so the kind's bytes are an upper bound, stated on the screen as the pinned row's are today)".
A proposal that knows its number is wrong before building has found its blocking objection and filed it under Cost.

## Objections

1. **Blocking. The split accounting does not exist in the cache, and making it exist is a cache change the option
   denies.** Evidence: `TileCache.pinnedBytes` is the bytes of entries `keep(k)` holds (`TileCache.kt` 14 to 16, 60 to
   73); `keep` is `Areas.isPinned` over one `AreaPins` of every `pinned && pack == null` record (`Areas.kt` 202 to 205,
   395 to 404); `budgetRefusal` and the Storage row read that total (`AreaDownload.kt` 289, 334, 393; `Storage.kt` 155,
   191). A recent-place record pinned by the same cover counts against `pinnedCacheMb` and crowds the corridor C says it
   never touches. Resolves: either adopt B's one total with C's release order (below, Recommendation), or state the
   cache change honestly (a kind-returning `keep`, per-kind pinned bytes, a rule for a key under two covers, the Storage
   and `budgetRefusal` changes) and cost it in Moving parts, and say what ADR 0034 §6's "unchanged `TileCache` API"
   becomes.

2. **Blocking. A box is a region pack; the dwell record must be a polygon (or a new path), with consequences the
   proposal does not carry.** Evidence: `AreaDownload.start` sends a `Box` to `service.region.start` (319 to 323) and
   `plan` refuses one (287); ADR 0027 "A box is a region pack"; the code's own region-as-tiles path is a polygon ring
   (`AreaPicker.kt` 468 to 475). Consequences unstated: manifest version 2, `areas2.json`, a `polygon:` id,
   `userShape()` true (the drawn-area refusal sentence and the detail alternatives in `plan`), and the tile-edge
   doubling of `TileCover.box` (266 to 275) for a cell whose edges are tile edges. Resolves: say "a polygon of the z12
   cell, shrunk by an epsilon stated once (or a cover built from the tile index)", carry the version and file
   consequences into the Exit cost, and decide whether the dwell path calls `plan` at all.

3. **Serious. The byte figures behind every default are the proposal's arithmetic, not the manifest's.** Evidence:
   `tileItems` and `AreaPins.cover` add a one-tile margin at every zoom (`AreaManifest.kt` 293; `AreaPins.kt` `cover`);
   `localManifest` lists DEM from the source's minimum zoom, 8 direct or 6 server, not the area's `minZoom`
   (`AreaDownload.kt` 786 to 788); a whole run records `bytes` as fetched plus already cached (468). One z12 cell to z15,
   edges handled, margin 1: 9 DEM tiles at each of z8 to z12, 16 at z13, 36 at z14, 100 at z15 = 197 tiles, about 31.5 MB
   (215 and 34 MB from a server), not "about 15 MB"; a z13 dwell lists 61 DEM tiles, about 9.8 MB, not "about 1 MB". The
   daily 100 MB then holds about three z15 cells, not six; the 1 GB ceiling about thirty, not seventy. The proposal's own
   "What would change" bullet 3 ("a z15 cell well over 30 MB ... then cap the DEM at z14 and drop the daily default to
   50 MB") fires on this arithmetic before anything is built. Resolves: compute each figure with
   `AreaManifest.tileItems` on the JVM (a test or a devtool command: a cell at Rainier's latitude at z12, z13, z15, direct
   and server), re-derive the defaults from those numbers, state whether already-cached tiles count against the day, and
   apply bullet 3 if it still fires.

4. **Serious. In direct mode (the default without a server) the detail rule buys nothing for terrain past z12 and the
   daily limit is spent on copies.** Evidence: `DirectSources` renders DEM to `maxZoom` 12 by default and synthesises
   deeper tiles from the z12 ancestor, which the fetcher caches (`DirectSources.kt` 165 to 167, 319, 332, 337, 473 to
   481, 904). The proposal's bullet 4 misreads this as a heap-guard timing problem. Resolves: in direct mode cap the
   dwell's DEM at `direct.maxZoom` through `demMaxZoom` (reverse "no `demMaxZoom`"; ADR 0047 exists for this), vector to
   the zoom viewed (at most 14); or measure a synthesised cell and accept the cost in writing. Record the same fact for
   T130's corridors as a finding.

5. **Serious. "Two optional fields older builds ignore" and "no data migrates" are false; the downgrade is a silent
   misread (ADR 0033 §3).** Evidence: `Record.read` and `recordJson` use fixed field sets (`Areas.kt` 99 to 110, 152 to
   165); only an opaque record is written back raw (97, 113). A v0.1.11 to v0.1.18 build parses a recent-place polygon
   record as an ordinary pinned area, drops `origin` and `viewedAt` at its next save (which `adopt` triggers with no user
   action, 217 to 229), and from then on it is a nameless pinned area in the budget with no expiry, indistinguishable
   after the upgrade. Resolves: keep the automatic records in their own file (`files/areas/recent-places.json`, the T130
   precedent of `trip-corridors.json`), pinned by adding their covers to the `AreaPins` list, promoted to an ordinary
   record on Keep; or give the record a manifest version older builds keep opaque (then they are pinned by bounds at z8
   to z14 and say "needs a newer app" for a place the user never asked for: honest but ugly). Rewrite the Exit cost.

6. **Serious. The WorkManager chain, the notification and the resume defeat the trigger's exclusions, and the
   job model is unstated.** Evidence: every `start` hands over to the chain (`AreaDownload.kt` 357; ADR 0046 §3); the
   worker shows "Downloading <name>" on every run (`DownloadWorker.kt` 121, 304; ADR 0046 §4); `unfinished` resumes any
   `downloading` or `partial` tiles record (`ResumeCheck.kt` 283) in background slices for days, with no knowledge of
   the daily limit, battery saver or the foreground; one job at a time (`AreaDownload.kt` 324). Resolves: name the
   trigger; say a dwell record is never auto-resumed (dropped or left partial, as `cancelled` is treated); say it shows
   no notification (an in-process fetch with no hand-over, the `AheadPrefetch` shape); say a dwell while a job runs is
   skipped, not queued; put the chain and the notification in Moving parts if the record path is kept.

7. **Serious. The session limit does not apply to area downloads, so a dwell fetches past "Online data limit
   reached".** Evidence: `AreaDownload.run` wraps the walk in `direct.exempt` (363 to 366; ADR 0045 §5); the
   Constraints paragraph says the session limit "already appl[ies] to every fetch the phone makes itself". Resolves: the
   dwell fetch is not exempt, or it checks the session cap before starting and `GET /areas/dwell` reports that reason;
   correct the Constraints paragraph.

8. **Serious. The 90-day expiry defeats the idea's own use case and is redundant with the ceiling.** Evidence: the
   idea's case is "planning in the evening, walking there tomorrow with no signal" and, by the proposal's own trigger
   (a second close view on a later day), seasonal returns; a place read in October and walked in February is 120 days
   unviewed and gone at the trailhead, offline, with no message. The ceiling already releases the oldest when space is
   needed, so below the ceiling the expiry frees nothing anyone asked for. Resolves: drop the expiry, or make it the
   survey's keep-time setting with a long default (365 days) and a reason under ADR 0044.

9. **Serious. The ceiling's release is a refusal with no message, under a sheet that promises the opposite.**
   Evidence: `AREAS_INTRO` "Pinned areas stay on this phone until you unpin or remove them" (`AreasScreen.kt` 308); the
   proposal's indicator covers downloads and the daily limit but says nothing when a place goes. Resolves: the section's
   own intro sentence ("Places you looked at closely are kept here until the space is needed for newer ones; Keep makes
   one permanent"), a "released N places since <date>" line under the ceiling, and the release reported in `GET
   /areas/dwell`.

10. **Minor. `dwellKeepMbPerDay` "synced like `tripCorridorM`" is the wrong side of ADR 0044's per-device rule.**
    Evidence: `DirectPrefs` is unsynced by design (`DirectPrefs.kt` 7); `pinnedCacheMb` is not in `SettingsSync.kt`;
    only `tripCorridorM` is (69, 143). A daily data cap is a plan's property, not a person's. Resolves: per device, like
    `directSessionMb`; say so for `recentPlacesMb` too (Storage budgets are per device today).

11. **Minor. The battery-saver pause is a new moving part with its lift outside the app.** Evidence: no code reads
    `PowerManager.isPowerSaveMode` (only `tracking/BatteryExemption.kt` touches battery, for the optimisation exemption);
    Moving parts for B and C do not list it. The idea's text sanctions a low-battery guard, so the gate itself is fine
    under ADR 0023. Resolves: list it; the message says the lift ("paused while battery saver is on; turn it off in the
    phone's settings"); `GET /areas/dwell` reports the state.

12. **Minor. Factual slips to correct.** `SettingsGroup.DATA`'s summary is "Sources (server, mobile data), offline
    areas, storage" (`SettingsLayout.kt` 12); T85's 20 to 35 MB is Rainier (Mont Blanc 38.8 to 46.4 MB; T88 later 17.5 MB);
    "a pinned area already covers the box (`Areas.coverage`)" names the wrong primitive (`coverage` reports what of a
    record is on the device; `Areas.pins().contains(z, x, y)` answers the question); the overlay is a column of short
    lines under one chip, 260 dp wide, with the notice line only in direct mode (`OnlineOverlay.kt` 60 to 61, 140 to
    160), so a two-sentence daily-limit line is the longest text it has carried; "What would change" bullet 1 is a
    condition on a survey already run and already answered the other way, so it cannot fire.

13. **Minor. The sheet and the release scale with cells, and the proposal costs them at zero.** Evidence:
    `Areas.coverage` per record on the sheet's poll (`Areas.kt` 476; ADR 0045 Consequences); every outline drawn while
    the sheet is open (`AreasScreen.kt` 77 to 78); `Areas.remove` one index pass per record (376 to 386). Resolves: one
    section object with its own cheap summary (count, bytes, last viewed) and no per-cell coverage until opened; a batched
    release (one `clearWhere` for N cells); no per-cell outline until the section is opened.

14. **Minor. The section is a browsing history and should be named as one.** Evidence: cells named from the search
    index with last-viewed times, on the sheet and in `GET /areas`. The proposal has Remove all, which is the right
    control. Resolves: one sentence in the section and in the help text saying what it records; `GET /areas` behind the
    debug token as today.

## Recommendation and confidence

Revise toward B's accounting with C's policy: one pinned total (the number the cache computes), automatic records kept
in their own file and pinned through the same `AreaPins` list, released oldest-viewed first whenever a user's download
would not otherwise fit and whenever the automatic records pass a soft ceiling shown as a line under the pinned row, no
expiry, promotion to an ordinary record on Keep. Fetch in process with the prefetch's loop (skipped while an area
download runs; no chain, no notification, no resume), not exempt from the session limit, with the daily limit as the
proposal has it, per device. Make the cell a polygon built from the tile index, with the margin and the source minimum
zoom in the figures, and cap the DEM at `direct.maxZoom` in direct mode. Re-derive every default from
`AreaManifest.tileItems` on the JVM and apply the proposal's own bullet 3 where it fires. Keep the trigger, the
dedupe by cell, the second-day refresh, the indicator and the agent path as written; they are good. Confidence: high
that objections 1, 2, 5 and 7 are facts of the code (each is a line cited above); high that 3 and 4 are, give or take the
exact tile counts, which a ten-line JVM test settles; medium on the recommended shape, because what the sheet shows and
whether a browsing cache deserves records at all is the owner's call under ADR 0031, and the proposal rightly stops
before it. Residual risks whichever shape wins: the survey's rows are unverified here; a real phone's dwell behaviour
(how often 20 s of rest at z12 happens while idly panning) is unmeasured, so the trigger's constants may fire too often
or never, which the debug override is for; and the privacy of a kept list of looked-at places on a shared phone is a
wording and a Remove all, not a mechanism.
