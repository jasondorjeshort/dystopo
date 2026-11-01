---
date: 2026-11-01
kind: feature
related: [2026-11-01-compare-page-web-and-phone-side-by-side.md]
---

# A toggle on the web page that swaps in the phone's style

`/phone` already serves the phone's style over the live routes (`serverlib/.../LiveTiles.kt`,
`docs/reference/live-server.md`), and `devtool/.../StyleParityTest.kt` guards the shared parts. The
page at `/` draws its own style. A toggle on `/` that swaps the page's style for the phone's (same
view, same options) makes every difference between the two renderings one click away, and each
difference found either becomes a parity test or a documented departure.

## Open

- The page's layers (its POI markers, the slope legend) are hand-written in `index.html`; the toggle
  may need to disable the page's own controls that the phone style lacks.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
