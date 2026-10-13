# 0025. Design goals: one data path online and offline, bounded caches, areas, tiled routing, peers

Date: 2026-10-10 (no Wi-Fi gates added 2026-10-11)
Status: accepted (owner's goals; principles bind every task that touches tiles, caches, packs,
routing or sharing)

Record: `docs/PLANNING.md` › "Design goals (set 2026-10-10): one data path online and offline,
precaching, long routes, sharing on a trip" (goals, what already fits, principles, gaps); tasks T73
to T78 on the board.

## Context

By wave 11 the phone read packs, a server and its own caches through several paths, keyed in places
to "the active pack". The owner set longer-term goals so near-term work would stay friendly to them:
(1) move between online and offline without noticing; (2) keep what is built, keyed by every setting
that affects it and disposable; (3) disk for caches is a setting; (4) say where you are going and
download everything for it; (5) routes longer than one pack (rim to rim, a 40 km ride, New York to Los
Angeles); (6) on a trip one phone with the sources serves the others.

## Decision

Six principles, binding from 2026-10-10 (the full text, with what already fitted, is the PLANNING
section):

1. **Everything on the phone is a cache in front of a source; a pack is a pre-filled, pinned,
   immutable cache segment.** One resolver (pack union, disk cache, source); nothing special-cases
   the active pack, which survives only as a tie-breaker and the camera's home. A source may be a
   server or a peer, so the fetcher takes a list of sources.
2. **Every cache key names its inputs and its version**: settings vector, source identity, format
   version. Online tiles carry the source's content version; style assets are versioned; content
   hashes, not URLs, identify pack files.
3. **Derived caches are never required for correctness and are always bounded**, with a cap, LRU,
   and one settings screen for the budgets. No Wi-Fi gates (ADR 0023). Generated tiles are shared
   between phones only when the full derived key matches, and only where measured to beat
   regeneration.
4. **An area of interest is a first-class object**: a box or a route corridor with a zoom range
   becomes a manifest, downloaded, verified and pinned (ADR 0027).
5. **Routing is tiled, not per pack**: graph tiles at a fixed zoom behind a `Router` interface;
   long distance through a hierarchy or a remote router, with the result saved as geometry plus a
   corridor download (ADR 0028).
6. **Transports are pluggable and discovery is separate from transfer**: discover a peer (NSD now,
   Bluetooth LE later for discovery and pairing only), verify by content hash, fetch over HTTP with
   the share link's token and encryption.

## Consequences

- Built against these: T74 (caches and budgets), T75 (areas), T76 (tiled graph), T77 (long routes),
  T73 (drive mode), T78 (peer sources).
- Code still keyed to the primary pack (contour warm-up, for example) is a defect to remove, not a
  pattern to copy.
- A task that adds a cache, a download or a transport states in its PR which principle it follows.
- Supersede when the owner changes a goal; amend by a new ADR per principle that changes.
