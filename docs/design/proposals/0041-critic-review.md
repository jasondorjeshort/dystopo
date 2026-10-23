# Critic review of proposal 0041: polygon areas in the manifest

Date: 2026-10-23
Status: review of the proposal as of commit a346f0c9 on `task/T133`
Reviewer: critic (Fable 5.1), read-only on the code; one temporary JVM test run in the worktree
(`AreaManifest.tileItems` over one-point corridors and boxes near Rainier, deleted after the run,
numbers below); no device. Prior-art rows re-read where the pages answered.

## Part 1. Claims checked

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| 1 | `AreaOfInterest.parse` throws on an unknown kind | Confirmed | `AreaOfInterest.kt` line 151: "kind must be box or corridor (got '$kind')". |
| 2 | `AreaManifest.parse` refuses `version` above 1 with the quoted sentence | Confirmed, but second | `AreaManifest.kt` lines 189 to 191: the `area` object is parsed **before** the version check. Run: a `{"version": 2, "area": {"kind": "polygon"}}` manifest on today's reader says "kind must be box or corridor (got 'polygon')", not the version sentence; a version-2 manifest with a box says the version sentence. See objection 1. |
| 3 | `Areas.load` drops an unparseable record; the next save rewrites the file without it | Confirmed | `Areas.kt` line 182 ("unreadable area dropped", a log line only); `changed(save = true)` line 289 writes `records.values`. `load` itself saves nothing (line 186), so the loss lands at the first mutation, which `adopt` (a source's version change, line 144) can trigger with no user action. The file-level `"version": 1` written at line 289 is never read. Every record carries `bounds` and `zooms` at the record level (lines 71 to 72), so the opaque rule's conservative box pin is possible. |
| 4 | A one-point corridor is a disc; `CorridorOutline` for one point unverified | Confirmed; the outline is verified already | `TileCover.corridor` line 122 (`listOf(points[0] to points[0])`; a zero-length segment makes `rectSegmentDistance` a point distance). `CorridorOutline.polygons` line 66 handles one point and `CorridorOutlineTest.oneOrNoPoint` asserts a disc within 3% of pi r²; `AreaOutlines.show` line 269 doubles the point for the line. The "What would change" bullet on this is moot. The one-point id differs from a two-point line at the same place (run). |
| 5 | `AreaDownload.plan` requires a corridor | Confirmed | `AreaDownload.kt` line 133. A box goes straight to `region.start` (line 151) with no size shown. |
| 6 | `serverManifest` falls back on a 400 from an older server | **Contradicted** | Lines 359 to 363: only 404 and 405 fall back; a 400 (an older server on a polygon) or 413 throws "server refused the area (HTTP 400): kind must be box or corridor" and the plan fails. The proposal's `GET /area` probe covers it only if the phone reads `manifestVersion` (an older server has no `shapes` field). |
| 7 | Server cap 200,000, HTTP 413; the phone's `localManifest` has none | Confirmed | `BuildRoutes.kt` lines 117, 750 to 751 (the sentence names corridor remedies and the env var); `localManifest` lines 379 to 393 builds the full `TileSet`s with no count check. `MAX_AREA_BODY` is 2 MiB (line 873): 2,000 vertices is about 50 kB. |
| 8 | Size figures (5 km about 300 tiles and 55 MB; 10 km 900 and 150 MB; 20 km 2,800 and 450 MB; a 1,900 km² park 4,300 and 700 MB at z8 to z15, 260 and 50 MB at z8 to z13) | Bytes within about 25%; tile counts low by 1.5 to 3.5x | Run at 46.853 N, DEM z6 to z15 with one tile of margin, vector z8 to z14 (what `localManifest` and the server do): 5 km disc 554 tiles, 68 MB (383 DEM, 171 vector); 10 km 1,333 tiles, 166 MB; 20 km 3,988 tiles, 505 MB; the park box 5,610 tiles, 716 MB at z8 to z15 and 903 tiles, 89 MB at z8 to z13; a 2 km disc 256 tiles, 33 MB. The missing tiles are the vector ones and the DEM z6 and z7 rows. The less-detail ratio holds (716 to 89 MB, eight to one). |
| 9 | A 2,000-vertex ring at z15 over a 100 km box may take over two seconds to fill on a phone | Cannot fail | Run on the JVM: a 100 km box at z8 to z15 (26,213 tiles, 3.4 GB) fills in 137 ms; a 2,000-vertex closed ring 100 km across as a 10 m corridor band at z8 to z15 in 21 ms. A phone ten times slower is still under 2 s. |
| 10 | The fetcher keeps bytes and times for a session rate | Partly | `OnlineFetcher.fetchedBytes` (line 207, an `AtomicLong`) with no time base; every `Areas.Record` has `bytes` and `downloadMs`, and `AreaDownload.Job` has `bytes` and `elapsedMs`, so a rate from the last completed download exists without a new counter. |
| 11 | `park` is unnamed merged polygons; `boundary` is admin 2 and 4 lines | Confirmed | `tilebuild/PROFILE.md` lines 97 to 116. |
| 12 | `pinnedCacheMb` defaults to 2,048 | Confirmed | `AppSettings.kt` line 221; range up to the cache budget's maximum (line 176). |
| 13 | Even-odd centres plus the edge band equal the exact cover for buffer at or above 0 | Argued sound; no new dependency | A tile farther than the buffer from every edge is wholly inside or wholly outside, so its centre decides; nearer tiles are the band (`TileCover.corridor` on the closed ring, distance 0 when an edge crosses the square). `UnitRegions.inside` (private, 12 lines) is the ray cast; a per-row crossing list is the scanline. Pure commonMain. |
| 14 | Older peers list areas without parsing `area`; where a refused manifest shows is unverified | Confirmed; the refusal is a toast on the Share screen | `PeerSources.kt` line 395 keeps the list as maps; `PeerSources.area` line 476 parses and throws; `ShareScreen.kt` line 314 runs it in `run { }`, so the user reads the kind sentence of claim 1, not "update this phone". |
| 15 | No proposed user-visible string names another product | Confirmed | The strings in positions 1 to 5 and the size line name none. |
| 16 | Manifest version 2 is the next free number | Confirmed | `AreaManifest.VERSION = 1`; SERVER.md documents version 1 only. |
| 17 | Prior art rows | Locus, Apple, OsmAnd confirmed; four official pages answer 403 to a fetch; the "not established" row can be half filled | Locus (docs page): 25 x 25 km default crop, drag points to reshape, by track with a buffer slider or metres, by points with a radius (Premium), a zoom slider or a per-zoom table with tile count and size, 10,000 tiles a day for custom online maps, no time. Apple (105084): size shown before the download; the page says only "adjust the area", not anchors or pinching. OsmAnd: named regions and a tap on the world map (the size column is not on that page). AllTrails, Gaia, Komoot and onX help pages return 403 to automated fetches (bot-blocked, not down); Komoot's official page on offline routes and regions exists (support.komoot.com, article 10356476920986: routes or regional maps, Wi-Fi recommended; the 3 km strip is DC Rainmaker's). From public pages this pass: Organic Maps downloads named regions (a country split into regions) with sizes in the list; Guru Maps downloads vector maps by country or by browsing the map, with downloaded areas highlighted; AlpineQuest's store-area tool takes an area and chosen zoom levels and warns about server limits; Backcountry Navigator XE picks blocks on a grid; Avenza sells whole maps with no area selection. A grid of blocks is a third model the table lacks; it does not change the conclusion. |

