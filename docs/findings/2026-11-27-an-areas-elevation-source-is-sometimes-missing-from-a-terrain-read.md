---
date: 2026-11-27
status: open
area: android / terrain read, downloaded areas, the lookup (ADR 0066)
---

# An area's elevation source is sometimes missing from a terrain read

## Question

T378's sweep step `peaks-tiles` reads the Horizon view at Paradise with the Rainier pack off and a
fixture area that holds its own elevation tiles. The same step code passed for the implementer and
failed twice for the verifier on one head. Why does the same read give two answers?

## What was tried

`tools\sweep.ps1 -Only features-sky` whole against the release build, in the sweep's default mode
and with `-DeviceServer direct`, on the heads dde7d58f and 9eb9700e of `task/T378`, by the
implementer and by the verifier on separate emulators (API 35), ten runs in all. The step seeds the
area through `POST /areas/fixture/tiles?dem=1`, switches airplane mode on, and reads `GET /horizon`
and `GET /holdings`. Reports: the merge commit of T378 (398de1a4) carries both agents' accounts.

## Result

The terrain read takes one of two paths, and which one varies from run to run on the same code.

- **The area's source is in the read's list.** The area gives the cells inside its box at z13 and
  the terrain's `maps` name the area. The Horizon has 22 candidates.
- **The area's source is missing.** No box holds the point, the read goes one zoom deeper, and the
  "online source" member serves every cell: the area's own tiles, from the cache under the fetcher's
  keys. `maps` is empty. The Horizon had 21 candidates in the verifier's runs.

No request goes to the network on either path (the counters of `GET /online` do not move, and the
phone is in airplane mode). So a user sees a horizon either way, but not always the same one: one
candidate differed.

What lines up with the difference, from the verifier's last two runs: the fetcher's state at the
moment of the seed and the first read. With the fetcher `online`, `GET /holdings` at z12 answered
that the area serves the elevation (stored maximum zoom 12) and the fixture copied z8 to z13. With
the fetcher `offline`, it answered that the cache serves it ahead of the area (stored maximum zoom
15) and the fixture copied z6 to z13. Both of those runs still named the area in `maps`, so the
fetcher's state is not the whole of it. The implementer points at the list's source,
`TileService.heldAreas()`, as where the area can be absent. Neither agent pinned the cause.

Not ruled out: that the difference is the fixture's alone (it copies a pack's tiles under an area's
keys, which no download does in that order), and a real downloaded area never shows it.

## Next

1. Reproduce off the sweep: seed the fixture, then read `GET /horizon` repeatedly with the pack off,
   with the fetcher forced online and offline, and log the read's source list each time.
2. Read how `TileService.heldAreas()` is built and cached, and whether a read can run between the
   area's vector item and its elevation item being listed.
3. Repeat with an area downloaded the ordinary way. If it never shows there, the defect is the
   fixture's, and the fix is to seed in a download's order.
4. If it shows for a real area: the lookup's order between an area and the cache for elevation is
   ADR 0066's to state, and the horizon should not depend on it.
