---
date: 2026-11-22
kind: question
related: [2026-11-07-search-and-routes-find-nothing-outside-a-pack.md]
---

# The View range cap (100 km) may be short: the owner has seen peaks about 80 miles away

Priority: **low** (after the blank-view fault; a limit, not a bug).

The owner has seen peaks about 80 miles (about 130 km) away; the Panorama's `viewRange` offers fixed steps up to
100 km (`Viewshed.RANGES_KM`, the setting `viewRange`, `AppSettings.kt:597`; the peak read to 100 km,
`HorizonData.kt:97`), so such a peak cannot be drawn. Anchors: `Viewshed.RANGES_KM`, `Viewshed.peakZoom`,
`LayeredSkyline.ranges`.

## Open

- The cost of a longer range: terrain tiles to read and decode, the sweep's time, memory on the phone, and curvature
  and refraction at 130 km and beyond (a peak below the geometric horizon of a low viewpoint).
- Whether a taller peak could extend the range on its own (the highest peaks within a larger radius only) instead of
  one larger range for everything.
- Whether the range is a setting step (150, 200 km) or automatic from the viewpoint's height.

2026-11-23 (planning wave 48): surveyed and costed by a read-only agent,
`docs/findings/2026-11-23-view-range-past-100-km-survey-and-cost.md`. Other tools reach 300 km or more; curvature
and refraction are already in the sweep (k 0.13); from a standing eye a peak at 130 km needs about 1,050 m of
summit. The Horizon view's own peak read stops at its profile range (30 km by default), not 100 km as written above.

2026-11-24: drafted as a task for wave 48 and held at the critic's word (the first cut of a wave that had grown to
seventeen tasks). What the premise read and the critic found, for whoever builds it: `Viewshed.RANGES_KM` and
`MAX_RANGE_M` are shared by the map's viewshed and the sky views, and one synced key `viewRange` drives the Settings
row, the map's range chips (`options/MapOptionsSection.kt`, written through `ViewshedSession.setRange`), the
viewshed's route validation and its bench's default, the Panorama and the List; a phone on an older build that is
synced a 200 rejects it (`AppSettings.kt`'s validation); `LayeredSkyline` is in `shared/.../horizon/`; on the
two-maps fixture (the Washington extract) the peak to test with is Glacier Peak, about 148 km from Mount Rainier's
summit; the edge join of ADR 0066 changes the terrain read's cost, so the before and after are both taken after it.

The pass as drafted: a 200 km step joins `viewRange` (10, 30, 100, 200; the default stays 30; the chips in the user's
units), with a coarser far ring in `Viewshed.plan` (the cell about a 480th of the distance: z8 beyond 100 km) and
a clearance pre-filter for peaks beyond 100 km (the summit must clear the curved horizon from the eye; a peak with
no height is kept). The map's "What can I see from here" keeps its 100 km limit, said in the setting's help.
Measured on the emulator before and after at 100 and 200 km from a valley and a summit (`GET /panorama` `work`,
the heap's peak, `viewshed/bench` at 100 km unchanged). No new setting (ADR 0044: the step extends an existing
one; the ring and the filter are cost and geometry). "Automatic" is left as a later question for the owner.

Question: one synced range drives the sky views and the map's "What can I see from here". With 200 km chosen for
the sky views, what does the map do?
- (a) Recommended: the map's chips stay 10, 30 and 100; at 200 the map works to 100 and shows 100 chosen; a tap on
  a map chip changes the one range for every view.
- (b) Two ranges: the map's own (10, 30, 100) and the sky views' (10, 30, 100, 200), a second synced key.
- (c) The map goes to 200 km too (about four times the cells of 100 km; measured first).

Answer: (a), the map's chips stay 10, 30 and 100 (the owner, 2026-11-24, by multiple choice).