## Part 2. Objections

1. **Serious. Option B's reader-side benefit is misstated for every build that exists.** B says
   older builds refuse a peer's polygon manifest with "manifest version 2 is newer than this app
   reads (1)". They do not: `AreaManifest.parse` parses `area` first, so v0.1.10 and earlier say
   "kind must be box or corridor (got 'polygon')" (claim 2, run). The version-by-content rule
   therefore buys nothing on the peer path until a build that checks `version` before `area` has
   shipped, and the server's 400 is the same sentence either way. The recommendation still holds
   (B is A plus two cheap rules for the future), but the text argues from a mechanism that is not
   there. *Resolve:* correct B in place; make the build swap the order in `AreaManifest.parse` and
   in `Areas.Record.parse` (version, then kind, then fields) with a test that a version-3 manifest
   gets the version sentence; map both refusals to ADR 0033's wording ("this phone runs an older
   Dystopo; update it") where the user sees them (`ShareScreen`, the card).

2. **Serious. The one-time downgrade loss is accepted when a cheap, honest alternative avoids it.**
   The proposal records "a phone downgraded to v0.1.10 drops the record at load and loses it at the
   next save" as an alpha limit, and `adopt` can trigger that save with no user action (claim 3).
   Option C was rejected for lying about the kind; not considered: the new build writes records
   that version-1 readers cannot parse to a sibling file (`files/areas/areas2.json`, say) and keeps
   `areas.json` as the set a v0.1.10 reader reads whole. The old build never sees a polygon record,
   so its save loses nothing; the upgrade finds both files. It is ten lines in `Areas.load` and
   `changed`, and can be retired once every build in the field has the opaque-record rule. *Resolve:*
   adopt the sibling file for the first polygon build, or say in the proposal why one lost
   polygon area on a downgrade is acceptable (the alpha testers are few and the area is
   regenerable) and keep the limit explicit in the PR; either is fine, but the choice should be made
   knowing the alternative exists.

