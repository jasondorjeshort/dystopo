---
date: 2026-11-01
kind: feature
---

# Federated instances: anyone's home site announces its packs, and the phone picks the nearest

`tools/site/install.ps1` makes running an instance a one-command affair, and the catalog is JSON
(`serverlib/.../PackCatalog.kt`, `docs/SERVER.md` › The catalog); the phone already has peer sources
(wave 12). If instances could announce their catalogs to each other (a list of peers in the config,
each polled for `/catalog`, the union served with the origin named), a hiking club's PC becomes the
mirror for its region and a phone that knows one instance finds the packs of all of them, picking the
nearest or the one holding the pack. Discovery without a central server: each instance's peers are
whoever its owner typed in.

## Open

- Trust: a peer's pack is a file from someone else's PC; the `.sha256` sidecars exist, a signature
  does not. ADR 0051's signing could extend to packs.
- Attribution and licence travel with the pack (`sources.json` is inside the archive's metadata today?).

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): the catalog is JSON and carries each file's SHA-256, attribution and licence from the
archive's metadata (`PackCatalog.kt`); the sidecar is a checksum from the same server, not a signature.
The phone's peer sources (T78) are other phones behind a share code while the Share screen is open, and
the phone holds one configured server; no instance polls another, and no catalog field names an origin.

Question: do you want your PC to fetch and serve other people's packs, and on what trust (typed-in peers
with hashes only, or packs signed by a publisher key)? Recommendation: not now. It is a catalog-format
change phones read, a trust scheme and a redistribution question (who is the publisher of a mirrored
pack under ODbL attribution), so a proposal when a second instance exists; until then the phone's
single server and the site's own packs are enough.
Answer:
