---
date: 2026-11-14
kind: feature
related: [2026-11-07-downloads-as-a-synchronisation-in-cells.md]
---

# Stored tiles checked against their hash, and a damaged one fetched again

T279 (merged in bb4ff4a8) fixed one shape of damage: a tile left as a zero-byte file by a hard stop was served as a
hit for ever, and is now dropped and fetched again. Any other shape (a torn write of the right length, a truncated
body, a bad block) still reads as a good tile. ADR 0060 already decides the hashes: the phone hashes a tile on
`put`, the ledger `tiles/cells.bin` keeps the tile hashes of each cell, and an instance publishes a cell listing of
per-tile SHA-256 cut to 16 bytes (S2; step 4 of its build order). There the hash says a tile is stale; this idea
uses the same hash to say it is damaged: a tile whose bytes no longer match the ledger is dropped and fetched
again, as an empty one is today (`androidApp/.../app/online/TileCache.kt`). The owner, 2026-11-14: "Yes, but how do
we get that on the server end?"

The server end (the coordinator's answer): nothing beyond ADR 0060's S2. For damage on the phone no server is
needed, since the phone's own hash at `put` is the reference; that covers direct mode too, where no instance
vouches for anything. The instance's listing adds the second check, that what arrived is what the instance holds,
and it is what would let a phone trust a tile handed over by another phone (the tile matches a listing the receiver
has from an instance). The instance computes a listing by reading the cell's tiles out of its pack and hashing them
(`shared/.../packs/Sha256.kt`), for `osm` first as the ADR says; DEM listings stay "only on evidence".

Recommendation: no task of its own. When step 4 of ADR 0060 builds hashing on `put`, its Done-when gains the check:
a debug route flips a byte in a stored tile, and the tile is dropped and fetched again; done by a low-priority pass
over the kept areas that reports "N tiles repaired", unless a hash on every read measures as cheap.

## Open

- The cost of a hash on every read against a pass in the background; measure before choosing.
- A tile found damaged while offline: drawn from a wider zoom until a network returns, and counted on the area's
  card.

Plan: no pass of its own. The brief of ADR 0060's step 4 (hashing on `put`, the ledger) takes this file's check
into its Done-when: a debug route flips a byte in a stored tile, and the tile is dropped, counted and fetched
again, by a low-priority pass over the kept areas that reports "N tiles repaired", unless that task measures a
hash on every read as cheap; a tile found damaged offline is drawn from a wider zoom and counted on the area's
card. This file is deleted on that step's branch.