3. **Serious. The phone-side tile cap has no lift and the wrong reason.** E's moving parts add "a
   tile cap on the phone's own manifest (the server's 200,000 as the default, with the same
   sentence)". The server's cap is lifted by its operator through `DYSTOPO_AREA_MAX_TILES`, named in
   the message; the phone's would be a constant nobody can change from the phone, and its sentence
   ("lower maxZoom, narrow the buffer or split the route") names corridor remedies. At 160 kB a DEM
   tile the cap is about 25 GB, so with the default 2 GB budget it never fires before the budget
   does; it matters only for a user who raised the budget toward 1 TB, and then it refuses what they
   asked for. The real reason for a phone cap is memory and time: `localManifest` builds every
   `TileSet` in full before counting (claim 7), so a state-sized ring at z15 allocates millions of
   `Long` entries during the live size line, the territory of the direct-view out-of-memory finding
   (T127). *Resolve:* refuse from the bounding box's tile count (O(1)) before any fill, with a
   sentence for polygons ("shrink the area or choose less detail"); either derive the cap from the
   pinned budget (bytes, which the user can raise and the message already names) or make it a
   setting beside `pinnedCacheMb`; the server's sentence gains the polygon remedy.

4. **Serious. A reader-enforced vertex cap repeats the `MAX_BUFFER_M` trap and quietly bounds the
   boundary step.** "At most 2,000 vertices over all rings after `Polyline.simplify`" reads as a
   `require` in `AreaOfInterest.init`, like the buffer range. Then the first county with a coastline
   or a state outline that needs 3,000 vertices forces either a lossy simplification (a 0-buffer
   polygon simplified with a tolerance under a z15 tile cuts inside the true boundary, which the
   corridor code avoids by growing the buffer, `Polyline.simplify` KDoc) or a cap raise that every
   earlier reader drops as unreadable, the downgrade problem again, now on an upgrade of the data
   rather than of the app. *Resolve:* make the cap the writer's (the drawing flow and the boundary
   step simplify to fit; the server's 2 MiB body is the only hard limit) and the reader lenient; add
   the simplification tolerance to `bufferM` for a polygon as corridors do; state the number as a
   budget, not a format rule.

5. **Serious. The 20 km radius refusal names no way out.** Position 2 allows a typed radius
   "within the 20 km cap". The refusal today is "bufferM must be 10.0 to 20000.0 metres (got
   25000.0)", lifted only by a release (and a raise moves T130's corridor cap with it, the same
   constant). The user does have a way out the proposal itself provides: draw an area, which has no
   radius cap. *Resolve:* the chooser's sentence says so ("Up to 20 km around a point; draw an area
   for more"), and the proposal says which one lifts the cap when the owner asks for 50 km (a
   version step with the opaque rule in place, or a polygon circle, H's first half, which it rejects
   for the first build only).

