---
date: 2026-11-22
kind: feature
related: [2026-11-22-a-downloaded-map-must-never-make-the-view-worse-than-online.md, 2026-11-22-sky-from-here-in-colorado-says-no-elevation-data-with-a-pack-and-the-internet.md]
---

# Turn an offline map off without deleting it

From the owner (2026-11-22: "Let's add a todo to disable packs without deleting them"). The Offline maps rows offer Show
on map, Allow clearing, Download again, Rename and Remove (`packs/PacksScreen.kt`, rows in `areas/AreasScreen.kt`,
`docs/reference/android-app.md` › Offline maps); none switches a map off, and Remove deletes a pack that took hours
to download (the owner's US map is 30.4 GB). A map that is off stays on the phone and counts in the budget, and nothing
reads it: not the tile server, the terrain reads of the Horizon and Panorama views, search coverage (`MapCoverage`), the
routing graph, nor the first-run card's "holds a map" rule (T332).

**Why now.** The owner's reports of 2026-11-22 each want a comparison of a view with and without a pack (the Sky-from-here
bug, the empty map between the overview and the packs, "a download must never make the view worse"). Today the only way
is to delete. It also lets the owner keep a large coarse map and use a fine regional one alone.

**What more we would want to see (design, before building).**
- What "off" means for the pack that holds the routing graph or the elevation: one switch per map, or per layer.
- Where it shows: a toggle on the row ("Use on this phone"), the row's state in words ("Off: not used"), and whether the
  Offline maps chip or the map's chip says when a map in view is off.
- Persistence: a field on the pack's record (a data format change, ADR 0033: listed in the report, existing records read
  as on).
- Debug route and test: the route lists each map's state and sets it (agent-testable); the unit test is the registry
  filter; the sweep step compares a view with a map on and off.

Plan: a per-map on/off in the registry that every reader of packs and areas honours, a row toggle with its words, a
debug route, and the first-run and coverage rules reading only maps that are on; settled in alpha under ADR 0031 (the
owner adjusts the wording afterwards). Built after T332 merges, which owns the first-run rule's files.
