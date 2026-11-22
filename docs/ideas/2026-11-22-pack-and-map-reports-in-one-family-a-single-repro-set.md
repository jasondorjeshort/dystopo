---
date: 2026-11-22
kind: chore
related: [2026-11-15-a-first-launch-with-no-map-ignores-a-fix-after-four-seconds.md, 2026-11-07-search-and-routes-find-nothing-outside-a-pack.md, 2026-11-22-sky-from-here-in-colorado-says-no-elevation-data-with-a-pack-and-the-internet.md, 2026-11-22-the-map-is-nearly-empty-at-the-zooms-between-the-world-overview-and-the-packs.md]
---

# One emulator fixture for the pack-and-map reports: two overlapping maps, the network on and off

Status: **untriaged and not sanity-checked** (the coordinator's own, from a scan of all the ideas, 2026-11-22).

At least six reports describe one family: the phone says one thing about its maps and holds another (the no-map message
with a bulk download, the first launch with no map, search and routes outside a pack, and today's Sky-from-here, empty
map, Download-this-area and "no map after Back" reports). Each is reproduced from scratch by whoever takes it, and each
needs the same setup: two overlapping packs of different detail (a coarse wide one and a fine regional one), an area, the
network on and off, a place inside both and one inside one. Idea: one named fixture for the emulator (a small pair of
synthetic packs from public areas, built by `tilebuild` or the devtool, installed by a script) and one sweep part that
reads the phone's answer at each of the four places from `GET /firstrun`, `/startup`, `/areas/rows` and the views'
routes, so every report of the family is a row in one table, and a fix is checked against all of them at once.

**What exists already (to be checked):** `tools/android-a11y.py` screens (`map-outside`, T332) and the sweep's parts; no
shared fixture of overlapping packs. Public areas only (`areas.json`), per the privacy rule.
