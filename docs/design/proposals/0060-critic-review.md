# Critic review of proposal 0060: downloads as a synchronisation in cells over one store

Date: 2026-11-09
Status: review of `0060-downloads-as-a-synchronisation-in-cells.md` at `under review` (T244, before any build)

Read in order: `CLAUDE.md`; the proposal; ADRs 0023, 0025, 0030, 0031, 0033, 0034, 0044, 0046, 0049, 0051, 0059;
PLANNING › Design goals (areas, pinned tiles and versions, caches) and › Peer sources; the task text T244, T249 and
T254; the idea of 2026-11-07 and its three related ideas; the research report; the code the proposal cites at e184b663
(`online/OnlineService.kt`, `TileCache.kt`, `TileIndex.kt`, `OnlineFetcher.kt`, `DirectSources.kt`,
`areas/AreaDownload.kt`, `ResumeCheck.kt`, `Areas.kt`, `storage/Storage.kt`, `packs/PackDownloader.kt`,
`share/PeerServe.kt`, `PeerFiles.kt`, `shared/.../areas/AreaManifest.kt`, `packs/Sha256.kt`,
`serverlib/.../ServeStatic.kt`). Not re-argued, as briefed: per-tile units, map apps as the benchmark, a central server.

Verdict in one line: the direction is right (a store outside `cacheDir`, an aligned cell with a shared identity, a
ledger, per-tile hashes where a source has them, packs on the worker, a stateless helper); the recommendation is
upheld with corrections. 0 blocking, 9 serious, 11 minor. The two serious findings that change the build order are
that W2's "one rename, no moving part" is false in detail on Android (objections 1 and 2) and that the Automatic
default with the per-source version rule contradicts ADR 0030 and never catches up on a large area (3 and 4).

## Claims verified

