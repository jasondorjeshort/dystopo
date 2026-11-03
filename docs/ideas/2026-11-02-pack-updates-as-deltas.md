---
date: 2026-11-02
kind: feature
related: [2026-11-01-federated-instances-announce-their-packs.md]
---

# A pack update downloads only the tiles that changed

T202 (wave 26) made the server keep every pack's routing graph current; the packs themselves still update
whole: a refreshed OpenStreetMap extract means a new 100 MB pair for every phone that holds it, when a
month of mapping touched a few hundred tiles. PMTiles is addressed by tile id and the server hashes every
file (T58's sidecars); a per-tile hash list beside the pack (a derived file, as the graph is in ADR 0049)
lets the phone fetch only the tiles whose hash moved and rewrite its archive in place, with the graph
rebuilt from the changed z11 tiles only (ADR 0049's C is the partitioned builder). The catalog's `derived`
list is the announcement; old phones ignore it and download whole, as with the graph.

## Open

- Rewriting a PMTiles archive in place on the phone (the directory and the run-length layout) against
  writing a new file beside it and swapping.
- The server's extract refresh cadence (the site's packs are built by the owner today).

Recorded 2026-11-02 at the close of wave 26 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): the hashes are whole-file (`.sha256` sidecars, `contentHash` over them), no per-tile
hash exists; `derived` is additive and old phones ignore it, as the idea says. An in-place rewrite is not
realistic: `PmtilesWriter` deduplicates, run-length-merges and clusters data in tile-id order, so one
changed tile shifts every later offset; the update is a new file written from old plus changed tiles and
swapped. The graph is one whole file; ADR 0049's partitioned builder (C) is unbuilt and conditional.

Question: wanted, given that the packs are rebuilt by you by hand today and no refresh cadence exists?
Recommendation: not now. It is a new derived file format both sides read, a catalog field, an update
protocol and a graph rebuild that depends on C: a proposal, worth writing when the site rebuilds its
packs on a schedule and the phones' update traffic is measured.
Answer:
