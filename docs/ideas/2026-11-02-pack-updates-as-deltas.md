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
