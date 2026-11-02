---
date: 2026-11-01
kind: feature
---

# One definition of the controls and settings, read by the web page, the Android app and a later iOS app

The owner, 2026-11-01, planning wave 25 (the home site): the web page and the phone should have "a
consistent design between them that put things in generally the same categories and places. Be that
buttons on screen, settings categories, etc."; not a faithful mirror, and the maintenance of keeping
two (later three) clients in step is the real cost; something like OpenAPI docs that both stay in
sync with. Today the web panel (`tools/demo/index.html`: layers, units, text size, map options,
pack building, print) and the phone's Settings were never designed together; the map options are
the one shared surface (`/style.json`'s query parameters, guarded by `StyleParityTest`).

Two layers suggest themselves: behaviour (each option's id, type, values and default; OpenAPI
fits, since the options are already query parameters with enums and a 400 on bad values), and
placement (a small manifest: ordered categories, each item's category and order, label and help-text
keys, the on-screen zone it lives in, the platforms that have it), with a check in `./gradlew check`
that fails when a client shows a control the manifest does not place. Layout and flows stay each
platform's own.

Proposal: `docs/design/proposals/0053-one-definition-of-controls-and-settings.md`, with the inventory for the owner's categories in `docs/design/proposals/0053-controls-inventory.md` (T206).

## Open

- The categories themselves: nothing settles them. First an inventory of every control on both
  clients and the benchmark survey of how the established topo apps group theirs (ADR 0039), then a
  side-by-side table for the owner to edit; the manifest follows the table.
- A format other tools read is a major choice: proposal, critic, ADR before building.
- Whether strings (labels, help) belong in the same definition or stay per platform.
