# 0060. Downloads as a synchronisation in cells over one store

Date: 2026-11-09
Status: under review

Paths without a root are under `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/`; `shared/` paths
are under `shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/`. Every `file:line` is read at the fork of
this task, commit e184b663. T249 (the index snapshot) and T254 (the ancestor fallback) change `online/` files in this
wave without changing the store's layout; the build tasks re-read every site after wave 31's merges.

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
- ADR 0030: a pinned area is served at the content versions it was fetched at until it is refreshed.
- ADR 0033: alpha may change formats; user data (the area records) migrates; derived data (tiles, indexes) may be
  rebuilt by a version in its key, never misread. ADR 0031/0033 §4: a new data flow to a server is flagged to the owner.
- ADR 0034: one cache budget over the phone's caches; pinned tiles have their own budget (`pinnedCacheMb`) outside it.
- ADR 0049's pattern: something new beside a pack is a `derived` entry old phones ignore, never a pack-format change.
- Direct mode (the phone reads OpenFreeMap and the elevation services itself, ADR 0059's public sources) has no
  instance to ask for hashes: every rule here must also work with the public sources alone.
- T229's rule: no disk or binder work on the main thread before its gate; a move of the store happens off it.
- Already ruled out: a per-tile download unit (the owner: "tiles are tiny"); map apps as the benchmark (the owner).

## Prior art

One technical survey of 2026-11-09 (ADR 0044; `out/wave31-research/downloaders.md` [no-check: gitignored], Sonnet,
web). It fetched four primary pages: SteamPipe's upload documentation
(`partner.steamgames.com/doc/sdk/uploading`), desync's README (`github.com/folbricht/desync`), OSTree's formats page
(`ostreedev.github.io/ostree/formats/`) and WorkManager's guide (`developer.android.com/develop/background-work/
background-tasks/persistent/getting-started`); its other lines are from memory and marked so, and two of them were
re-checked for this proposal (DepotDownloader's GPL-2.0 on `github.com/SteamRE/DepotDownloader`; casync's
LGPL-2.1 file on `github.com/systemd/casync`). Claims still resting on memory are under Claims to verify.

| System | Wanted | Present | Stale | Unit | Address | Resume | Verify | Update |
|---|---|---|---|---|---|---|---|---|
| Steam (SteamPipe) | a target manifest id per depot | the installed manifest id, files on disk | installed id differs from the branch's (fetched) | ~1 MB chunks, compressed, encrypted (fetched) | chunk id = SHA-1 of the chunk (mem) | re-diff manifest against disk, fetch missing chunks (mem) | re-hash by chunk, refetch mismatches (mem; DepotDownloader's `-validate` confirmed) | chunk diff of manifests, unchanged chunks reused (fetched) |
| desync / casync | an index of chunk hashes (fetched) | any store or seed holding the hash | a new index | content-defined chunks, ~64 KiB (mem) | chunk file named by its hash, any static host (fetched) | completed chunks are not fetched again (fetched) | per-chunk hash | seeds: chunks found in the old file are reused (fetched) |
| OSTree | a ref to a commit checksum | the installed commit | ref commit differs (fetched) | one object per file, or a static delta (fetched) | object checksum (fetched) | objects present are skipped (mem) | checksums | precomputed `from-to` deltas, per-object fallback (fetched) |
| BitTorrent v2, zsync (one line each, mem) | a merkle root / a control file | pieces held | root differs | 16 KiB leaves / blocks | piece hash | piece bitmap | piece hash | blocks fetched by Range |

Common practice: **wanted is a manifest of units with hashes per version; present is an installed-version id plus
what is on disk; stale is a version or hash mismatch resolved to a unit diff; resume is idempotence (recompute
wanted minus present at every start, no byte journal); verify is re-hashing; an update fetches only the units whose
ids the client lacks.** Units are 64 KiB to about 1 MB, never one per small item. Outliers: OSTree pays the delta on
the publisher; Steam's manifest id is an opaque version, not a hash; zsync diffs on the client with Range reads.

Licences: DepotDownloader is GPL-2.0 (checked), SteamKit2 LGPL (mem), casync LGPL-2.1 (checked), OSTree LGPL (mem):
reading material only. desync is BSD-3 (fetched): a reference, no code copied. Nothing here needs a library.

Settings (the survey): practice splits on whether updates run by themselves, so one setting for that with a visible
data limit; hash checks, atomic commits and resume are not settings. The survey's default was off; this proposal
departs (Settings, below).

## What the code does today, in that vocabulary

| | Today | Where |
|---|---|---|
| Wanted | the area record (`Areas.Record`, geometry, zooms, versions) and its manifest, in `filesDir/areas` | `areas/Areas.kt:55` |
| Present | the tile cache's in-memory index, rebuilt by walking the directory at every cold launch (T242; T249 adds a snapshot) | `online/TileCache.kt`, `online/TileIndex.kt` |
| Stale | per source version (`GET /version`'s `dem`, `osm`); a pinned area keeps its versions (ADR 0030); nothing refreshes without Update | `online/OnlineFetcher.kt:89-106` |
| Unit | one tile per request (six at a time, `POOL`, `areas/AreaDownload.kt:797`); progress by whole **row** at one zoom | `shared/areas/AreaManifest.kt:217`, `TileRow` |
| Resume | a cursor (last whole row, failed rows) saved every 2 s (`CURSOR_SAVE_MS`, `:805`); WorkManager chain with `CONNECTED`, persisted over reboots | `areas/ResumeCheck.kt`, `areas/DownloadWorker.kt` |
| Give up | a run with no whole row is a failure; five stop with Retry (`MAX_FAILURES`, `areas/ResumeCheck.kt:179`), so a country at z14, whose rows are thousands of tiles, can stop with most tiles cached | `:173-179` |
| Verify | a DEM tile must be a PNG, a vector tile must unzip; no hash | `areas/AreaDownload.kt` (class comment) |
| Store | `cacheDir/online/tiles`, one file per tile, pinned and unpinned together; the versions file in `cacheDir` too | `online/OnlineService.kt:45`, `:156`; `storage/Storage.kt:78` |
| Packs | a whole-file byte resume (`.part`, `Range` with `If-Range`), SHA-256 on finish for catalog packs, resumed at launch; no background worker | `packs/PackDownloader.kt:31-60`, `:537` |

So an area download is already a synchronisation; it lacks a small unit with a time and an identity, a ledger of
what is present that is not the cache's walk, a stale test finer than the source version, and a safe home.

## Options

Six questions, each with options at equal depth. The settings each would add are collected after them.

### 1. Where the store lives

**W1. Stay in `cacheDir`, say so.** T249 adds the sentence "Android's Clear cache removes kept maps too". Meets
nothing new: the system may also delete `cacheDir` files under low storage on its own (Claims to verify). Cost: none.
Risk: the 62 GB loss repeats. Exit cost: none. Moving parts: none.

**W2. Move the whole tile tree to `noBackupFilesDir/tiles` by one rename.** `cacheDir` and `noBackupFilesDir` are
both under the app's data directory on the same file system, so `File.renameTo` is one system call however many
GB it holds, not a copy (Claims to verify; a failed rename leaves the tree where it is and `GET /debug/cache` says
`location: cache`). The versions file and T249's snapshot move with it. Unpinned tiles go too: they are then bounded
only by the app's own budgets (ADR 0034, which already bound them), the system's Clear cache frees little, and the
system's Settings shows the space as app data, so its Clear storage warns properly. The Storage screen's own clear
action stays the user's way to drop unkept tiles. `noBackupFilesDir` rather than `filesDir` because `allowBackup` is
false (`AndroidManifest.xml:37`) but on Android 12 and later that does not stop a device-to-device transfer, which
would copy tens of GB (Claims to verify). Contour and shade caches stay in `cacheDir` (derived, rebuilt offline from
the DEM). Cost: a few lines in `OnlineService`, run on the index thread before the walk, and an upgrade test.
Exit cost: one rename back. Moving parts: none at run time.

**W3. Two roots: kept tiles in `noBackupFilesDir`, the rest in `cacheDir`.** A pin moves each of its tiles across
(a rename each), an unpin moves them back, a lookup stats both roots. Meets the loss as W2 does and keeps Clear cache
meaningful for browsing tiles. Cost: the move per tile on pin and unpin (tens of thousands of renames for a large
area, interleaved with overlapping pins: a tile two areas hold), two stats per miss, and the upgrade moves only the
kept tiles one by one (the 62 GB case: hundreds of thousands of renames, minutes at T242's walk rate). Exit cost: merge
the roots. Moving parts: a mover with its own resume.

### 2. The cell

Not an option: a tile as the unit (the owner). The cell is the unit of progress, of the fetched-at time and of the
failure count in every option; option 4 says whether it is also the unit of transfer.

**C1. A bounded run of a row.** Today's `TileRow` cut into runs of at most 32 tiles; the cursor moves by run; each run
gets a fetched-at time in the record. Meets the "gives up with most cached" defect (progress every 32 tiles). The run
depends on the area's shape, so two overlapping areas or a peer cannot name the same unit: no shared identity, no
content address. Cost: small (the cursor's grain). Exit cost: none. Moving parts: none. Who else: nobody in the survey;
it is a byte journal by another name, which the survey's practice avoids.

**C2. An aligned block: 4 by 4 tiles of one route at one zoom.** Cell `(route, z, x >> 2, y >> 2)`, fixed by the tile
grid, so every area, phone and instance names the same cell. An area wants a 16-bit mask of each cell its cover
touches (several areas OR their masks). Size, from `AreaManifest.estimate` (`shared/areas/AreaManifest.kt:317`): a DEM
cell up to 16 times 160 kB, 2.5 MB in mountains, less on flat ground; a vector cell 160 kB at z13-14 and up to
13 MB at z5-7, where an area touches a handful. That brackets the survey's 1 MB. Counts: a million tiles is about
62,000 cells, so wanted minus present is computed at every start in milliseconds. Meets resume by recomputation
(Steam's shape), the shared identity the content address needs, and the edge of a corridor (the mask, not the whole
block, is fetched). Cost: the walk by cell instead of by row (`AreaManifest.walk` gains a cell order), a ledger
(option 3). Risk: 4 by 4 is a guess; the first task measures cell bytes over the Rainier and Mont Blanc areas and may
pick 8 by 8 (64-bit mask) for vector. Exit cost: the ledger is derived; dropping it returns to the row walk.
Moving parts: the ledger file.

**C3. An aligned block stored as one file (a bundle).** As C2, but the cell's tiles are one file on disk (a small
PMTiles or a framed concatenation). Cuts the file count sixteenfold, so T242's walk and the inode load shrink, and a
cell is atomic on disk by one rename. But browsing writes one tile at a time into a cell, which then rewrites a
2.5 MB file per tile, or keeps loose tiles beside bundles (two layouts). Cost: a new reader in the tile path the map
uses, a compaction step. Exit cost: unpack every bundle (tens of GB rewritten). Moving parts: the bundle format and
its compactor. Who else: Steam and OSTree store units as files; map stores keep tiles loose or in one archive.

### 3. The ledger and the stale test

**S1. Source versions plus a fetched-at per cell.** A ledger `tiles/cells.db` [no-check: proposed name] (a compact
binary file like T249's snapshot, or SQLDelight's driver, chosen in the build) with one row per cell: key, the version
it was fetched at, fetched-at, the present mask, bytes, failures. Stale: the source's version moved (today's rule,
ADR 0030 unchanged), or, for a source with no version, fetched-at older than a per-source age (vector 30 days, DEM
365 days; constants, not settings). Meets direct mode as it is. Cost: the ledger, kept in step with `put`/`remove`.
Risk: a version is per source, so a new OpenFreeMap build marks every vector cell stale though most did not change:
the whole-area refetch the owner calls monolithic. Exit cost: the ledger is derived (rebuilt from the store's files,
fetched-at taken from mtimes). Moving parts: the ledger's writer.

**S2. S1 plus per-tile hashes from the source that has them.** An instance answers a cell listing: the 16 (or 64)
tiles' hashes, SHA-256 cut to 16 bytes, for the tiles present (a missing tile is a zero entry), and the listing's
own hash is the cell's id, the Steam manifest id for one cell. The phone keeps the hashes it fetched in the ledger
row (256 bytes a cell; 16 MB for a million tiles). Stale: the listing's id differs from the row's; the diff of the two
listings is the tiles to fetch. A source without listings (direct mode, an older server) falls back to S1. Meets the
owner's "sufficiently out of date" as "changed", with no clock; a new upstream build costs only the changed tiles. Cost:
the server hashes tiles it serves and keeps listings per version (the live server makes DEM tiles on demand, so a
listing of a never-made cell makes it: the helper's job, option 6). Risk: a renderer change that alters bytes but not
meaning marks cells stale; the server versions its renderer anyway. Exit cost: listings are derived on both sides;
dropping them returns to S1. Moving parts: a server route, a server-side listing store, the ledger's hash column.

**S3. Fetched-at only (a time to live).** Every cell older than N days is refetched. Meets direct mode trivially.
Fetches unchanged data on a clock: the survey's practice avoids it, and it would steer data use by time, not change.
Exit cost: none. Moving parts: none beyond S1's ledger.

In all three, the failure count moves to the cell: a cell that fails in three runs is held back (the area ends
`partial` with "N places could not be fetched" and Retry), and `MAX_FAILURES` counts runs that completed no cell.

### 4. The transfer and the content address

**T1. Per-tile requests as today, the cell for bookkeeping.** Each wanted tile of a cell is a GET through
`OnlineFetcher` (six in flight); the cell is committed in the ledger when its mask is whole. Works with every source,
public ones included. Cost: none new. Exit cost: none. Moving parts: none.

**T2. One request per cell for the tiles not held.** `GET /cells/<route>/<z>/<cx>/<cy>?have=<listing id>` answers the
listing and the bodies of the tiles whose hashes differ, framed (count, then index, length, bytes). One round trip per
cell instead of 16; the peer link (sealed 4 MiB pieces, PLANNING › Peer sources) carries the same route. Falls back to
T1 for a source without it. Cost: a server route and a frame reader in `shared` (pure Kotlin). Exit cost: the route
stays or goes; phones fall back. Moving parts: the route on the server and in `share/PeerServe.kt`.

**T3. Blobs by hash: `GET /blob/<hash>`.** Any holder serves any tile by its content hash; a listing names the
hashes. The most general (identical ocean tiles stored once, any peer serves any blob) and the survey's purest model.
Cost: the store keyed by hash beside the key by place (a second index, a reference count for eviction), every tile
read through an indirection. Exit cost: rewrite the store back to place keys. Moving parts: the hash index and its
garbage collection.

The content address in every option is per tile, with the cell's listing id as the unit's address: per tile because
an edge cell is wanted in part and one changed tile should cost one tile; per cell for the stale test and the
announcement. A cell-only hash would refetch a whole 2.5 MB cell for one changed tile and could not match a partial
cell.

### 5. Packs

**P1. A byte resume, plus the background worker.** The pack job stays as it is (`.part`, `Range`, `If-Range`, SHA-256
at the end) and joins ADR 0046's WorkManager chain, so a pack, like an area, resumes with the app closed and after a
reboot (today it resumes only at the next launch, `packs/PackDownloader.kt:31-36`). Cost: the job's hand-over to the
worker. Exit cost: none. Moving parts: one more job kind in the chain.

**P2. P1 plus a block-hash list as derived data.** Beside the whole-file SHA-256 the catalog's `derived` list (ADR
0049's) names a list of SHA-256 per 4 MiB block. Resume checks the last block before appending (a power loss can
leave a torn tail, today found only by the whole-file hash at the end, which then discards gigabytes); a failed
verify refetches the bad blocks only; a peer can serve blocks. It does not give delta updates: the 2026-11-02 triage
found that `PmtilesWriter` shifts every later offset when one tile changes, so fixed blocks do not line up across
versions (content-defined chunking would; not measured). Cost: one derived file per archive on the server, a block
check in the downloader. Exit cost: ignore the list. Moving parts: the server's block hasher.

**P3. A pack as a set of cells in the one store.** A region pack becomes its cells; the archive file goes. Every pack
reader (the DEM compositor, the vector reader, the routing graph builder, search) then reads from the tile store.
This is the owner's single store taken literally, and deltas fall out of S2. Cost: rewriting every reader of
`PackSet`, imports by file, the server's pack build and the catalog; the routing graph and search index, built from
the vector archive, need a new input path. Exit cost: re-pack every phone's cells. Moving parts: most of the app's
data path.

### 6. Announcement and the always-on helper

**H1. None: the phone polls.** The phone checks its source's `GET /version` (today) and, with S2, the listings of its
wanted cells, on the refresh setting's schedule. Cost: none new. Per-cell listings for a large area are many requests
(62,000 listings for a million tiles; a batch form of the route answers a z8 block's listings at once).

**H2. A stateless catch-up.** `POST /cells/changes` with the phone's wanted areas (the geometries it already sends to
`POST /area`) and, per route, the instance's change token it last caught up to; the answer is the cells whose listing
changed since, with sizes. The instance keeps a change log per version and, as the "always-on" part, keeps the cells
of areas it was asked about built and hashed for a time after each ask (30 days, renewed by the next ask), so a
phone's catch-up meets warm cells. No account: the instance keeps area geometries, not who asked. An instance and a
peer announce the capability in `/version` (or `/live/info`) and the peer catalog (`cells: 1`); a federated instance
announces the same, its coverage as the catalog's areas. Cost: the route, a change log, a keep-warm list with expiry
on the server. Risk: an instance now stores where its users go for 30 days, a new data flow (flagged). Exit cost: the
route goes; phones fall back to H1. Moving parts: the server's change log and keep-warm job.

**H3. A registered phone.** The phone registers its wanted areas under its ADR 0051 identity key; the instance
pushes or holds changes per phone. Most efficient for many small changes; an account in all but name, and a phone's
identity linked to its places on someone's server. Exit cost: drop the registration store. Moving parts: identity on
the server, a per-phone store.

## Settings (ADR 0044)

| Behaviour | Setting or one behaviour | Why |
|---|---|---|
| Kept maps refresh themselves when their source changed | **Setting** "Keep offline maps up to date": Automatically (default), Only when I tap Update | The survey found apps split; the owner wants it to "just work in the background", so the default departs from the survey's off |
| How much an automatic refresh may use | **Setting** "Background updates up to" 100 MB a day (default), 50 MB, 500 MB, 2 GB, no limit; reaching it shows "Update waiting: daily limit reached" on the map's list | ADR 0023: a visible limit, never a network check |
| The store's location, the cell size, the hashes, the atomic commit, resume, the failure rule, the age constants | One behaviour | Correctness and safety; one practice is clear |
| Which areas refresh first | One behaviour now (the area open or being navigated, then the rest oldest-first); by use later with the 2026-11-04 idea | No survey yet; that idea carries it |

The two strings and the row's place are the owner's to adjust (ADR 0031). No product name in any of them.

## Recommendation

**W2, C2, S2 with S1 as its fallback, T1 then T2, P1 now and P2 when measured, H2.** In terms of the constraints:
W2 ends the loss with one rename and no new moving part; C2 is the only cell with an identity the phone, a peer and an
instance share, which every later step needs, at the survey's unit size; S2 makes "out of date" mean "changed" and
costs a new upstream build only its changed tiles, while S1 keeps direct mode and older servers working; T1 first
because it needs no server change, T2 when the server has listings; P1 closes the pack's background gap now, and P2
waits for a measured torn-tail or repair case; H2 is the helper ADR 0059 allows, stateless and keyed by nothing but
geometry. Rejected for now, kept as the record: W1 (the loss repeats), W3 (a mover with its own resume for what W2
does by a rename), C1 (no identity), C3 (two layouts or a rewrite per browsed tile), S3 (data by the clock), T3 (a
second index and garbage collection before any dedupe is measured), P3 (every pack reader rewritten), H3 (an account).

Build order (wave 32's; the coordinator decides after the critic):

1. **The store's move** (W2), alone and first: the rename on the index thread before the walk, the versions file and
   T249's snapshot with it, `location` in `GET /debug/cache`, an upgrade test over a fixture tree; the owner's phone
   then keeps its maps through Clear cache. T249's sentence becomes "Clear cache no longer removes kept maps", or goes.
2. **Cells on the phone alone** (C2, S1, T1): the ledger, the walk by cell, per-cell fetched-at and failures,
   `MAX_FAILURES` over runs with no cell, resume as wanted minus present (the cursor kept one wave as a cross-check),
   `GET /areas/cells` [no-check: proposed route] with counts by state; the real-phone checks below before and after.
3. **Listings on the server** (S2, then T2): `GET /cells/...` with hashes per tile and per version, the batch form,
   the phone's stale test by listing, the peer route over the sealed link.
4. **The refresh setting and its limit**, with the order above.
5. **H2**, flagged to the owner as a new data flow before it is built; tested against the sweep's own server.
6. **P1** fits any wave; P2 on its trigger.

The real-phone checks still owed from T151 and T156, run on the owner's phone with step 2's build and, as the
baseline, with the current alpha: a long area download with the app closed and the screen off; one over a service
loss of ten minutes or more; one over a reboot with the network off at boot and back later. Each reports
`GET /areas/resume`'s chain, the ledger's counts and the bytes fetched twice (zero is the target).

Formats (ADR 0033 listing): the store's location (moved, same layout); the ledger (new, derived, rebuilt from the
files when missing or of another version); the area records (unchanged); the server's listing and change routes and
the `cells` capability (new; an older phone ignores them, a newer phone against an older server falls back to S1 and
T1); the refresh settings (new keys, synced). Nothing a user made migrates.

## What would change this recommendation

- The rename across `cacheDir` and `noBackupFilesDir` copies on some phones (another mount): W3's mover, or a copy
  in the background with the old tree served until it finishes.
- The first task's measurement puts a 4 by 4 DEM cell over 8 MB or a vector cell under 50 kB on typical ground:
  a cell size per route (8 by 8 for vector).
- OpenFreeMap or the instance's builds turn out to change most tiles each time: S2 saves little, and S1 is enough.
- The file count, not bytes, limits phones (T249's snapshot not enough at millions of files): C3 for pinned cells.
- Packs on phones are updated often and measured big: P2 with content-defined chunks, or P3.
- The owner rules the keep-warm list out as a data flow: H1 with the batch listing.
- Android stops a long background download whatever the worker does (the real-phone checks): a foreground service
  started by the user's tap, held for the download.

## Claims to verify

- `cacheDir` and `noBackupFilesDir` are on the same file system on Android, so `renameTo` is atomic and free of a
  copy (`Context.getNoBackupFilesDir` and `getCacheDir` reference; `/data/user/0/<package>/{cache,no_backup}`).
- The system may delete `cacheDir` files when storage runs low (`Context.getCacheDir` reference,
  `StorageManager.getCacheQuotaBytes`).
- On Android 12 and later `android:allowBackup="false"` does not stop device-to-device transfers, and
  `noBackupFilesDir` is excluded from both backup and transfer (the Auto Backup and data extraction rules pages).
- Steam: the chunk id is the SHA-1 of the chunk, a killed client re-diffs and fetches only missing chunks, Verify
  re-hashes by chunk (the survey from memory; SteamKit's `DepotManifest` source or SteamDB's write-ups).
- casync's default average chunk is 64 KiB (casync's man page); SteamKit2's licence is LGPL-2.1.
- RFC 9110 §13.1.5: `If-Range` takes a strong validator, so the downloader's `ETag` or date check is correct.
- A torn tail after power loss on the phone's file system is possible for an appended `.part` (P2's premise).
- OpenFreeMap's build cadence and the share of tiles that change between builds (S2's payoff).

## Open questions for the critic

- Is the cell of C2 too small for transfer and too big for a corridor, and should DEM and vector cells differ?
- Is a SQLDelight table or a binary file the right ledger, given T249's snapshot does the same job for the index?
- Should step 1 (the move) go before T249's snapshot lands in its new place, or after?
- Is H2's 30-day keep-warm list acceptable under ADR 0059 and ADR 0031's data-flow rule, or is H1 the default?

## Critic's objections and responses

(none yet)
