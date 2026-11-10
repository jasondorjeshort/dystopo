---
date: 2026-11-10
kind: chore
---

# ADR 0045's deferred item fired and was built: the tile cache's index on disk (T249)

The ADR 0012 scan of 2026-11-10. ADR 0045 (`docs/design/decisions/0045-area-tiles-as-rows.md`) defers "an index on
disk or a compact index for the tile cache, revived when a phone's area downloads pass `AreaLimit.maxTiles` in use or
a heap reading shows the index near a quarter of the heap". The owner's phone froze on a 40 GB cache (T242, wave 30)
and T249 (wave 31) built the on-disk snapshot `online/tiles.index` with its validation and discard rules; the ADR
still reads the item as deferred.

Plan: the coordinator (architect) amends ADR 0045: one dated line under the deferred item saying its trigger fired
(T242's freeze) and T249 built the snapshot, pointing at `docs/reference/android-app.md`'s tile cache paragraph and
naming ADR 0060's ledger as the place a later incremental index is decided; the decisions README's status line if it
changes. No code.
