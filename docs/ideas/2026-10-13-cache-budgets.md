---
date: 2026-10-13
kind: feature
status: done
pipeline: T112 (T74 partial before)
related: [2026-10-13-auto-download-when-zoomed-in.md]
---

# Cache system: one budget, per-kind budgets, eviction of the oldest or least used

Wanted: a maximum total cache size on Android with separate maximums per part (map vector tiles,
topo/elevation, rendered tiles, and the rest), evicting the oldest-used or least-used data when a
limit is near or exceeded. Open whether storage is a database, the file system or something else.

Today (T74, ADR 0025 principles 2 and 3): every cache names its inputs and is bounded; caps for
online tiles (`onlineCacheMb`) and derived tiles (`contourCacheMb`, covering contours, cliff
rewrites and shade); least-recently-used eviction (`BoundedCache`, tile caches); a Storage screen
with bytes per store and a Clear per store. Recorded gaps: no single total budget; pinned bytes
(T75) have no separate budget; the shade and contour cache cap is only enforced while the app
runs; eviction is by recency, not frequency.

## Brainstorm (2026-10-13)

Three kinds the owner wants budgeted separately:

1. **Map:** vector tiles.
2. **Topo:** elevation sources, and what is derived from them.
3. **Rendered raster tiles:** easily invalidated (any style or option change) and arguably
   single-session, so they may get a smaller share. But they are essential for power saving: on a
   hike the phone should not regenerate them, and on the first open of the day at home it should
   not rebuild everything around the user. So they are kept across sessions even if small.

Principles floated:

- Offline, an evicted downloaded source tile cannot come back, but a derived tile can be rebuilt
  (at a battery cost). Evict derived data before source data, and weigh rebuild cost against
  battery; never evict what is in the current view or ahead along a navigated route.
- A total budget with per-kind floors and ceilings; evict oldest-used from the kind furthest above
  its floor. Pinned areas and packs have their own budget and are never evicted.
- Storage: tile bytes as files, plus a small separate index database for last-used time, size,
  kind and hit count (cache only, deletable without touching user data, last-used writes batched).
  Accounting in a database is an option to measure, not decided.
- Rebuildable derived data may live where the OS can clear it; anything that matters offline must
  live in app storage the OS does not clear.
- How big: let the user set the total as a fixed size with presets, small for a casual user and
  large for a dedicated hiker (128 GB or more on a 256 GB phone is realistic). Possibly also offer
  "a percentage of free space"; the owner worries a percentage races with other apps filling the
  disk, so a percentage would be a way to pick a fixed number at setup, not a live limit (not
  decided). The default is small.
- Measure first: replay a day of viewing against a given cap and report hit rate per kind
  (devtool command or the existing `/storage` route), to compare least-recently-used against
  frequency-aware eviction.

## Open

- Whether "total plus per-kind" is a layer over today's caps (a total that scales the kinds down
  proportionally, a floor per kind) or a replacement.
- LRU versus least-frequently-used, or a mix (a size-weighted score) for tiles that are costly to
  rebuild (contours) versus cheap ones.
- File system versus database: tiles as files is simple and cheap to evict; an index database
  helps with last-used time and counts without touching file times. Measure before choosing; an
  ADR if the on-disk layout changes.
- Pinned areas and packs are never evicted; they need their own budget, shown in the Storage
  screen.

## Done (T112, 2026-10-16)

Built as a layer over the caps: ADR 0034 (one total with presets, floors per kind, derived data first,
least recently used within a kind, never the view or the route ahead; frequency scoring and an index
database rejected on `devtool cache-replay` numbers, to revisit with a phone request log).