| Claim | Found | Source |
|---|---|---|
| `cacheDir` and `noBackupFilesDir` share a file system; `renameTo` is one call | Holds, with two caveats (objection 1). Both are under the app's private data directory; the docs say of each that "the returned path may change over time if the calling app is moved to an adopted storage device" (they move together). A rename within one file system is atomic (`rename(2)`). Caveat A: `installd` creates `cache` with mode `02771` and the app's **cache GID** (`prepare_app_cache_dir(path, "cache", 02771, uid, cacheGid, projectIdCache)`), so every tile file carries the cache GID and the directories pass it on; `collectQuotaStats` counts cache space **by that GID** (`GetOccupiedSpaceForGid(uuid, cacheGid)` into `stats->cacheSize`). A rename keeps the GID. Caveat B: ext4 and f2fs refuse `rename()` into a directory whose inherited **project ID** differs (`ext4_rename`: `if ((ext4_test_inode_flag(new_dir, EXT4_INODE_PROJINHERIT)) && (!projid_eq(...))) return -EXDEV;`; `f2fs_rename` the same with `FI_PROJ_INHERIT`). `installd` gives `cache` its own project ID when `internal_storage_has_project_id()` is true; in AOSP main that function has `return false` hard-coded with the marker-file check (`misc/installd/using_project_ids`) commented out, so upstream does not use project IDs for app data today; a vendor build with the marker enabled would make `renameTo` return false. | `developer.android.com/reference/android/content/Context` (`getCacheDir`, `getNoBackupFilesDir`); AOSP `frameworks/native/cmds/installd/InstalldNativeService.cpp` and `utils.cpp` (`set_quota_project_id`, `FS_PROJINHERIT_FL`); Linux `fs/ext4/namei.c`, `fs/f2fs/namei.c` |
| The system may delete `cacheDir` files under low storage | Confirmed verbatim: "The system will automatically delete files in this directory as disk space is needed elsewhere on the device. The system will always delete older files first, as reported by File#lastModified()." and "Apps are strongly encouraged to keep their usage of cache space below the quota returned by StorageManager#getCacheQuotaBytes(UUID). If your app goes above this quota, your cached files will be some of the first to be deleted". Note: `TileCache` touches a file's mtime only on a hit, so a pinned tile not viewed for months is exactly what the system deletes first; this strengthens W2 beyond the Clear cache case. | `Context#getCacheDir()` |
| On Android 12+ `allowBackup="false"` does not stop D2D; `noBackupFilesDir` is excluded from both | Confirmed: "On devices from some device manufacturers, specifying `android:allowBackup="false"` does disable backups to Google Drive, but doesn't disable D2D transfers for the app." and "Files in directories returned by `getCacheDir()`, `getCodeCacheDir()`, or `getNoBackupFilesDir()` are always excluded even if you try to include them"; with no `<device-transfer>` rules "that mode is fully enabled for all content except for `no-backup` and `cache` directories". The 25 MB quota is the cloud's; no size limit is stated for D2D. Consequence the proposal misses: `filesDir/packs` (GBs) is transferred today on those devices (objection 12). | `developer.android.com/about/versions/12/backup-restore`; `developer.android.com/identity/data/autobackup` |
| Steam: chunk id is the SHA-1 of the chunk; a killed client re-diffs; Verify re-hashes by chunk | Chunk id confirmed from SteamKit2's `DepotManifest.ChunkData.ChunkID`: "the SHA-1 hash chunk id", beside an Adler32 `Checksum`, `Offset`, compressed and uncompressed lengths. The re-diff and per-chunk verify are confirmed for **DepotDownloader**, not the Steam client: it matches chunks across manifests by `ChunkID`, checks existing bytes per chunk by Adler32, short-cuts on a whole-file hash match, moves the old file to `.DepotDownloader/staging`, and fetches only `neededChunks`; `-validate` is "include checksum verification of files already downloaded". The Steam client's own behaviour is not publicly documented (objection 10). | `github.com/SteamRE/SteamKit` `DepotManifest.cs`; `github.com/SteamRE/DepotDownloader` `ContentDownloader.cs`, README |
| casync's default average chunk is 64 KiB; SteamKit2 is LGPL-2.1 | Both confirmed: `#define CA_CHUNK_SIZE_AVG_DEFAULT ((size_t) (64U*1024U))`, min a quarter and max four times that; SteamKit's `LICENSE` is "GNU LESSER GENERAL PUBLIC LICENSE Version 2.1". OSTree's `COPYING` is the "GNU LIBRARY GENERAL PUBLIC LICENSE Version 2" (LGPL-2.0, not 2.1). desync BSD-3 as the survey says. | `systemd/casync` `src/cachunker.h`; `SteamRE/SteamKit` `LICENSE`; `ostreedev/ostree` `COPYING` |
| RFC 9110 §13.1.5: `If-Range` takes a strong validator, so the downloader's check is correct | The RFC confirmed ("A client MUST NOT generate an If-Range header field containing an entity tag that is marked as weak"; a date only when the client has no entity tag and the date is a strong validator; the server evaluates by exact match). The code **does not** meet it: `PackDownloader.HttpConn` sends `f.etag ?: f.lastModified` and `f.etag = conn.header("ETag")` is stored verbatim; no `W/` check anywhere in `packs/`. The project's own server emits a strong tag (`ServeStatic.kt`: `"<size hex>-<mtime hex>"`); a static host behind nginx with gzip emits weak tags (objection 9). | `rfc-editor.org/rfc/rfc9110` §13.1.5; `packs/PackDownloader.kt` (the `HttpConn` constructor and the `Restart` branch) |
| A torn tail after power loss is possible for an appended `.part` | Not confirmable from a primary source and probably the wrong premise: ext4 in `data=ordered` and f2fs make a **short** tail (length behind the bytes written) far likelier than a tail of garbage, and the part file's length is already the resume point. P2's case is repair and peer serving, not the torn tail (objection 19). | reasoning over the file systems' documented ordering; no measurement |
| OpenFreeMap's build cadence and the share of tiles that change | Cadence confirmed: "Planet is generated weekly, every Wednesday", "Full planet runs are uploaded weekly", "We keep the last 4 runs and the first run of each of the last 6 months". The share of changed tiles is not published anywhere found (objection 6). Also found, bearing on S2 and H2: the public instance's stated use limits for apps, "areas of up to 3,000 tiles or 50 MB, with at most 4 parallel requests and a unique User-Agent. For anything larger, please contact us" (objection 5). Licence MIT. | `openfreemap.org`; `github.com/hyperknot/openfreemap` README |

