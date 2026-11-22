---
date: 2026-11-21
kind: feature
related: [2026-11-20-the-moon-on-the-horizon-view.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md, 2026-11-21-one-clock-for-the-whole-app-past-and-future.md]
---

# Stars and planets over the named skyline

Moonshot. A night layer for the Horizon and Panorama views: the bright stars and the planets drawn over the terrain's
real skyline with its peak names, and for any of them when it clears which ridge. Sky apps draw the stars over a flat
horizon; what this app has that they do not is the skyline from the elevation model, offline. With Follow the phone
(ADR 0064, pass three) it is the sensor mode those apps have, with mountains in it.

What it needs: sidereal time and the conversion from a star's fixed position to bearing and angle, pure Kotlin in
`shared` beside `nav/Sun.kt`; a table of the few hundred brightest stars, a data source whose licence and credit go in
`sources.json` before any of it is used (`CLAUDE.md`, Rules); the planets from a low-precision model, which the Moon
idea already names as its later half; a layer in the `HorizonLayer` list so the names take part in `HorizonLabels`.

A use beyond looking: a star's position is known far better than the compass reads, so pointing at one named star is a
compass check at night, the same correction the Panorama's drag offset makes by day.

Recorded 2026-11-21 from the coordinator's list after wave 44, on the owner's "we can record them all". Untriaged and
not sanity checked.

## Open

- How far it goes: stars and planets only, or constellation lines and the Milky Way's band too. Established sky apps
  do the full sky well; the case for it here is the skyline and the names, so the smallest useful form comes first.
- The layer means little by day: shown only when the sun is down, or with the one-clock idea set to tonight.
- Which star table and its terms, checked before a proposal.
