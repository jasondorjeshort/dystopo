---
date: 2026-11-02
kind: feature
related: [2026-11-01-one-definition-of-controls-and-settings-across-platforms.md, 2026-11-01-share-links-carry-the-map-options.md]
---

# Your phone's settings follow you to the web page

ADR 0053 (wave 26) gives the web page and the phone one definition of the controls they share, with an
item's id being its synced key. The phone already syncs its settings between phones through the server
(`SettingsSync`, the share code). The web page on your own server could join that sync: enter the share
code once on the page (or open a link the phone offers), and the page shows the map in your units, your
map options and your text size, and a change on the page reaches the phone. The registry of ADR 0053 is
the list of what syncs; nothing else is new on the wire.

## Open

- The page keeps the share code in the browser only; what a shared computer means for it.
- Whether the page writes settings or only reads them (the owner's call on the flow).

Recorded 2026-11-02 at the close of wave 26 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): "nothing else is new on the wire" is false. The phone's settings travel as ops in the
sync log sealed under the share code through the relay (ChaCha20-Poly1305, HKDF); the page has no crypto,
WebCrypto has no ChaCha20-Poly1305, and the page's own sync (`trip-sync.js`) pulls routes with a bearer
token and never settings. ADR 0053's registry, which would list what syncs, is not built yet (step 1).

Question: read-only or two-way, and through the share code (the relay, crypto in the browser) or the server
token (the sync peer, no crypto)? Recommendation: wait for ADR 0053's steps 1 and 2, then one pass on the
token path, read-only: the page pulls the settings ops for the token's user and applies units, text size
and map options; writing from the page and the share-code path only if you want the page to work
without a token. A shared computer keeps nothing: the token is already in the browser only.
Answer:
