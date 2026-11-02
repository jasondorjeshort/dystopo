---
date: 2026-11-01
kind: feature
related: [2026-11-02-phone-settings-registry.md]
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

Plan: build ADR 0053 (`docs/design/decisions/0053-one-definition-of-controls-and-settings.md`) in its
three steps, each a task. (1) The map options: the registry in `shared` for `MapOptions.KEYS` (types,
choices, defaults; `MapOptions` reads them and keeps its parse), `devtool controls` writing the
committed `tools/demo/controls.json` [no-check: written by step 1] with its staleness test under
`./gradlew updateGoldens`, `injectLive` inlining it, the standalone build copying it, the page's
`data-control` attributes, and the check's parts for the settings rows and the web page; today's six
phone groups as the placeholder categories; no user-visible change. (2) The rest of the shared
surface and every placed item of both clients, the phone's `Modifier.control` helper and its
both-ways test, `GET /controls` on the debug port, one label per item and the help references. (3)
The owner's adjustments D1 to D6 as they come (ADR 0031). This file stays until step 1 lands.
