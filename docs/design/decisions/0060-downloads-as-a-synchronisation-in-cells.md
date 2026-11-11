# 0060. Downloads as a synchronisation in cells: the store out of the cache, cells with a ledger, listings, a consented helper

Date: 2026-11-09
Status: accepted (the technical decision after the critic; the owner answered 2026-11-11, below)

Record: proposal `../proposals/0060-downloads-as-a-synchronisation-in-cells.md` (T244, wave 31; the specification:
the procedure of the move, the cell sizes, the ledger, the stale tests, the routes, the settings table, the formats)
and `../proposals/0060-critic-review.md` (0 blocking, 9 serious, 11 minor; all accepted, two with change). Prior art:
the proposal's survey of 2026-11-09 (Steam's depot format through SteamKit and DepotDownloader, desync and casync,
OSTree; map apps excluded on the owner's word). Supersedes ADR 0030 in part: its "Refresh is explicit or on the
route" rule, its rejected alternative "re-fetching a stale area in the background as soon as its source moves on",
and its "one version throughout" lookup rule. What still stands from 0030: pinned tiles keep the versions they were
fetched at until refreshed, a version change keeps what pins hold, the pinned budget, the Offline button's size first.

## Context

The owner, 2026-11-07: downloads should be "a synchronization rather than a monolithic pull", by "some reasonable
download unit which I called a cell", surviving "a full phone reboot after connection is interrupted"; one
content-addressed model for the tile cache, packs and the phone-to-phone link ("Absolutely we should do this"); an
always-on helper for any instance the user chose or a peer (ADR 0059). The same day Android's Clear cache removed
62 GB of kept maps from the owner's phone: pinned tiles live in `cacheDir/online/tiles` (`online/OnlineService.kt:45`).
Read against the code at e184b663: an area download already resumes from a cursor through WorkManager (ADR 0046), but
its progress unit is a whole row at one zoom, so five runs without a whole row stop a country download with most of it
cached (`areas/ResumeCheck.kt:173-179`); staleness is per source version; packs resume by bytes with no worker and
send weak `ETag`s in `If-Range` against RFC 9110 §13.1.5. Verified by the critic: `installd` makes `cache` setgid with
the cache GID and counts it by GID; ext4 and f2fs refuse a rename across project IDs (`EXDEV`); the system deletes
`cacheDir` files under low storage, oldest first; `allowBackup="false"` does not stop device-to-device transfer on
some Android 12+ phones while `no-backup` is always excluded; OpenFreeMap builds a planet weekly and limits apps on its
public instance to 3,000 tiles or 50 MB per area.

## Decision

- **The store leaves the cache (W2).** The tile tree, its versions file and T249's snapshot move to
  `noBackupFilesDir/tiles` by a procedure on the index thread before the walk: rename; on failure a resumable copy and
  swap with the old tree served meanwhile; an ownership pass to the app's own GID with setgid cleared (directories at
  once, files lazily by the walk). `GET /debug/cache` gains `location`, `moveState`, `groupFixed`; the Storage screen
  says "Kept maps are in the cache on this phone; Android's Clear cache would remove them" until the move is done. A
  `dataExtractionRules` file excludes `packs/` from device transfer, so kept maps of both kinds stay off a migration.
  Contour and shade caches stay in `cacheDir`.
- **The cell (C2).** `(source, route, z, x >> k, y >> k)`: 4 by 4 for DEM, 8 by 8 for vector, no cell below z10 (a
  tile there is its own cell). The content version is a field, never the key. An area wants a mask per cell; areas OR
  them. The cell is the unit of progress, fetched-at, failures and staleness.
- **One catalogue.** `TileIndex` stays the record of what is present; a ledger `tiles/cells.bin` holds only cell facts
  (version, fetched-at, failures, listing id, tile hashes), binary, versioned, rebuilt on mismatch, one writer on the
  index thread, streamed, never loaded whole. Resume is wanted minus present at every start; for the one wave with
  both, the ledger wins over the cursor and `GET /areas/resume` reports disagreements.
