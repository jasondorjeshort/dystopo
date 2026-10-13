# 0027. Areas of interest: a versioned manifest, packs for boxes, pinned tile covers for corridors

Date: 2026-10-12
Status: accepted

Record: the proposal paragraph "Areas of interest as built (T75, proposal for the ADR T84 will
write)" in `docs/PLANNING.md` › Design goals; the format field by field in `docs/SERVER.md` › Areas
of interest (T75); the T75 log entry (2026-10-12). No separate critic pass was run: the proposal was
written, built and checked by an implementer and a verifier on separate emulators before this ADR
records it.

## Context

Design goal 4 (ADR 0025): the user says where they are going and everything for that area or route
is downloaded ahead of time and kept. The format had to serve three callers: the phone downloading
from a server, the phone building the same list itself with no server (direct sources, T80), and a
peer phone publishing what it holds (T78). It also had to survive long corridors (a drive across
packs is thousands of tiles) and a server that renders tiles on demand and so cannot know their
sizes or hashes in advance.

## Decision

- **An area** is a box or a route corridor (a line and a buffer in metres) with a zoom range
  (`AreaOfInterest`, `shared/.../areas/`). Its id is a hash of the geometry and the zooms, not of the
  name, so the same area asked twice or by two phones is one area.
- **It expands to a versioned manifest** (`AreaManifest`) whose items are: `pack` archives with a
  SHA-256 each; `tiles` lists per route and zoom as rows `[y, x0, x1]`, with estimated sizes; bundled
  style assets; and products the phone makes itself (contours, trail graph, search index), named so
  the phone knows to make them. The field-by-field format is `docs/SERVER.md` › Areas of interest.
- **A box is a region pack** built through `/build`: `POST /area` answers 202 while it builds, then
  the archives with hashes, so the download is verified like a catalog download. The installed pack
  is recorded as a pinned area and stays until removed.
- **A corridor is the DEM and vector tiles within the buffer** (DEM from z6, because contour tiles
  sample coarser zooms than they show, and one tile of margin), fetched through the online fetcher
  into the tile cache and **pinned there**: the cache's eviction asks a spatial predicate (`AreaPins`)
  whether a key's z/x/y lies in a pinned area's cover, so DEM, vector and contour tiles of any source
  and any settings are kept by one rule, with no key lists. **Known gap (critic review
  `../proposals/0027-critic-review.md`, 2026-10-13):** a source content-version change drops every
  old-version tile through `TileCache.clearWhere` without the pin check, and lookups use the current
  version, so a pinned corridor does not survive a server rebuild today; T95 fixes it by keying a
  pinned area's tiles to the version in its manifest until the area is refreshed.
- **Ahead on the route:** during navigation the next 3 km of the line (1 km segments, 300 m buffer,
  z12 to z15) are fetched while online and pinned until navigation stops.

### Rejected

1. A corridor as a pack of only its tiles: the resolver ranks packs by bounding box, so such a pack
   would shadow online tiles beside the corridor inside its box (blank vector tiles, coarse terrain
   even online) until the resolver checks per-tile presence.
2. A corridor as the pack of its bounding box: right for a short loop, wrong for a long drive whose
   box is half a continent.
3. Explicit key lists per area: they depend on the source URL and every derived setting and grow
   with every contour tile.
4. Per-tile sizes and hashes in the manifest: too large for long corridors and unknown to a server
   that renders on demand; tiles are checked by type, packs by SHA-256.
5. A separate pinned store outside the cache cap: cleaner accounting, but a second resolver lookup
   or a cache rewrite. Today pinned tiles count against `onlineCacheMb` and are skipped by eviction,
   so a cap below the pinned areas leaves the cache over its cap, visible in `GET /areas`.

## Consequences

- A peer phone publishes the same manifest (T78); fetching an area from a peer is the same download
  code with another base URL.
- Pinned bytes are not budgeted separately (T74 follow-up); a corridor carries no trail graph or
  search index (those come from vector packs; the manifest says so).
- Packs are never unpinned into the LRU (moving their tiles would cost more than it saves).
- Supersede when pinned bytes get their own budget or the resolver learns per-tile pack presence (which
  would reopen option 1).