Also read against the code: in direct mode the phone knows OpenFreeMap's build id (`DirectSources.openFreeMap()` parses it
from the TileJSON's tile URL) but its cache keys are unversioned (`PeerServe`: "`GET /version` is 404 (unversioned
keys)"; no `X-Dystopo-Version` in `DirectSources.kt`), so S1's "a source with no version" branch is direct mode's
every day, not a corner.

## Objections

### Serious

1. **W2 is not "one system call" with "no moving parts at run time".** Two facts from the sources above: (a) every
   tile file and directory carries the cache GID and the `02771` setgid bit, which a rename keeps, so after the move the
   system's storage statistics (quota by GID) still show the tree under "Cache" with a Clear cache button that frees
   nothing, and every new tile written into the moved tree inherits the cache GID from its directory; the proposal's
   sentence "the system's Settings shows the space as app data, so its Clear storage warns properly" is false on a
   quota-enabled device (every current one). (b) On a build that uses project IDs for app data, `renameTo` returns
   false with `EXDEV`, and the proposal's answer ("the tree stays where it is") leaves exactly that user exposed to the
   62 GB loss. Resolve: design the move as a procedure, not a call: rename if it succeeds, else a copy-and-swap in the
   background with the old tree served until the swap (the proposal's own "what would change" line, promoted to the
   design); then an ownership pass that `chown`s each directory to the app's own GID and clears the setgid bit (the
   owner of a file may change its group to one it belongs to), with files fixed lazily by the index thread that already
   stats each one; `GET /debug/cache` reports `location`, `moveState` and `groupFixed`; the upgrade test covers the
   `EXDEV` branch with a fake. Moving parts: the mover's resume (what the proposal credited W3 with and W2 not).

2. **A refused move has no lift and no message.** The only sign is `location: cache` in a debug route. Who can lift
   it: nobody from the app. Resolve: the Storage screen says "Kept maps are in the cache on this phone; Android's Clear
   cache would remove them" until the move completes, with the copy fallback of objection 1 running; the sentence T249
   adds becomes this state's sentence rather than going away.

3. **"Automatically" by default supersedes ADR 0030 without saying so, and under S1 it cannot finish.** ADR 0030
   decided "Refresh is explicit or on the route" and rejected "re-fetching a stale area in the background as soon as its
   source moves on (data the user did not ask for, and an area half old and half new when the network drops)". The
   proposal cites ADR 0030 as "unchanged" while making background refresh the default. Under S1 a source version is per
   source, so one new build marks every vector cell of every area stale; with the 100 MB daily default a country at z14
   (France: 1.44 million tiles, PLANNING; vector alone over 10 GB at the manifest's estimates) needs months per build
   and OpenFreeMap builds weekly: the steady state is "Update waiting: daily limit reached" for ever, cells refreshed in
   rotation, the area permanently mixed between builds, which is the state ADR 0030 refused. Resolve: the ADR that
   promotes this proposal supersedes ADR 0030's refresh rule explicitly and states the new consistency rule (a tile is
   served at the newest version present; the card shows "N of M places current"); automatic refresh of an area whose
   stale bytes exceed the daily limit is announced on the card with the size and the two lifts (raise the limit, Update
   now) instead of started; the default order follows use (the 2026-11-04 idea) so the commute refreshes and the
   country waits; and the survey's "off" default stays on the table for the owner beside the owner's sentence.

4. **The per-area version machinery is a moving part the proposal does not list.** `Areas.Record.versions` is one
   map per source per area; `AreaPins.holds`, `Areas.older`, the card's "Older than the source", the Storage screen's
   pinned row and `OnlineFetcher.dropUnheld` all read it. A refresh by cell leaves an area holding two versions, so the
   record's single version is wrong from the first refreshed cell: `holds` would drop the half not yet refreshed at a
   version change, or keep both for ever. Resolve: the ledger's per-cell version is the truth for `holds` and `older`
   (a lookup tries the versions the ledger names for that cell); the record keeps a summary; `dropUnheld` runs per cell
   when its mask is whole at the new version. List this under S1's moving parts and in step 2 of the build order.

