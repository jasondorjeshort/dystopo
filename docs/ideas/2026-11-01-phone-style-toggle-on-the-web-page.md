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

Plan: one pass in `tools/demo/` only (triage 2026-11-02). A "Phone style" toggle in the panel, shown in live
mode like the Print box: it calls `map.setStyle("/style.json?" + the page's current option query)`, which the
page's keys already match (`index.html`, `writeOptions`), re-adds the trips layers (`trips.js` already
re-adds on style load) and hides the page's own controls the phone style lacks (the POI markers, the slope
legend); off restores the page's style at the same view. Keep the toggle's state in the URL with the other
options. `tools/demo-check.py` gets one view with the toggle on and asserts the phone style's layer ids are
present. Out of scope: turning differences into parity tests (each one found is a follow-up in the
report). `StyleParityTest` compares the devtool renderer with the phone style, not the page; leave it.
