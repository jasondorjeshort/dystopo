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

Plan: a script under `tools/` that builds two small packs over a public area (Rainier from `areas.json`): a wide coarse
one (Less detail, elevation to a low zoom) and a fine one inside it (full detail), installs both on a given emulator serial
the way the app takes a pack, and switches the emulator's network on and off by its console. A sweep part beside it reads
`GET /firstrun`, `/startup`, `/areas/rows`, `/horizon` and the panorama route at four places (inside both, inside the coarse
one only, outside both, near the fine one's edge), network on and off, and writes one JSON table (eight rows) to `out/`.
`docs/reference/` gains the script's command line where the sweep parts are listed. Verify: the part runs on one AVD and the
table is complete; paste it in the report. Out of scope: fixing any row (the rows are the family's reports' evidence);
personal areas. Lands before the Sky-from-here, empty-map and Download-this-area passes, which then reproduce on it.
