---
date: 2026-11-01
kind: feature
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

2026-11-02 (triage): the web half is already built: `writeOptions()` in `tools/demo/index.html` writes every
non-default option into the URL's query, so a copied link reproduces what was shown. The phone has
`dystopo://map?lat&lon&z&bearing` (`app/DeepLink.kt`) and `dystopo://share`; neither carries map options, no
https filter exists, and nothing on the map screen emits a link.

Question: should the phone emit and open view links, and should a link it opens change your saved map
options or only that one view? Recommendation: yes, one pass: the option keys added to `dystopo://map`,
a "Share this view" action that emits that link and the site's https equivalent, an opened link applying
to the view only (your settings untouched), offline with no pack showing the place over whatever is
cached; https App Links (the phone opening the site's URLs) later as its own task, since that URL form is
read by other tools.
Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
