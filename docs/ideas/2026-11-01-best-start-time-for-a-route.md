---
date: 2026-11-01
kind: feature
---

# Best start time for a route: shade chosen by the hour

T194's `routing/ShadeAlong.kt` gives the shade stretches of a route for one start instant. Run it over a range of start times (every 15 minutes across daylight) and suggest the start with the most terrain shade on a hot day, or the most sun on a cold one: one card line and a `devtool shade --best-start` option. Cheap if the per-instant cost allows it (a 10 km route was 400 rays, 166 ms on the phone, so about 50 starts is seconds). The owner liked this one first.

## Open

- The objective: shade fraction, shade time before the hottest hour, or a weighted mix; hot day by the same 25 C line as `shadeSpoken`.
- Whether it composes with the terrain-sunset deadline (the latest start that still returns before the sun is gone).

Recorded 2026-11-01 at the close of wave 24 from the coordinator's list, moonshots included at the owner's invitation. Untriaged, and not even sanity checked: the premises below were not read against the code. Web delivery (the browser demo at `tools/demo/`, `devtool serve`) is an open question for every idea here; no Kotlin web site exists in the repository today.