6. **Minor. The size figures and the "what would change" fill condition.** Tile counts are low by
   1.5 to 3.5x and the z8 to z13 figures by about two in bytes (claim 8); the numbers to put in
   the text are those in claim 8 (or the build's own unit test). The two-second fill condition
   cannot fail by two orders of magnitude (claim 9); replace it with something that can (the live
   size line within 300 ms on the emulator for a 2,000-vertex ring, say) or drop it.

7. **Minor. The live size line would call the server on every edit.** `AreaDownload.plan` posts to
   `<server>/area` (5 s connect, 30 s read) before falling back to the phone's lists. "The plan
   recomputed after a short pause" while the ring changes must use `localManifest` for the live
   number and ask the server once at Download (the two differ only in the DEM start zoom and the
   server's cap). Say so, or the drawing stalls on a slow link.

8. **Minor. An older server does not fall back; `pack=1` for a polygon is C on the server.** Claim
   6: a 400 fails the plan rather than making the lists on the phone; the `GET /area` probe works
   only if the phone treats a missing `shapes` field (every server today) as "box and corridor".
   And "`pack=1` builds the bounding box's pack as for a box" is the lie C was rejected for, moved
   to the server: the manifest's `area` says polygon and the pack holds the box. *Resolve:* a 400
   whose message starts "kind must be" falls back like a 404; `pack=1` on a polygon either refuses
   or answers with a note "the pack is the bounding box" and the box as the manifest's `area`.

9. **Minor. Even-odd is right for holes and islands, wrong for overlapping parts.** Two rings that
   overlap (a protected area made of overlapping relations, or a user who draws a second loop across
   the first) cancel where they overlap. The drawing flow never makes two rings, and the boundary
   step can union before writing; say that the writer owns the no-overlap rule, since the reader
   cannot tell a hole from an overlap.

10. **Minor. The time line's source.** The proposal says the fetcher's rate is unknown; claim 10:
    `fetchedBytes` exists with no clock, and every record has `bytes` and `downloadMs`. The last
    completed download on this phone is the honest rate ("about 3 min at the last download's
    speed"); a session-wide counter needs a start time and would mix map browsing with downloads.

11. **Minor. Prior art gaps that one more pass fills.** Claim 17: Organic Maps, Guru Maps,
    AlpineQuest, Backcountry Navigator and Avenza have public pages; Apple's "anchors and pinching"
    is not on the cited page; Komoot's official offline page exists; four pages are bot-blocked
    rather than unreachable. Fill the row, mark the grid-of-blocks model, and note the 403s as such.

Hidden coupling found and judged acceptable: a disc is a corridor, so its label, outline and the
server's default name are decided by "one point" in three places rather than by a kind, and its
radius shares the corridor's cap (objection 5); T130's 1 km corridors are untouched (same shape,
same `plan`, a `tripId` the disc lacks); areas are not synced today (`sync/**` never reads
`Areas`), so the polygon rides into any future sync as an opaque `area` object; adding a third
`AreaShape` breaks every exhaustive `when` at compile time, which is the right failure.

## Part 3. Pre-mortem

Ten years on, the regret is not the polygon but what the first build hard-coded around it: the
boundary step arrived in year two with counties of 5,000 vertices and parks made of overlapping
relations; the 2,000-vertex `require` and the even-odd rule made the server simplify and union
before writing, the simplified rings cut inside real boundaries by up to a z15 tile, and the raise
of the cap was a version step that every phone still on the first polygon build dropped at load,
because the opaque-record rule was written for "the next format change" and the next change was
this one. Meanwhile the disc stayed a one-point corridor, so a 50 km radius for a desert drive
could never ship without moving T130's corridor cap too, and three places kept a "one point means
disc" branch. The lines that should have caught it: "at most 2,000 vertices over all rings" and
"the radius is bounded by `MAX_BUFFER_M`" were read as facts about the data instead of as rules a
reader enforces against its own future.

## Recommendation

The recommendation is right: a `polygon` kind with even-odd rings and an optional buffer, downloaded
as pinned tiles with the size and the budget first, and the disc as the one-point corridor the
format already has, is the smallest change that carries any boundary the project will offer; D, F
and H are rightly rejected and C is rightly kept as the record. Promote it after five corrections in
place: state B's benefit honestly and swap the parse order (1); decide the downgrade question
knowing the sibling-file option (2); replace the phone's tile cap with a bounding-box pre-check and
a lift the user has (3); make the vertex cap the writer's, not the reader's (4); give the radius
refusal its way out (5); and take the measured sizes (6). None of these changes the format; they
change what the readers refuse and what the sentences say, which is where ADR 0033 lives.
Confidence: high on claims 1 to 16 (read in the code, three run on the JVM); moderate on the prior
art (official pages for four apps could not be fetched by a machine); moderate on objection 2's
weight, which depends on how many phones ever downgrade.

Objections: 0 blocking, 5 serious (1 to 5), 6 minor (6 to 11).