5. **S2 and H2 turn an instance into a mirror of every wanted area, and OpenFreeMap's public instance forbids it.**
   The live server fetches OpenFreeMap tiles on request and renders DEM tiles on demand; a listing of a cell it never
   served means fetching or rendering its tiles, and keep-warm means doing so again for every weekly build. The public
   instance's stated limit for apps is 3,000 tiles or 50 MB per area, four requests in parallel. Resolve: the ADR states
   that S2 listings and H2 keep-warm for vector tiles require an instance with its own planet (OpenFreeMap publishes the
   weekly full planet; the install script's option) or a server-side limit per asked area, announced in `/live/info`;
   direct mode downloads of a country already exceed the same limit today, which the owner should hear (a pre-existing
   matter, flagged under ADR 0031's data-flow rule because the phone's User-Agent and request pattern are the project's).

6. **S2's payoff is asserted, not measured, and its DEM half is weak.** The share of vector tiles that change between
   two weekly builds is unpublished; DEM tiles are rendered deterministically from a source version, so per-tile DEM
   hashes buy nothing unless a regional update (3DEP lidar) changes part of a cover. Resolve: the first server task
   measures the changed-tile share over the Rainier and Mont Blanc areas between two consecutive planet runs before the
   listing store is built; S2 ships for `osm` first; DEM listings only if the measurement shows partial change; otherwise
   S1's DEM version with the 365-day age stands.

7. **H2's keep-warm list is a 30-day record of where an instance's users go, with no consent surface.** The proposal
   flags it (right) but offers only "no account". A corridor geometry is a commute; an instance log pairs it with an
   address. The phone already sends geometries to `POST /area` for the manifest, so the new part is the retention.
   Resolve: an instance announces its retention in `/live/info` (`cellsKeepDays`); the first catch-up against an
   instance shows "This server keeps the shapes of your offline maps for 30 days to prepare updates" with Allow and Not
   on this server, remembered per server; until allowed the phone uses H1's batch listing. A consent, not a setting
   (ADR 0044's "when not": privacy rules). The owner decides the default of the dialog (ADR 0031).

8. **The ledger is a second catalogue of the same files beside T249's index.** `TileIndex` holds every key with
   size and touch minute; T249 snapshots it; the proposal's `cells.db` holds every cell's present mask, bytes and
   fetched-at: two writers, two files, one truth, drift between them (the same class of defect T242 and T249 are
   fixing). Resolve: the ledger holds only what the index cannot (fetched-at, version, failures, listing id and hashes);
   the present mask is computed from the index (`TileCache.onIndex` already reports every add and remove) or cached and
   validated against it as T249 validates its snapshot; one writer, on the index thread. A binary file like the snapshot,
   not SQLDelight (a derived file rebuilt on version mismatch needs no migration; `.claude/rules/trackdb.md`'s cost is
   for user data).

9. **The `If-Range` check is not correct as the proposal claims; P1 would amplify it.** A weak `ETag` is stored
   and sent; a conforming server answers 200 to a mismatch, so `PackDownload.plan` yields `Restart` on every resume:
   safe, but a multi-GB pack from a user's nginx-with-gzip host restarts after every stop, and P1 makes those stops
   routine (a worker slice is about ten minutes, ADR 0046). Resolve: strip or ignore tags starting `W/`, fall back to
   `Last-Modified`, else resume without `If-Range` only when the catalog's SHA-256 will catch a splice; a unit test
   with a weak tag; the measured restart count in P1's report.

### Minor

10. Prior art attributes to "Steam" what is confirmed only for DepotDownloader (re-diff, per-chunk verify with a
    whole-file shortcut and Adler32 on existing bytes); the Steam client's behaviour is undocumented. Mark the cells.
11. Licence footnotes: OSTree is LGPL-2.0-or-later; casync 64 KiB confirmed; SteamKit2 LGPL-2.1 confirmed. Reading
    material only, as the proposal says.
12. The D2D reasoning is one-sided: tiles leave the transfer set, while `filesDir/packs` (GBs) and the area records
    stay in it on the manufacturers that ignore `allowBackup`. Either packs move to `noBackupFilesDir` too (a path
    change every reader learns) or a `dataExtractionRules` file excludes `packs/` from `<device-transfer>`, or the
    owner decides that kept maps should follow a phone migration (arguably a feature). State the position.
13. A box area is a region pack (ADR 0027, as built): the cell model covers corridors, polygons and country tile areas,
    not a box downloaded through `/build`. Say which downloads are cells and which are P1 jobs, and what "Keep offline
    maps up to date" does for a pack (whole Update until P2), since ADR 0056's one list shows them together.
14. Pick the cell size per route now: the proposal's own figures (a DEM cell up to 2.5 MB, a vector cell 160 kB at
    z13 to 14 and 13 MB at z5 to 7) span two orders of magnitude; 4 by 4 for DEM, 8 by 8 for vector, and no cell below
    z10 (a handful of tiles fetched whole) avoids a ledger format change after the first measurement.
15. T2 over the peer link needs the peer's hashes; tiles fetched in direct mode or from an older server have none.
    The phone must hash on `put` (pure-Kotlin SHA-256 of tens of GB is minutes of CPU, acceptable in the background,
    not at first use) and the ledger's hash column is filled by the phone, not only from listings.
16. A cell held back after three failed runs: say whether the next automatic refresh retries it and that the card's
    "N places could not be fetched" names or shows them; Retry is the lift, documented.
17. The daily limit and the tap: state that Update by tap never counts against "Background updates up to" (ADR
    0023's pattern: limits steer automatic use), and that "Update waiting: daily limit reached" names the setting and
    carries Update now.
18. "A million tiles is about 62,000 cells" ignores edge cells; a z14 corridor is nearly all edge cells, so the row
    count is two to three times that. Fine for a file read in a stream; say the ledger is never loaded whole into the
    576 MB heap.
19. P2's premise (the torn tail) is unproven and probably wrong on ext4 and f2fs (a short tail is the likely state);
    P2's trigger should be a measured repair or peer-serving case, as the recommendation already half says.
20. One wave with both the cursor and the ledger: say which wins when they disagree (the ledger, since it is derived
    from the store) and what the cross-check reports.

## Refusals and their lifts

| Mechanism | Who lifts, from where, while running | Message says so | Status |
|---|---|---|---|
| Daily background limit reached | The user, Settings (the limit) or the card's Update now | Must name the setting and carry the action | Objection 17 |
| Automatic refresh larger than the limit | The user, the card (raise or Update now) | Not specified | Objection 3 |
| A failed rename, tree left in `cacheDir` | Nobody; no fallback | No | Objections 1, 2 |
| A cell held back after three failed runs | The user, Retry on the card | "N places could not be fetched" | Objection 16 |
| `MAX_FAILURES` runs with no cell | The user, Retry | As today | documented |
| A listing id mismatch (hash differs) | Automatic refetch of the differing tiles; held back after three runs | As above | documented |
| An instance without listings or `/cells` | Automatic fallback to S1 and T1 | Not shown; fine | documented |
| A keep-warm list the user does not want | Not specified | No | Objection 7 |
| The pinned budget | The user, Settings › Storage | As today | documented |

## Hidden coupling

- **The peer link (ADR 0051):** the cell shape fixes nothing in the identity or sealing layer, but T2's frame can exceed
  the link's 4 MiB piece (a 13 MB z5 vector cell); the reader must span pieces. The real coupling is objection 15: a
  peer can only announce hashes it has.
- **The pack format (ADR 0049):** P1 and P2 keep the pattern (a `derived` entry); the recommendation keeps two stores,
  archives and loose tiles, with two sync models, and should say plainly that the owner's "one store" is honoured in
  the protocol (listings, cells, hashes) and not in the store; P3 stays the only route to one store.
- **T249's snapshot:** objection 8; and the move (step 1) must follow T249's merge, since both own `OnlineService.kt`
  and the snapshot's path moves with the tree (the proposal's third open question).
- **The server's catalog API:** `cells: 1` in `/version` or `/live/info` and the peer catalog is additive; fine. The
  listing store per version and the keep-warm job are the first server-side state that depends on what phones asked.
- **The cell key and the version in the cache key:** the store keys tiles as `<source>/<route>@<version>/z/x/y`; a
  cell's identity must leave the version out and carry it as a field, per source (two sources hold the same cell under
  two prefixes), or the "same cell on every phone" property is lost.

## The strongest case against the recommendation, per question

- **W3 over W2:** two roots match Android's own semantics: browse tiles stay cache (the system's low-storage deletion
  and quota keep working for them), kept tiles are data, and the statistics are right without an ownership pass. The
  cost is the mover per pin and unpin. With objection 1's fixes W2 reaches the same end state in one move; W2 stands.
