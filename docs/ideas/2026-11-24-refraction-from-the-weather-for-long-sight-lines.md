---
date: 2026-11-24
kind: feature
related: [2026-11-22-the-view-range-may-be-too-short-for-peaks-80-miles-away.md, 2026-11-20-moonshot-contrail-forecast.md, 2026-11-24-i-can-see-it-reports-for-peaks.md]
---

# Refraction from the weather, for long sight lines (moonshot)

Source: coordinator (claude-opus-5-5), after the ideas triage of 2026-11-24; offered and accepted by the owner; not sanity-checked. Moonshot.

The skyline uses one refraction coefficient, `Horizon.REFRACTION_K = 0.13`
(`shared/.../dem/Horizon.kt`). At the 130 km sight lines the owner has seen, refraction decides whether a summit
clears the horizon, and the real coefficient swings widely with the temperature gradient near the ground (inversions
lift distant peaks into view; a hot afternoon lowers them).

The idea: take the coefficient from a forecast's near-surface temperature lapse along the line (or a simple day and
night pair when no forecast is held), and let the Horizon view and Panorama say "Mount Rainier should be visible from
here this morning" or "marginal". Offline, the constant stays. Lever: it turns the 200 km range (held for wave 49)
from "drawn if geometry allows" into a forecast people can test, and the "I can see it" reports would calibrate it.

## Open

- A forecast source with a permissive licence and a lapse rate or levels near the ground (registry first).
- How large the effect is at 100 to 200 km for the public areas: a measurement before any build.
