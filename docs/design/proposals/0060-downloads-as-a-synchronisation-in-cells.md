# 0060. Downloads as a synchronisation in cells over one store

Date: 2026-11-09
Status: promoted to ADR 0060

Paths without a root are under `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/`; `shared/` paths
are under `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/`. Every `file:line` is read at the fork of
this task, commit e184b663. T249 (the index snapshot) and T254 (the ancestor fallback) change `online/` files in this
wave without changing the store's layout; the build tasks re-read every site after wave 31's merges. Revised on the
critic's review (`0060-critic-review.md`: 0 blocking, 9 serious, 11 minor); corrections are made in place and
numbered at the end.

## Question

The owner, 2026-11-07, during a large area download: "a synchronization rather than a monolithic pull ... we record
the areas that are downloaded and pull them cell by cell when they're not down or are sufficiently out of date", "per
some reasonable download unit which I called a cell", and "the ability to interrupt downloads, via a full phone reboot
after connection is interrupted ... is pretty essential". On the wider shape: one content-addressed store for the tile
cache, packs and the phone-to-phone transport ("Absolutely we should do this"), and an always-on helper keeping a
phone's wanted cells fresh ("an excellent plan"), written for any instance the user chose or a peer (ADR 0059). And on
2026-11-07 the owner's phone lost 62 GB of kept maps to Android's Clear cache, because pinned tiles live in
`cacheDir` (T242's note). What is the cell, what records say wanted, present and stale, where does the store live,
does a pack join the model, what is content-addressed and how do instances and peers announce it? In ten years a
phone should hold what its user asked for, catch up in small pieces from whichever source is reachable, survive any
interruption by recomputing what is missing, and never lose kept data to a system button.

## Constraints that matter here

- Platforms, licensing, offline first, agent-drivable, the design goals: as in the template. No dependency is proposed;
  `shared/packs/Sha256.kt` is already a pure SHA-256, WorkManager (Apache-2.0) is already in the app (ADR 0046).
- ADR 0023: nothing is limited to Wi-Fi; data use is steered by visible limits. Any automatic refresh needs one.
- ADR 0059: no instance's address is built in; the helper serves "any instance the user chose or a peer" and is tested
  against the sweep's own server.
- ADR 0051: the phone-to-phone link is sealed under the share code; a new peer route rides inside it.
- ADR 0030: a pinned area is served at the versions it was fetched at, and **refresh is explicit or on the route**; it
  rejected background refetch ("data the user did not ask for, and an area half old and half new"). An automatic
  refresh supersedes that rule and must state its consistency rule (objection 3).
- ADR 0033: alpha may change formats; user data (the area records) migrates; derived data (tiles, indexes) may be
  rebuilt by a version in its key, never misread. ADR 0031/0033 §4: a new data flow to a server is flagged to the owner.
- ADR 0034: one cache budget over the phone's caches; pinned tiles have their own budget (`pinnedCacheMb`) outside it.
- ADR 0049's pattern: something new beside a pack is a `derived` entry old phones ignore, never a pack-format change.
- Direct mode (the phone reads OpenFreeMap and the elevation services itself) has no instance to ask for hashes, and
  its cache keys are unversioned (`DirectSources.kt` sends no `X-Dystopo-Version`; `PeerServe` answers `/version` 404),
  so every rule here must work with the public sources alone. OpenFreeMap's public instance asks apps to keep to
  "areas of up to 3,000 tiles or 50 MB, with at most 4 parallel requests" (`openfreemap.org`).
- T229's rule: no disk or binder work on the main thread before its gate; the store's move runs off it.
- Already ruled out: a per-tile download unit (the owner: "tiles are tiny"); map apps as the benchmark (the owner).

## Prior art

One technical survey of 2026-11-09 (ADR 0044; `out/wave31-research/downloaders.md` [no-check: gitignored], Sonnet,
web), with the critic's checks against primary sources. Pages: SteamPipe's upload documentation
(`partner.steamgames.com/doc/sdk/uploading`), SteamKit's `DepotManifest.cs` and DepotDownloader's
`ContentDownloader.cs` and README (`github.com/SteamRE`), desync's README (`github.com/folbricht/desync`), casync's
`src/cachunker.h` (`github.com/systemd/casync`), OSTree's formats page (`ostreedev.github.io/ostree/formats/`),
WorkManager's guide, RFC 9110 §13.1.5. **The Steam client's own resume and verify are undocumented; the rows below
are DepotDownloader's, the open client of the same depot format.**

| System | Wanted | Present | Stale | Unit | Address | Resume | Verify | Update |
|---|---|---|---|---|---|---|---|---|
| Steam depots, via DepotDownloader | a target manifest id per depot | the installed manifest, files on disk | installed id differs from the branch's | ~1 MB chunks, compressed, encrypted | chunk id = SHA-1 of the chunk (`ChunkData.ChunkID`), Adler32 checksum | matches chunks across manifests, old file to a staging folder, fetches `neededChunks` | Adler32 per existing chunk, whole-file hash as a shortcut; `-validate` | unchanged chunks reused, only new chunks fetched |
| desync / casync | an index of chunk hashes | any store or seed holding the hash | a new index | content-defined chunks, 64 KiB average (16 to 256 KiB) | chunk file named by its hash, any static host | completed chunks not fetched again | per-chunk hash | seeds: chunks found in the old file reused |
| OSTree | a ref to a commit checksum | the installed commit | ref commit differs | one object per file, or a static delta | object checksum | objects present are skipped | checksums | precomputed `from-to` deltas, per-object fallback |
| BitTorrent v2, zsync (one line each, from memory) | a merkle root / a control file | pieces held | root differs | 16 KiB leaves / blocks | piece hash | piece bitmap | piece hash | blocks fetched by Range |

Common practice: **wanted is a manifest of units with hashes per version; present is an installed-version id plus
what is on disk; stale is a version or hash mismatch resolved to a unit diff; resume is idempotence (recompute
wanted minus present at every start, no byte journal); verify is re-hashing; an update fetches only the units whose
ids the client lacks.** Units are 64 KiB to about 1 MB, never one per small item. Outliers: OSTree pays the delta on
the publisher; Steam's manifest id is an opaque version, not a hash; zsync diffs on the client with Range reads.

Licences: DepotDownloader GPL-2.0, SteamKit2 LGPL-2.1, casync LGPL-2.1, OSTree LGPL-2.0-or-later: reading material
only. desync BSD-3: a reference, no code copied. Nothing here needs a library. OpenFreeMap: MIT, a full planet built
weekly on Wednesdays, the last four runs kept; the share of tiles that change between runs is not published.

Settings (the survey): practice splits on whether updates run by themselves, so one setting for that with a visible
data limit; hash checks, atomic commits and resume are not settings. The survey's default was off; the owner's words
point the other way, and the default is the owner's (Settings, below).

## What the code does today, in that vocabulary

| | Today | Where |
|---|---|---|
| Wanted | the area record (`Areas.Record`, geometry, zooms, versions) and its manifest, in `filesDir/areas` | `areas/Areas.kt:55` |
| Present | the tile cache's in-memory index, rebuilt by walking the directory at every cold launch (T242; T249 adds a snapshot) | `online/TileCache.kt`, `online/TileIndex.kt` |
| Stale | per source version (`GET /version`'s `dem`, `osm`); a pinned area keeps its versions (ADR 0030); nothing refreshes without Update; direct mode has no versions | `online/OnlineFetcher.kt:89-106` |
| Unit | one tile per request (six at a time, `POOL`, `areas/AreaDownload.kt:797`); progress by whole **row** at one zoom | `shared/areas/AreaManifest.kt:217`, `TileRow` |
| Resume | a cursor (last whole row, failed rows) saved every 2 s (`CURSOR_SAVE_MS`, `:805`); WorkManager chain with `CONNECTED`, persisted over reboots | `areas/ResumeCheck.kt`, `areas/DownloadWorker.kt` |
| Give up | a run with no whole row is a failure; five stop with Retry (`MAX_FAILURES`, `areas/ResumeCheck.kt:179`), so a country at z14, whose rows are thousands of tiles, can stop with most tiles cached | `:173-179` |
| Verify | a DEM tile must be a PNG, a vector tile must unzip; no hash | `areas/AreaDownload.kt` (class comment) |
| Store | `cacheDir/online/tiles`, one file per tile, pinned and unpinned together; the versions file in `cacheDir` too | `online/OnlineService.kt:45`, `:156`; `storage/Storage.kt:78` |
| Packs | a whole-file byte resume (`.part`, `Range` with `If-Range`), SHA-256 on finish for catalog packs, resumed at launch; no background worker; the `ETag` sent verbatim, weak or not | `packs/PackDownloader.kt:31-60`, `:537` |

So an area download is already a synchronisation; it lacks a small unit with a time and an identity, a record of
cell facts beside the cache's index, a stale test finer than the source version, and a safe home. A **box** area is
a region pack (ADR 0027, as built), not tiles: the cell model covers corridors, polygons, discs and country tile
areas; boxes and catalog packs are P1 jobs. "One store" is honoured in the protocol (cells, listings, hashes), not
on disk: archives and loose tiles stay two stores until P3's trigger (below).

## Options

Six questions, each with options at equal depth. The settings each would add are collected after them.

### 1. Where the store lives

**W1. Stay in `cacheDir`, say so.** T249 adds the sentence "Android's Clear cache removes kept maps too". The system
also deletes `cacheDir` files on its own when storage runs low, oldest `lastModified` first, and `TileCache` touches a
file's mtime only on a hit, so a kept tile not viewed for months is the first to go (`Context#getCacheDir`). Cost:
none. Risk: the 62 GB loss repeats, and silently. Exit cost: none. Moving parts: none.

**W2. Move the whole tile tree to `noBackupFilesDir/tiles`, as a procedure.** (Corrected, objections 1, 2, 12.)
`cacheDir` and `noBackupFilesDir` are both under the app's private data directory and move together to an adopted
device, so a rename is normally one atomic `rename(2)`. Two facts make it a procedure, not a call: `installd` creates
`cache` with mode `02771` and the app's **cache GID**, and counts cache space by that GID, so a renamed tree keeps
showing as Cache in the system's statistics and new tiles inherit the GID; and ext4 and f2fs return `EXDEV` for a
rename into a directory with another inherited project ID, which a vendor build that enables project IDs for app data
would give (AOSP main does not today). The procedure, on the index thread before the walk:

1. Rename `cacheDir/online/tiles` (with the versions file and T249's snapshot) to `noBackupFilesDir/tiles`.
2. On failure, copy and swap in the background: the old tree stays the served store, files are copied in key order
   with a resume marker, each copied file's entry switches roots, the old tree is deleted at the end.
3. An ownership pass: each directory's group set to the app's own GID and its setgid bit cleared (an owner may change
   a file's group to one it belongs to); files fixed lazily by the index thread, which already stats each one.
4. `GET /debug/cache` reports `location`, `moveState` (`done`, `copying`, `failed`) and `groupFixed`; until the move
   is done the Storage screen says "Kept maps are in the cache on this phone; Android's Clear cache would remove
   them" (T249's sentence becomes this state's sentence). An upgrade test covers the `EXDEV` branch with a fake.

Unpinned tiles move too: they are bounded by the app's own budgets (ADR 0034, which already bound them), and the
Storage screen's clear action is the user's way to drop them. `noBackupFilesDir`, not `filesDir`, because
`allowBackup="false"` (`AndroidManifest.xml:37`) does not stop a device-to-device transfer on some manufacturers'
Android 12+ phones, and `no-backup` and `cache` are always excluded (Android 12 backup-restore page). The position for
packs (`filesDir/packs`, transferred today on those phones): a `dataExtractionRules` file excludes `packs/` from
`<device-transfer>` in the same task, so kept maps of both kinds stay off a migration (a transfer of tens of GB the
user did not choose; they download again). Contour and shade caches stay in `cacheDir` (derived, rebuilt offline).
Cost: the mover and its resume, the ownership pass, one Storage state, the rules file, an upgrade test. Exit cost: the
same procedure back. Moving parts: the mover (once per install), the rules file.

**W3. Two roots: kept tiles in `noBackupFilesDir`, the rest in `cacheDir`.** A pin moves each of its tiles across
(a rename each, the same `EXDEV` and GID caveats per file), an unpin moves them back, a lookup stats both roots. It
matches Android's semantics: browsing tiles stay cache, so the system's quota and low-storage deletion keep working for
them. Cost: the move per tile on pin and unpin (tens of thousands for a large area, interleaved with overlapping
pins), two stats per miss, and the upgrade moves only kept tiles one by one (the 62 GB case: hundreds of thousands of
renames, minutes at T242's walk rate). Exit cost: merge the roots. Moving parts: a mover with its own resume, run at
every pin and unpin.

### 2. The cell

Not an option: a tile as the unit (the owner). The cell is the unit of progress, of the fetched-at time and of the
failure count in every option; option 4 says whether it is also the unit of transfer.

**C1. A bounded run of a row.** Today's `TileRow` cut into runs of at most 32 tiles; the cursor moves by run; each run
gets a fetched-at time in the record. Meets the "gives up with most cached" defect (progress every 32 tiles). The run
depends on the area's shape, so two overlapping areas or a peer cannot name the same unit: no shared identity, no
content address. Cost: small (the cursor's grain). Exit cost: none. Moving parts: none. Who else: nobody in the survey;
it is a byte journal by another name, which the survey's practice avoids.

**C2. An aligned block per route and zoom, sized per route.** (Corrected, objections 14, 18 and the critic's note on
keys.) Cell `(source, route, z, x >> k, y >> k)`, fixed by the tile grid, so every area, phone and instance names the
same cell; the content version is a field of the cell, never part of its key. Sizes from the start: **4 by 4 for DEM**
(a 16-bit mask; up to 16 times 160 kB, 2.5 MB in mountains, `shared/areas/AreaManifest.kt:317`), **8 by 8 for vector**
(a 64-bit mask; 640 kB at z13-14 at the 10 kB estimate), and **no cell below z10**: a tile there is its own cell (a
handful per area, fetched whole). An area wants the mask of each cell its cover touches; several areas OR their masks.
Counts: a million tiles are 30,000 to 60,000 cells in the interior, two to three times that for a corridor, which is
nearly all edge cells; the ledger is read as a stream and never loaded whole into the 576 MB heap. Meets resume by
recomputation (the survey's shape), the identity the content address needs, and a corridor's edge (the mask, not the
block, is fetched). Cost: the walk by cell (`AreaManifest.walk` gains a cell order), the ledger (option 3). Risk: the
sizes rest on estimates; the first task measures cell bytes over the Rainier and Mont Blanc areas, and a size change
before any instance serves listings costs a ledger rebuild only. Exit cost: the ledger is derived; dropping it returns
to the row walk. Moving parts: the ledger file.

**C3. An aligned block stored as one file (a bundle).** As C2, but the cell's tiles are one file on disk (a small
PMTiles or a framed concatenation). Cuts the file count sixteen- to sixtyfourfold, so T242's walk and the inode load
shrink, and a cell is atomic on disk by one rename. But browsing writes one tile at a time into a cell, which then
rewrites a 2.5 MB file per tile, or keeps loose tiles beside bundles (two layouts). Cost: a new reader in the map's tile
path, a compaction step. Exit cost: unpack every bundle (tens of GB rewritten). Moving parts: the bundle format and its
compactor. Who else: Steam and OSTree store units as files; map stores keep tiles loose or in one archive.

### 3. The ledger and the stale test

All three keep **one catalogue** (corrected, objection 8): the ledger holds only what the index cannot (per cell: the
version fetched at, fetched-at, failures, the listing id and the tile hashes); the present mask is computed from
`TileIndex` (`TileCache.onIndex` reports every add and remove), or cached and validated against it as T249 validates
its snapshot. One writer, on the index thread; a binary file beside T249's snapshot (`tiles/cells.bin` [no-check:
proposed name]), versioned, discarded and rebuilt on mismatch (derived data, ADR 0033 §2; no SQLDelight migration).

**S1. Source versions plus a fetched-at per cell.** Stale: the source's version for that cell moved, or, for a source
with no version (direct mode every day, not a corner), fetched-at older than a per-source age (vector 30 days, DEM 365
days; constants). Moving parts (corrected, objection 4): **the per-cell version replaces the area's single version as
the truth** for `AreaPins.holds`, `Areas.older`, the card's "Older than the source", the Storage screen's pinned row and
`OnlineFetcher.dropUnheld`. A lookup tries the versions the ledger names for that cell; the record keeps a summary per
source (the oldest and newest versions it holds); `dropUnheld` runs per cell when its mask is whole at the new version.
Consistency rule (replacing ADR 0030's "one version throughout"): **a tile is served at the newest version present for
its cell; an area may hold two versions while it refreshes, and its card says "N of M places current".** Cost: the
ledger and the pin rewiring. Risk: a new upstream build marks every vector cell stale though most did not change, so
under S1 a refresh is a whole-area refetch. Exit cost: the ledger is derived (rebuilt from the files, fetched-at from
mtimes); the pins return to the record's version. Moving parts: the ledger's writer, the per-cell pins.

**S2. S1 plus per-tile hashes from a source that has them.** An instance answers a cell listing: the cell's tiles'
hashes, SHA-256 cut to 16 bytes, a zero entry for a missing tile; the listing's own hash is the cell's id, the
manifest id for one cell. The ledger keeps the hashes per cell (256 bytes a DEM cell, 1 kB a vector cell). Stale: the
listing's id differs from the row's; the diff of the two listings is the tiles to fetch. A source without listings
(direct mode, an older server) falls back to S1. The phone **also hashes each tile on `put`** in the background
(corrected, objection 15), so it can answer listings to a peer for tiles fetched in direct mode or from an older
server. Where the instance gets tiles (corrected, objection 5): a listing of a cell it never served means fetching or
rendering it, so **vector listings require an instance with its own planet** (OpenFreeMap publishes the weekly full
planet; the site install's option) **or a server-side limit per asked area**, announced in `/live/info`; never a
mirror of OpenFreeMap's public instance past its stated limits. What it buys is unmeasured (corrected, objection 6):
the share of vector tiles that change between two weekly builds is unpublished, and DEM tiles are rendered
deterministically from a source version, so per-tile DEM hashes pay only when a regional update changes part of a
cover. So: measure the changed-tile share over the Rainier and Mont Blanc areas between two consecutive planet runs
first; listings for `osm` first; DEM listings only on that evidence, else S1's DEM version and age stand. Exit cost:
listings are derived on both sides; dropping them returns to S1. Moving parts: a server route, a server-side listing
store per version, the phone's hasher and the ledger's hash column.

**S3. Fetched-at only (a time to live).** Every cell older than N days is refetched. Meets direct mode trivially.
Fetches unchanged data on a clock: the survey's practice avoids it, and it steers data use by time, not change.
Exit cost: none. Moving parts: none beyond S1's ledger.

In all three the failure count moves to the cell (corrected, objection 16): a cell that fails in three runs is held
back, the area ends `partial` with "N places could not be fetched" and Retry, and the held-back cells are drawn on the
area's outline; the next automatic refresh tries them again once, and Retry clears their counts. `MAX_FAILURES` counts
runs that completed no cell. During the wave that keeps both (corrected, objection 20), the ledger wins over the cursor
(it is derived from the store) and `GET /areas/resume` reports the rows where they disagree.

### 4. The transfer and the content address

**T1. Per-tile requests as today, the cell for bookkeeping.** Each wanted tile of a cell is a GET through
`OnlineFetcher` (six in flight; four towards OpenFreeMap's public instance, its stated limit); the cell is committed
in the ledger when its mask is whole. Works with every source, public ones included. Cost: none new. Exit cost: none.
Moving parts: none.

**T2. One request per cell for the tiles not held.** `GET /cells/<route>/<z>/<cx>/<cy>?have=<listing id>` answers the
listing and the bodies of the tiles whose hashes differ, framed (count, then index, length, bytes). One round trip per
cell instead of 16 or 64; the peer link carries the same route, with the frame spanning its sealed 4 MiB pieces (a
cell below z10 is one tile; a z10 vector cell can pass 4 MiB). Falls back to T1 for a source without it. Cost: a server
route and a frame reader in `shared` (pure Kotlin). Exit cost: the route stays or goes; phones fall back. Moving parts:
the route on the server and in `share/PeerServe.kt`.

**T3. Blobs by hash: `GET /blob/<hash>`.** Any holder serves any tile by its content hash; a listing names the
hashes. The most general (identical ocean tiles stored once, any peer serves any blob) and the survey's purest model.
Cost: the store keyed by hash beside the key by place (a second index, a reference count for eviction), every tile
read through an indirection. Exit cost: rewrite the store back to place keys. Moving parts: the hash index and its
garbage collection.

The content address in every option is per tile, with the cell's listing id as the unit's address: per tile because
an edge cell is wanted in part and one changed tile should cost one tile; per cell for the stale test and the
announcement. A cell-only hash would refetch a whole cell for one changed tile and could not match a partial cell.

### 5. Packs

**P1. A byte resume, plus the background worker.** The pack job stays as it is (`.part`, `Range`, `If-Range`, SHA-256
at the end) and joins ADR 0046's WorkManager chain, so a pack, like an area, resumes with the app closed and after a
reboot (today only at the next launch, `packs/PackDownloader.kt:31-36`). **First, the weak-tag fix** (corrected,
objection 9): RFC 9110 §13.1.5 forbids a weak entity tag in `If-Range`, and the downloader stores and sends `ETag`
verbatim, so a host that sends `W/` tags (nginx with gzip) restarts a multi-GB pack after every stop, which P1 makes
routine (a worker slice is about ten minutes, ADR 0046). The fix: ignore a tag starting `W/`, use `Last-Modified`
instead, else resume without `If-Range` only when the catalog's SHA-256 will catch a splice; a unit test with a weak
tag; P1's report counts restarts. For a pack, "Keep offline maps up to date" means a whole-pack Update (only the
archives whose hash changed, as today) until P2. Cost: the job's hand-over to the worker, the tag rule. Exit cost:
none. Moving parts: one more job kind in the chain.

**P2. P1 plus a block-hash list as derived data.** Beside the whole-file SHA-256 the catalog's `derived` list (ADR
0049's) names a list of SHA-256 per 4 MiB block. A failed verify refetches the bad blocks only, and a peer can serve
blocks. (Corrected, objection 19: the torn tail after power loss is not its case; on ext4 and f2fs a short tail is the
likely state, and the part file's length is already the resume point.) It does not give delta updates: `PmtilesWriter`
shifts every later offset when one tile changes (the 2026-11-02 triage), so fixed blocks do not line up across
versions. Trigger: a measured repair case (a verify failure on a real download) or peer serving of packs by block.
Cost: one derived file per archive on the server, a block check in the downloader. Exit cost: ignore the list. Moving
parts: the server's block hasher.

**P3. A pack as a set of cells in the one store.** A region pack becomes its cells; the archive file goes. Every pack
reader (the DEM compositor, the vector reader, the routing graph builder, search) then reads from the tile store.
This is the owner's single store taken literally, and deltas fall out of S2. Cost: rewriting every reader of
`PackSet`, imports by file, the server's pack build and the catalog; the routing graph and search index, built from
the vector archive, need a new input path. Exit cost: re-pack every phone's cells. Moving parts: most of the app's
data path. Trigger to revisit: a bug fixed twice, once per store, in two consecutive waves, or pack updates measured
as the larger share of phones' download traffic.

### 6. Announcement and the always-on helper

**H1. None: the phone polls.** The phone checks its source's `GET /version` (today) and, with S2, the listings of its
wanted cells, on the refresh setting's schedule, through a batch form of the listing route (one z8 block's listings
in one answer). Cost: the batch route. Keeps no state on any server and asks nothing of the user.

**H2. A stateless catch-up with a keep-warm list.** `POST /cells/changes` with the phone's wanted areas (the
geometries it already sends to `POST /area`) and, per route, the instance's change token it last caught up to; the
answer is the cells whose listing changed since, with sizes. The instance keeps a change log per version and, as the
"always-on" part, keeps the cells of areas it was asked about built and hashed for a time after each ask (30 days,
renewed by the next ask). No account, but a 30-day record of where an instance's users go, which an instance log can
pair with an address (corrected, objection 7): the instance announces its retention in `/live/info`
(`cellsKeepDays`); the first catch-up against an instance shows "This server keeps the shapes of your offline maps for
30 days to prepare updates" with Allow and Not on this server, remembered per server; until allowed the phone uses
H1. A consent, not a setting (ADR 0044: privacy rules). Keep-warm for vector needs the instance's own planet (S2's
rule). An instance and a peer announce the capability in `/live/info` and the peer catalog (`cells: 1`); a federated
instance announces the same. Cost: the route, a change log, a keep-warm list with expiry, the consent dialog. Exit
cost: the route goes; phones fall back to H1. Moving parts: the server's change log and keep-warm job, the per-server
consent.

**H3. A registered phone.** The phone registers its wanted areas under its ADR 0051 identity key; the instance
pushes or holds changes per phone. Most efficient for many small changes; an account in all but name, and a phone's
identity linked to its places on someone's server. Exit cost: drop the registration store. Moving parts: identity on
the server, a per-phone store.

## Settings (ADR 0044)

| Behaviour | Setting or one behaviour | Why |
|---|---|---|
| Kept maps refresh themselves when their source changed | **Setting** "Keep offline maps up to date": Automatically, Only when I tap Update. **The default is the owner's**: Automatically on the owner's "needs to just work in the background", or Only when I tap Update as ADR 0030 and the survey have it | Apps split (survey); the owner's words against ADR 0030's rule |
| How much an automatic refresh may use | **Setting** "Background updates up to" N MB a day (choices 50 MB, 100 MB, 500 MB, 2 GB, no limit; **the default is the owner's**, 100 MB proposed, the critic's confidence low past a corridor). Reaching it shows "Update waiting: daily limit reached (Settings › Storage)" with Update now. A tap on Update never counts against it | ADR 0023: a visible limit, never a network check |
| An automatic refresh larger than the limit | One behaviour: announced on the card with its size and the two lifts (raise the limit, Update now), not started | Objection 3: a country never catches up under a daily limit |
| Which areas refresh first | One behaviour: the area open or being navigated, then saved routes and trip corridors, then the rest oldest-first; by use (the 2026-11-04 idea) when its signal exists | The commute refreshes, the country waits |
| The keep-warm list on a server | A per-server consent (H2), not a setting | Privacy rule |
| The store's location, the cell sizes, the hashes, the atomic commit, resume, the failure rule, the age constants | One behaviour | Correctness and safety; one practice is clear |

The strings and the row's place are the owner's to adjust (ADR 0031). No product name in any of them.

## Recommendation

**W2 as a procedure, C2 sized per route, S2 over S1 (S1 first and always the fallback), T1 then T2, P1 (after the
weak-tag fix) then P2 on its trigger, H2 behind a per-server consent with H1 until then.** In terms of the
constraints: W2 ends the loss for kept maps of both kinds and, with the copy fallback, on every phone; C2 is the only
cell with an identity a phone, a peer and an instance share, at the survey's unit size per route; S2 makes "out of
date" mean "changed" where an instance can afford listings, and S1 keeps direct mode and older servers working; T1
first because it needs no server change; P1 closes the pack's background gap; H2 is the helper ADR 0059 allows, with
its retention disclosed and consented. Rejected for now, kept as the record: W1 (the loss repeats, silently), W3 (a
mover at every pin for what W2 does once), C1 (no identity), C3 (two layouts or a rewrite per browsed tile; revisit if
T249's numbers show the file count limits phones), S3 (data by the clock), T3 (a second index and garbage collection
before any dedupe is measured), P3 (every pack reader rewritten; its trigger above), H3 (an account).

Build order (wave 32; the ADR carries it):

1. **T249 merges** (this wave): the snapshot then moves with the tree.
2. **The store's move** (W2's procedure), alone: rename, copy fallback, ownership pass, the Storage state, the
   `dataExtractionRules` file, `GET /debug/cache`'s fields, the upgrade test with the `EXDEV` fake.
3. **Cells on the phone with S1 and the refresh setting together** (C2, S1, T1, the per-cell pins, the consistency
   rule, the daily limit, the order, the held-back cells, `GET /areas/cells` [no-check: proposed route]), once the
   owner has chosen the two defaults; the real-phone checks below before and after.
4. **The changed-tile measurement**, then **listings** (S2 for `osm`, T2, the batch route, the phone's hashing on
   `put`, the peer route), on an instance with its own planet.
5. **H2 last**, after the owner allows the data flow, with the consent; tested against the sweep's own server.
6. **The weak-tag fix, then P1**, in any wave; P2 on its trigger.

The real-phone checks still owed from T151 and T156, on the owner's phone with step 3's build and, as the baseline,
with the current alpha: a long area download with the app closed and the screen off; one over a service loss of ten
minutes or more; one over a reboot with the network off at boot and back later. Each reports `GET /areas/resume`'s
chain, the ledger's counts and the bytes fetched twice (zero is the target).

Formats (ADR 0033 listing): the store's location (moved, same layout, group fixed); the `dataExtractionRules` file
(new); the ledger (new, derived, rebuilt when missing or of another version); the area records (a per-source version
summary in place of the single version: migrated, the old value read as both ends); the server's listing, batch and
change routes and the `cells` and `cellsKeepDays` announcements (new; an older phone ignores them, a newer phone
against an older server falls back to S1 and T1); the refresh settings (new keys, synced); the per-server consent
(new, stored with the server setting).

## Waiting on the owner

- The refresh default: Automatically (the owner's words) or Only when I tap Update (ADR 0030, the survey), shown with
  the use-ordered rule and the large-area announcement.
- H2's keep-warm list as a new data flow (ADR 0031, ADR 0059): H1 until allowed; the consent's wording.
- The daily limit's default, and the direct-mode downloads of a country, which already pass OpenFreeMap's public
  limit of 3,000 tiles or 50 MB per area (a matter of today's app, raised here).

## What would change this recommendation

- The copy fallback runs on many phones (rename refused widely): W3's per-pin mover costs no more than the copy.
- The first task's measurement puts a DEM cell over 8 MB or a vector cell under 50 kB on typical ground: other sizes,
  before any instance serves listings.
- The changed-tile share between two planet runs is high (most tiles change): S2 saves little; S1 is enough.
- The file count, not bytes, limits phones (T249's snapshot not enough at millions of files): C3 for pinned cells.
- Pack updates measured as the larger share of download traffic, or a bug fixed twice per store: P2 with
  content-defined chunks, or P3.
- The owner refuses the keep-warm data flow: H1 with the batch listing, permanently.
- Android stops a long background download whatever the worker does (the real-phone checks): a foreground service
  started by the user's tap, held for the download.

## Claims to verify

Checked by the critic and corrected above: the shared file system and its two caveats (the cache GID, `EXDEV` on a
project-ID build; AOSP `installd`, Linux `ext4_rename`, `f2fs_rename`); the system's deletion of `cacheDir` under low
storage (`Context#getCacheDir`); D2D and `allowBackup` on Android 12+ (the backup-restore page); SteamKit's chunk id
and DepotDownloader's resume and verify; casync's 64 KiB; the licences; RFC 9110 §13.1.5 and the downloader's gap;
OpenFreeMap's cadence and limits. Still open:

- That an app may `chown` its moved directories from the cache GID to its own GID on current Android (the ownership
  pass's premise; the move's task tests it on the emulator and the owner's phone).
- The changed-tile share between two planet runs (step 4's measurement).
- The cell bytes per route (step 3's first measurement).

## Critic's objections and responses

From `0060-critic-review.md`. Every correction is made in the body.

1. **Serious. W2 is not one call with no moving parts (cache GID, `EXDEV`).** Accepted. W2 is now a procedure:
   rename, else copy and swap with resume, an ownership pass, `moveState` and `groupFixed`, an `EXDEV` test; its
   moving parts name the mover.
2. **Serious. A refused move has no lift or message.** Accepted. The Storage screen states it until the move is done;
   the copy fallback is the lift, running by itself.
3. **Serious. Automatically by default supersedes ADR 0030 unstated and never finishes on a large area under S1.**
   Accepted with change. ADR 0060 supersedes ADR 0030's refresh rule and its rejection of background refetch, and
   states the consistency rule ("newest version present", "N of M places current"); an automatic refresh larger than
   the limit is announced, not started; the order follows use. The default itself goes to the owner with both choices.
4. **Serious. The per-area version machinery is unlisted.** Accepted. The per-cell version drives `holds`, `older`,
   the card, the Storage row and `dropUnheld`; the record keeps a summary (a migrated format); listed under S1.
5. **Serious. S2 and H2 make an instance a mirror; OpenFreeMap's public limits.** Accepted. Vector listings and
   keep-warm need an instance with its own planet or a per-area limit announced in `/live/info`; T1 keeps four in
   flight towards the public instance; the direct-mode country download goes to the owner.
6. **Serious. S2's payoff unmeasured, its DEM half weak.** Accepted. The changed-tile measurement precedes listings;
   `osm` first; DEM listings only on evidence.
7. **Serious. The keep-warm list has no consent surface.** Accepted. `cellsKeepDays` in `/live/info`, a per-server
   consent on first catch-up, H1 until allowed; the wording and H2 itself wait on the owner.
8. **Serious. The ledger duplicates T249's index.** Accepted. One catalogue: the ledger holds cell facts only, the
   mask comes from the index, one writer on the index thread, a binary file, not SQLDelight.
9. **Serious. `If-Range` sends weak tags; P1 amplifies it.** Accepted. The weak-tag fix and its test come before P1;
   the "correct" claim is withdrawn.
10. **Minor. Steam rows are DepotDownloader's.** Accepted. Prior art says so and names the sources.
11. **Minor. Licence footnotes.** Accepted. OSTree LGPL-2.0-or-later, casync 64 KiB and SteamKit2 LGPL-2.1 recorded.
12. **Minor. D2D reasoning one-sided for packs.** Accepted with change. Position stated: a `dataExtractionRules` file
    excludes `packs/` from device transfer in the move's task; kept maps of both kinds stay off a migration.
13. **Minor. Box areas are packs.** Accepted. The scope paragraph says which downloads are cells and which are P1
    jobs, and what the refresh setting does for a pack.
14. **Minor. Cell size per route now.** Accepted. 4 by 4 DEM, 8 by 8 vector, no cell below z10.
15. **Minor. Peers need the phone's own hashes.** Accepted. The phone hashes on `put` in the background.
16. **Minor. Held-back cells' retry.** Accepted. Drawn on the outline, tried once by the next automatic refresh,
    cleared by Retry.
17. **Minor. The tap and the daily limit.** Accepted. A tap never counts; the message names the setting and carries
    Update now.
18. **Minor. Edge-cell counts.** Accepted. Counts corrected; the ledger is streamed, never loaded whole.
19. **Minor. P2's torn-tail premise.** Accepted. P2's case is repair and peer serving, its trigger a measured case.
20. **Minor. Cursor versus ledger.** Accepted. The ledger wins; `GET /areas/resume` reports disagreements.

The critic's answers to this proposal's four open questions are taken: cell sizes per route (14), a binary ledger
(8), the move after T249 merges (build order step 1), H2 with disclosure and consent, H1 until allowed (7). Its hidden
couplings are taken too: the version is a field of the cell, not its key (C2); the frame spans the peer link's pieces
(T2); "one store" is honoured in the protocol, not on disk, with P3's trigger (scope paragraph, P3).