- **C1 over C2:** the per-tile hash and the mask make the cell a bookkeeping unit until T2 lands; until then C1 gives
  the same progress grain with no new identity. But C1 can never name a unit two phones share; C2 stands, per route.
- **S1 alone over S2:** S2's cost falls on the instance (objection 5) and its payoff is unmeasured (6); S1 with the
  use-ordered refresh (3) may be enough for a year. S2 stands as the measured second step, `osm` first.
- **T1 only:** fine until the server has listings; T2 stands as specified.
- **P1 and P2:** right; P2's trigger re-stated (19); the `If-Range` fix first (9).
- **H1 over H2:** H1 keeps no state and asks nothing of the user; H2 is the owner's "excellent plan". H2 stands with
  the consent surface (7) and the planet requirement (5); H1 is the default until the user allows an instance.
- **The setting:** Automatic by default is the owner's word against the survey's off and ADR 0030's rule; the ADR
  must own the supersession and the large-area rule (3). A setting with a researched default is right (ADR 0044).

## Pre-mortem

Ten years on, the phone holds two stores that never merged: PMTiles archives for boxes and packs, loose tiles in
cells for everything else, each with its own ledger, resume, verification and peer route, and every bug fixed twice.
The 4 by 4 cell, chosen before a measurement, is baked into `/cells/...` on every instance and peer, the ledger
format and the listing ids, so the 8 by 8 vector cell the first task wanted was never adopted. Listings exist only on
instances that self-host a planet, which in practice is one, so "any instance the user chose" (ADR 0059) is true on
paper and S2 is one instance's feature. The ledger and the index drifted twice and each time a release rebuilt one
from the other. The lines that should have caught it: "C2 is the only cell with an identity the phone, a peer and an
instance share, which every later step needs" (an identity fixed in a protocol before its size was measured),
"P3 ... rejected (every pack reader rewritten)" with no trigger that ever fires, and "Cost: the server hashes tiles
it serves and keeps listings per version" with no sentence on where the server gets tiles it has not served.

