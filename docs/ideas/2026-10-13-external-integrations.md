---
date: 2026-10-13
kind: feature
---

# Import, export and sync with outside services

In addition to the automatic sync between our own devices and server, move tracks and routes in and
out of other apps and services:

- A route-planning service where a subscription gives you a route track: import that track.
- A cloud drive (or similar) holding tracks and routes as a safer backup and sync.
- The OpenStreetMap project: upload a GPS trace in one press, to help fix its maps.
- A fitness-tracking service: upload recorded runs.

Each is probably its own integration. Today: GPX 1.1 import and export, the system share sheet and
file picker (`TrackControls`), and our own LAN and relay sync (T44, T51, T72). No service
integrations.

## Open

- Start with what needs no new dependency: share-sheet and file-picker open/save for GPX (the
  first three above can use a plain file in a drive folder or an open-with intent).
- Service APIs need an account, OAuth and a client registration (a secret that must not enter the
  repo), terms of use and privacy review. Track data can reveal where a user lives: nothing uploads
  without a visible, per-upload confirmation.
- Licences: any client library must be permissive (CLAUDE.md, Rules); calling a documented
  HTTP API ourselves needs none.
- These integrations must name the services they connect to, an exception to the
  no-other-product-names idea.

Question: which service first, if any? Each is its own integration with an account, a client registration whose secret stays out of the repo, terms of use and a per-upload confirmation, and each names an outside service. Recommendation: OpenStreetMap trace upload first (a documented open API, no client library, and it helps the map the app draws), as its own idea and proposal; the cloud drive is mostly covered today by GPX through the system file picker and share sheet, so it waits until you find that lacking; route and fitness services after.
Answer: OpenStreetMap trace upload first, as its own idea and proposal; cloud drive and other services wait, as recommended (owner, 2026-10-23).