- **Staleness (S1 first, S2 over it).** S1: the cell's source version moved, or for an unversioned source (direct mode)
  fetched-at past 30 days for vector and 365 for DEM. The per-cell version is the truth for `AreaPins.holds`,
  `Areas.older`, the card, the Storage row and `OnlineFetcher.dropUnheld` (per cell, when its mask is whole at the new
  version); the area record keeps a per-source summary (migrated, ADR 0033). **Consistency:** a tile is served at the
  newest version present for its cell; an area may hold two versions while it refreshes and its card says "N of M
  places current". S2: an instance's cell listing (per-tile SHA-256 cut to 16 bytes; the listing's hash is the cell's
  id); stale means the id differs, and only differing tiles are fetched. Listings for `osm` first and only after the
  changed-tile share between two planet runs is measured; DEM listings only on evidence. Vector listings require an
  instance with its own planet or a per-area limit announced in `/live/info`. The phone hashes tiles on `put` in the
  background so it can list to a peer.
- **Failures.** A cell failing in three runs is held back (drawn on the outline, "N places could not be fetched",
  Retry clears it, the next automatic refresh tries once); `MAX_FAILURES` counts runs that completed no cell.
- **Transfer.** T1 (per-tile requests, six in flight, four towards OpenFreeMap's public instance) first; T2
  (`GET /cells/<route>/<z>/<cx>/<cy>?have=<id>`, framed bodies of the differing tiles, the frame spanning the peer
  link's 4 MiB pieces) with listings.
- **Packs.** The weak-tag fix first (ignore `W/` tags, `Last-Modified` instead, else no `If-Range` only where a catalog
  SHA-256 checks the result; a test), then P1: the pack job joins the WorkManager chain. For a pack the refresh setting
  means a whole-pack Update until P2. Box areas stay region packs.
- **Refresh settings (ADR 0044).** "Keep offline maps up to date" (Automatically, Only when I tap Update) and
  "Background updates up to" a daily amount (50 MB, 100 MB, 500 MB, 2 GB, no limit); reaching it shows "Update waiting:
  daily limit reached (Settings › Storage)" with Update now; a tap never counts against it; a refresh larger than the
  limit is announced on the card with its size and both lifts, not started; order: the area open or navigated, saved
  routes and trip corridors, then oldest first.
- **The helper (H2, behind consent).** `POST /cells/changes` (wanted geometries, a change token per route; the cells
  changed since) and a keep-warm list held 30 days per ask; an instance announces `cellsKeepDays` and `cells: 1` in
  `/live/info`, a peer `cells: 1` in its catalog. The first catch-up asks "This server keeps the shapes of your
  offline maps for 30 days to prepare updates" with Allow and Not on this server, remembered per server; until allowed,
  H1 (a batch listing route). Tested against the sweep's own server, never a built-in address.
- **Build order (wave 32 on).** (1) T249 merges (wave 31). (2) The move alone. (3) Cells with S1 and the refresh
  settings together, after the owner's defaults, with the T151 and T156 real-phone checks (the app closed and the screen
  off; a service loss of ten minutes; a reboot with no network at boot) before and after, each reporting the bytes
  fetched twice. (4) The changed-tile measurement, then S2 for `osm`, T2, the batch route, hashing on `put`, the peer
  route. (5) H2 last, after the owner allows it. (6) The weak-tag fix then P1, in any wave.
- **Waiting on the owner, not decided here:** the refresh default (Automatically, on "needs to just work in the
  background", or Only when I tap Update, as ADR 0030 and the survey have it); H2's keep-warm list as a new data flow
  (ADR 0031, ADR 0059; H1 until allowed) and the consent's words; the daily limit's default (100 MB proposed, low
  confidence past a corridor) and today's direct-mode country downloads, which pass OpenFreeMap's public limit.
- **The owner's answers, 2026-11-11.** Refresh: **automatically in the background**. H2's keep-warm list: allowed,
  with its consent's words built as proposed and shown in the report (ADR 0031). The daily limit: 100 MB "sounds a
  decade out of date" (the owner keeps the limits at their maximum and downloads gigabytes routinely), so the default
  waits on fresh research (ADR 0044) into what today's users and apps download and on what plans. Country downloads in
  direct mode: no central server can be assumed, so a country is split into areas; the research first reads
  OpenFreeMap's published terms, and the split respects them (a split that only evades a per-area limit is not built).
  Over all of it, the owner's requirement: separate downloads work together for search and routing, and an area that
  overlaps one already kept never fetches the overlap again (the cells' content addressing, P1's store).
- **The research and the limit, 2026-11-11** (a read-only survey; about 25 GB a month per smartphone in North America
  and Western Europe in 2025, no app publishing a daily cap): the owner set the daily limit at **1 GB** by default,
  choices 250 MB, 500 MB, 1 GB, 2 GB, 5 GB and Unlimited, today's use shown against it. One limit for every network
  (a separate mobile-data limit was offered and not taken). OpenFreeMap's terms cap each offline download at 3,000
  tiles or 50 MB with at most 4 parallel requests and an identifying User-Agent, and say nothing of splitting: a split
  that only evades the cap is not built. The owner chose both, 2026-11-11: direct mode keeps to the terms now (each
  download at most 3,000 tiles or 50 MB, at most 4 parallel requests, the app's own User-Agent; no background refresh
  from the public instance), larger areas and refresh go through a server, and the owner asks OpenFreeMap for leave;
  their answer may lift the cap.
- **Deferred, with triggers.** P2 (a block-hash list as a `derived` entry): a measured repair case or peer serving of
  packs by block. C3 (a cell as one file): T249's numbers show the file count limits phones. P3 (packs as cells, one
  store on disk): a bug fixed twice, once per store, in two consecutive waves, or pack updates the larger share of
  traffic. W3 (two roots): the copy fallback runs on many phones.
- **Rejected:** W1 (the loss repeats, silently), C1 (runs with no shared identity), S3 (data by the clock), T3 (blobs
  by hash: a second index and garbage collection before dedupe is measured), H3 (a registered phone: an account in all
  but name), a per-tile download unit (the owner).

## Consequences

- Kept maps survive Clear cache and low-storage deletion, and the system's Settings stop calling them cache once the
  ownership pass is done; Clear cache in Settings frees little, and the Storage screen is where tiles are cleared.
- A download's progress, failures and staleness move in units of 16 or 64 tiles; a country no longer stops with most
  of it cached, and an interruption costs at most the cells in flight.
- An area may now be half old and half new for a while, which ADR 0030 refused; the card says so. Automatic refresh,
  if the owner chooses it, uses data the user did not ask for in that moment, bounded by a visible daily limit.
- Two stores remain on disk (archives and loose tiles) with one protocol; every fix to resume or verification may be
  needed twice until P3's trigger fires.
- Listings put cost on an instance: its own planet, a listing store per version, and for H2 a keep-warm job and a
  record of asked areas, disclosed and consented.
- Formats (ADR 0033): the store's location; the rules file; the ledger (derived); the area record's version summary
  (migrated); the server's listing, batch and change routes and announcements (additive; old phones ignore them, new
  phones fall back to S1 and T1); two synced setting keys; the per-server consent.
- Supersede when the store becomes one on disk (P3), when an instance becomes the only source of tiles, or if the owner
  rules automatic refresh out for good (the settings then go and ADR 0030's rule returns).

Predictions (the trigger scan reads these).
- Prediction, check when the move (build step 2) reaches the owner's phone: `GET /debug/cache` reads `moveState: done`
  by rename with no copy, and after Android's Clear cache the pinned bytes on the Storage screen are unchanged.
- Prediction, check at build step 3's real-phone checks: after each of the three interruptions the area completes with
  no tap, and the bytes fetched twice are under 1 percent of the area's bytes.