## Recommendation and confidence

Uphold W2, C2, S2 over S1, T1 then T2, P1 then P2 and H2, with the corrections above folded into the proposal before
the ADR: the move as a procedure with a copy fallback, an ownership pass and a user-visible state (1, 2); the ADR
supersedes ADR 0030's refresh rule and states the consistency and large-area rules, with the use-ordered default and
the owner shown both defaults (3); the per-cell version drives the pins (4); listings and keep-warm require an
instance with its own planet or a stated per-area limit, and the direct-mode country download is raised with the owner
(5); S2 built for `osm` after a measured changed-tile share, DEM on evidence (6); keep-warm behind a per-server consent
(7); one catalogue, the ledger as cell-level facts over the index, a binary file (8); the weak `ETag` fixed before P1
(9); cell size per route from the start (14). Build order: T249 merges; then the move; then cells with S1 and the
refresh setting together (3 and 17 are one design); then listings after the measurement; H2 last. Confidence: high
that the store must leave `cacheDir` and that an aligned cell with a ledger is the right unit; moderate on S2's and
H2's economics until the changed-tile share and the instance's cost are measured; low that a 100 MB daily limit is
the right default for anything larger than a corridor. Residual risks if all of the above is taken: the file count
(C3 waits on T249's numbers), Android stopping long background slices (ADR 0046's known ten minutes), and the two
stores staying two.
