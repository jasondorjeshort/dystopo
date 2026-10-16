# 0034. One cache budget over the phone's caches: floors, derived first, least recently used within a kind

Date: 2026-10-16
Status: accepted (T112; the eviction rule chosen on `devtool cache-replay` numbers)

Record: task T112 (`docs/TASKS.md`, wave 15), the idea `docs/ideas/2026-10-13-cache-budgets.md` (the
owner's brainstorm), `docs/PLANNING.md` › Design goals › Caches (the measurements). Amends ADR 0025
principle 3 ("always bounded, with a cap, LRU, and one settings screen for the budgets"): the caps
become ceilings under one total, and eviction across caches follows the order below; LRU stays the
rule within a kind. Principles 1, 2 and 4 to 6 of ADR 0025 stand.

## Context

Before T112 the phone had a cap per cache (`onlineCacheMb` and `contourCacheMb`, 256 MB each, set on
the Storage screen) plus the direct sources' own 512 MB trim, each evicting by least recently used, and
no total. Contour and shade tiles made from downloaded DEM tiles sat in the online cache and counted
as online tiles. The owner asked (the idea's Brainstorm) for one total with per-kind floors, eviction
of the oldest-used from the kind furthest above its floor, derived data before source data (offline a
derived tile can be made again, a downloaded one cannot), never what is in view or ahead on a
navigated route, presets that are small by default and large enough for a dedicated hiker (128 GB or
more on a 256 GB phone), and a percentage of free space only as a way to pick a number once.

Measured with `devtool cache-replay` over the standalone server's request log of 23 viewing sessions
(10,540 tile answers: scripted tours repeated, phone-style tours, and six seeded random walks around
Rainier, the Alps and other public places; 242 MB of distinct downloaded tiles, 7.1 MB of contours).
Hit rates are per request; "bytes" is the byte hit rate of all kinds.

| log | total | rule | online | derived | bytes |
|---|---|---|---|---|---|
| random walks (2,734) | 32 MB | one LRU, no floors | 8.4% | 1.6% | 9.3% |
| | 32 MB | floors, derived first, LRU | 8.2% | 33.1% | 9.8% |
| | 32 MB | the same, size-weighted frequency (GDSF) | 15.3% | 33.1% | 10.4% |
| | 32 MB | GDSF forgetting its counts at each restart | 16.3% | 33.1% | 11.4% |
| | 128 MB | one LRU, no floors | 31.1% | 27.1% | 30.7% |
| | 128 MB | floors, derived first, LRU | 31.2% | 33.1% | 31.1% |
| | 128 MB | the same, GDSF | 35.7% | 33.1% | 34.6% |
| | 128 MB | GDSF forgetting its counts | 33.9% | 33.1% | 32.4% |
| all 23 sessions | 64 MB | floors, derived first, LRU | 33.1% | 75.1% | 34.5% |
| | 64 MB | GDSF | 49.3% | 75.1% | 43.0% |
| | 64 MB | GDSF forgetting its counts | 41.6% | 75.1% | 33.2% |

The floors with derived first keep contours (33% instead of 1.6% of requests on the random walks, at
most the 33.1% a cache of everything gets) for a fraction of a point of online hits. GDSF gains most
on the scripted tours replayed in the same order, LRU's worst case (a loop slightly larger than the
cache), and little in bytes on random walks (0.6 to 3.5 points); without hit counts kept across app
restarts it can do worse than LRU (33.2% against 34.5% of bytes at 64 MB).

## Decision

1. **Kinds** (`androidApp/.../cachebudget/BudgetPlan.kt`, `CacheKind`): Online tiles (the unpinned
   DEM, map and glyph tiles in `cache/online/tiles/`), Contours and shading (every contour, shade and
   cliff tile the phone made: `cache/contours/` and the online cache's `contours-` and `shade-`
   entries), Source data (`cache/sources/`, the phone's own source downloads). Pinned offline areas
   (`pinnedCacheMb`, ADR 0030) and packs are outside. The Storage screen's rows are these kinds.
2. **The total** (`AppSettings.cacheBudgetMb`): presets Small 1 GB (the default), Medium 8 GB, Large
   128 GB; a custom size from 64 MB to 1 TB; or 10, 25 or 50% of the free space plus what the caches
   hold, stored as a fixed number (never a live share). `POST /storage?budgetMb=|preset=|percentFree=`.
   An install without one stored gets the larger of 1 GB and its two caps together.
3. **Floors**: 25% of the total for online tiles, 15% for contours and shading, 5% for source data.
   The budget never evicts a kind below its floor (give or take the last tile).
4. **Order** (`BudgetPlan.next`, `BudgetEngine`): when the kinds together pass the total, evict until
   1% of it (at least 4 MB) is free: contours and shading first, down to their floor, then from the
   source kind furthest above its floor; within a kind the least recently used first, across the two
   caches that hold derived tiles by their touch times; never a pinned tile, never one in the view
   (the visible box grown by half on every side), never one within 300 m of the next 3 km of a
   navigated route (`CacheGuard`).
5. **When**: a cheap check after every tile write (`TileCache.onWrite`), a check every 30 s (which
   also walks `cache/sources/`), and at once when the total is set; after a run that could not fit
   (everything left is in view, ahead or pinned) writes wait 30 s.
6. **Caps stay as ceilings.** `onlineCacheMb` and `contourCacheMb` remain each cache's own synchronous
   LRU cap; setting the total sets both to it (after evicting through the budget, so the view stays).
   `POST /settings` can still lower one. The public API of `TileCache` (constructor, `resize`, the
   directory layout) is unchanged; it gains `onWrite`, `evictOldest`, `oldestTouch` and `bytesWhere`.
7. **Files, no index database.** Least recently used survives a restart in the files' times (a hit
   touches its file at most once a minute), so no index of the cache is kept.

## Consequences

- One number for the user instead of two caps; the default footprint stays about what it was (two
  256 MB caps plus a 512 MB sources trim before; 1 GB over all three now).
- Contour and shade tiles are no longer the first to vanish when map tiles stream in, which saves the
  rebuilds the owner worried about on a hike; their Clear now also takes those in the online cache.
- A run walks a cache's index under its lock (the derived bytes of the online cache, the derived-first
  pass): at most once per 1% of the total written, measured at 23 to 59 ms on the emulator at 64 MB.
- The source data kind is still also trimmed by the direct sources' own 512 MB cap, which does not
  grow with the total (`online/DirectSources.kt`, another task's file).

Rejected:
- **A size-weighted frequency score (GDSF) within a kind**: more request hits, few more bytes on
  random viewing, and it needs hit counts that survive restarts, that is an index database beside the
  tile files. Revisit when a request log from a real phone (deferred to wave 16) shows the gain on real
  use; that is the trigger for the index too.
- **One LRU over every cache with no floors**: map tiles streaming in evict every contour tile.
- **A share of the free space as a live limit**: other apps filling the disk would shrink the cache
  under the user (the owner).
- **A budget chip per cache on the Storage screen**: two knobs that had to add up; the per-cache caps
  stay settable for tests and special cases through `POST /settings`.
