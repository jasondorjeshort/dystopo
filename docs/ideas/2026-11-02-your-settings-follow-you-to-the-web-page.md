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
