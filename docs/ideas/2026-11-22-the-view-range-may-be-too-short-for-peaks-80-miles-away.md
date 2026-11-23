---
date: 2026-11-22
kind: question
related: [2026-11-22-at-the-phones-own-position-with-two-packs-the-panorama-is-blank-and-horizon-peaks-never-draw.md]
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
