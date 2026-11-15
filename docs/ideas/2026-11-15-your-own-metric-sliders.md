---
date: 2026-11-15
kind: feature
related: [2026-11-15-drive-and-hike-pace-learn-from-your-own-tracks.md, 2026-11-14-drive-preference-fastest-balanced-or-shortest.md]
---

# Your own metric: a few sliders re-price the route, hike first

The drive overlay keys its cells by metric and can re-price them (`routing/DriveOverlay.kt`, ADR 0063), which is
what makes a user's own metric affordable. The owner, 2026-11-15: this could be very helpful for hiking: "A fixed
amount of time per mile and a different additive time per 1000' elevation gain is roughly it. Then a third
multiplier ("pace") you can slide slightly if you intend to go faster or slower." Wanted for a hike: those three
values (time per mile, added time per 1000 ft of gain, a pace multiplier) as settings the planned time and the
search use (`routing/Router.kt`'s `CostModel` takes a pace today), with the pace slider on the route card for this
trip. For a drive later: turn aversion, unpaved, motorways.

## Open

- How this sits with the hike model of today (its descent and terrain terms) and with the related idea that fits
  the same values from your tracks: the fit could set the sliders' defaults.
- Units follow the units setting (per km and per 300 m for a metric user).
- Whether the three values sync.
