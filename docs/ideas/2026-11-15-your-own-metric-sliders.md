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

Plan: one implementer, hike only (ADR 0031: built, shown, the owner adjusts). Today the standard time is 5 km/h
plus an hour per 600 m of climb, descent free (`CostModel.timeS`, `routing/Router.kt`), replaced by the fitted
curve when one is ready (`routing/PaceFit.kt`); the owner's two values are that same rule with its numbers open.
Build: a third choice in Settings, Navigation and tracking, Route times, beside Your pace and Use the standard
estimate: Your own numbers, with minutes per mile and minutes per 1000 ft of gain (per km and per 300 m for a
metric user; stored in SI, two synced keys), defaults equal to today's standard (about 19 min a mile, 30 min per
1000 ft) so choosing it changes nothing until a value moves. A pace slider on the hike card, 0.7 to 1.4, for this
trip only (the session, not synced), multiplying whichever basis is in use; the card's basis line says "Based on
your numbers" and names the pace when it is not 1. The search follows: the slope penalty's 8 m per metre of climb
becomes the ratio of the two values. No descent value: the survey of 2026-11-15 found every alpine standard but
the plain rule has one (500 to 800 m an hour down), so the help says descent counts as level ground and a fourth
value is the follow-up if steep descents read short. Debug: `GET /pace` and `POST /settings` carry the three.
Done when: unit tests (defaults equal the standard; the multiplier scales time, not the route), the row and the
card captured in both unit systems. Out of scope: drive sliders, fitted values as the sliders' defaults.
