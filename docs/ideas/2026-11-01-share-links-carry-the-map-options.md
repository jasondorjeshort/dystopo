---
date: 2026-11-01
kind: feature
related: [2026-11-01-one-definition-of-controls-and-settings-across-platforms.md]
---

# A shared view link carries the map options, and both clients read it

The page's hash (`tools/demo/index.html`, MapLibre's `hash: true`) encodes zoom and centre; the map
options (`hillshade`, `slope`, `contourInterval`, `night`, the layer toggles, `drainage`) are query
parameters of `/style.json`, `/phone` and `/` (`serverlib/.../ServeMapOptions.kt`,
`docs/reference/live-server.md`). A link copied from the site today reproduces the place but not what
was shown on it. Put the options in the URL too, and let the phone's share sheet emit the same URL and
open one: a link then reproduces exactly what the sender saw, on either client. One URL format shared
by the web page and the app is the first concrete slice of the cross-platform definition idea.

## Open

- Hash or query for the options (a hash never reaches the server; the style parameters must).
- The phone has no URL handler today; which intent filter, and what happens offline for a place
  without a pack.

Recorded 2026-11-01 at the close of wave 25 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
