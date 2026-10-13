# Critic review of ADR 0027: areas of interest, manifests, pinned corridors

Date: 2026-10-13
Status: review of an accepted ADR written after the build (T75, PR #81; ADR by T84)
Reviewer: critic (Opus 5.5), read-only; nothing was run on a device

## Part 1. Claims checked against the code

| # | Claim in the ADR | Verdict | Evidence |
|---|---|---|---|
| 1 | Area id is a hash of geometry and zooms, not the name | Confirmed | `AreaOfInterest.id`: `"a" + Sha256(canonical).take(16)`, canonical = shape, coordinates rounded to 1e-6, `minZoom-maxZoom`. |
| 2 | Tiles listed as rows `[y, x0, x1]` with estimated sizes | Confirmed | `TileSet.rows()`, `AreaManifest.tileItems`, `estimate()` (DEM 160 kB a tile, the Rainier pack's mountain average). |
| 3 | Corridor DEM from z6, one tile of margin | Confirmed | `AreaDownload.localManifest` (`OnlineTiles.DEM_MIN_ZOOM = 6`, `DEM_MAX_ZOOM = 15`); server `BuildRoutes.area` uses `LIVE_MIN_ZOOM`; margin 1 in `tileItems`. |
| 4 | Eviction asks a spatial predicate, any source, no key lists | Confirmed for eviction | `TileCache.keep` defaults to `Areas.isPinned`; `trim()` skips kept keys; `AreaPins.isPinned` parses `<source>/<route>[@v]/z/x/y`. |
| 5 | DEM, vector and contour tiles "are kept by one rule" | **Contradicted** | `TileCache.clearWhere` ignores `keep`; `OnlineFetcher.applyVersions` / `dropOld` call it whenever a fetched tile carries a new `X-Dystopo-Version`. See objection 1. |
| 6 | A cap below the pinned bytes leaves the cache over its cap | Confirmed, and worse | `trim()` iterates the whole LRU while `total > maxBytes`, so with pinned bytes above the cap every write evicts every unpinned entry, the new one included. |
| 7 | Ahead-on-route: 3 km, 1 km segments, 300 m, z12 to z15 | Confirmed | `AheadPrefetch.kt` constants and status JSON. |
| 8 | A box is a pack built through `/build`, verified by SHA-256 | Confirmed, with a hole | `BuildRoutes.area`: a finished pack of the same *name* is answered without comparing its bounds or zooms. See objection 4. |

## Part 2. Objections

1. **Blocking (a defect, not a reversal). Content-version changes delete pinned corridor tiles,
   and the lookup could not find them anyway.** T74 and T75 merged in the same wave with disjoint
   Owns lines; T75 added `keep` to eviction but the version path bypasses it. `OnlineFetcher`
   reads `X-Dystopo-Version` from every fetched `/dem` and `/osm` tile; a new value runs
   `dropOld`, which deletes every key of the old version through `clearWhere` (no `keep` check),
   including `contours-` and `shade-` keys derived from the DEM. The server's `demVersion` is a
   hash of its DEM pack files (`LiveTiles.packsChanged`), so **any** `/build` (including the box
   branch of `POST /area`, from any phone) changes it; `osm` follows the OpenFreeMap planet build
   (SERVER.md, `GET /version`). Sequence that loses data: download a corridor for next week's
   trip, browse online once after the server builds a pack or picks up a new planet, and the
   corridor's DEM, contours or vector tiles are gone; `GET /areas` coverage drops but nothing tells
   the user. Even with the delete skipped, the cache key carries the current version
   (`OnlineFetcher` line 137), so offline lookups would miss the old-version tiles. No test
   covers pins against a version change (`PinnedTrimTest` covers trim only).
   *Resolve (task):* a pinned area records the content versions it was fetched at; `dropOld` skips
   keys a pin covers; tile lookup falls back to the pinned version when the current one misses;
   when online and a pinned area's version is stale, re-fetch it in the background with a visible
   notice (ADR 0023). Tests in `PinnedTrimTest` and `OnlineFetcherTest`. Amend the ADR's
   Consequences until fixed ("kept until the source's content version changes").

2. **Serious. A trip corridor is hike-sized for every mode, and nothing shows its size first.**
   `AreaRoutes.tripArea` uses z8 to z15 and 500 m for every kept route; the trip's Offline button
   (`TripControls.kt`, `Trips.downloadOffline`) starts the download with no estimate shown and no
   check against `onlineCacheMb` (default 256 MB, `AppSettings`). ADR 0028 sends long drives
   through exactly this path. Measured in the T77 log: a 148 km drive at the default zooms was
   2,101 tiles and 134 MB. My estimate with the ADR's own cover rule and per-tile estimates, on
   straight lines (a lower bound): a 570 km drive about 5,500 DEM and 2,500 vector tiles, about
   930 MB estimated; a 3,900 km coast-to-coast drive about 51,000 tiles, about 6 GB (DEM estimate
   is the mountain upper end; flat ground compresses better). With pinned bytes above the cap,
   claim 6 above applies: the online cache keeps nothing unpinned, so ordinary browsing
   re-fetches every tile.
   *Resolve (task):* mode-dependent corridor defaults (drive: DEM to z12 or z13 and 300 m, which is
   what the T77 implementer chose by hand and got 35 MB); show the manifest's `bytes` and the cache
   headroom before starting, and ask when pinned would exceed the cap. This is the T74 budget
   follow-up the ADR defers, but the confirmation does not need the budget work.

3. **Minor. The manifest is not forward compatible within version 1.** `ManifestItem.parse` throws
   on an unknown `kind`, which fails the whole manifest; SERVER.md speaks of a "newer major
   version" but `version` is one integer. T78 will have phones of different releases reading each
   other's manifests, and ADR 0028 already names the next kind (graph tiles from a server or peer,
   PLANNING › Routing "Not yet"). *Resolve:* before T78 ships, readers skip unknown kinds and list
   them in `notes`; bump `version` only when an existing field changes meaning. One test.

4. **Minor. The box path identifies packs by name, not by area.** `BuildRoutes.area` answers any
   request whose `name` matches an existing DEM pack file with that pack, whatever bbox and zooms
   were asked; the manifest then carries the new area's `id` and `area` beside archives of a
   different box. It contradicts the identity rule in the ADR's first bullet. *Resolve:* compare the
   pack's bounds and zooms with the area (`FileItem.bounds` is there) and build under
   `area-<id>` on a mismatch, or answer 409.

Hidden coupling, beyond objections 1 and 2: pinning by z/x/y across every source means a source
switch (server to direct, or a new server) leaves the old source's tiles pinned and unreachable
until Unpin; acceptable, but `GET /areas` should report pinned bytes per source.

## Recommendation

The decision is right: a corridor as tiles in the cache, pinned by a spatial predicate, is simpler
than per-key lists or a second store, and the manifest shape (packs with SHA-256, tile rows,
on-device products) fits all three callers. Rejected options 1 to 5 hold up against the code. Two
things must change now that the code exists: objection 1 is a data-loss defect that defeats design
goal 4 in ordinary use and should be the first task of the next wave, with the ADR's "kept by one
rule" line amended until it lands; objection 2 should ship with it because ADR 0028 routes long
drives through the same button. Objections 3 and 4 are cheap and belong before T78. Confidence:
high on 1 (read in `TileCache`, `OnlineFetcher`, `LiveTiles`; not reproduced on a device), high on
the facts of 2, moderate on its size estimates (straight lines, mountain DEM average).
