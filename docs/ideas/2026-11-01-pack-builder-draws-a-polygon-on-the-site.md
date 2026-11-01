---
date: 2026-11-01
kind: feature
---

# The site's pack builder takes a drawn polygon and hands the phone a download link

"Build a pack here" (`tools/demo/index.html`, `serverlib/.../BuildRoutes.kt`) takes the current view
as a box. On a desk one would rather draw: a polygon tool on the map, the size shown before the build
(the phone's size-first card has the arithmetic), then a link or QR code the phone opens to pull the
pack from `/out/`. For anyone with a desk this replaces most of the in-app country picker.

## Open

- `POST /build` takes a bbox; a polygon build is the server-side half (proposal 0041 polygon areas
  covers the phone's side).
- The link's form (the share-links idea) and whether the phone registers it as an offline area.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
