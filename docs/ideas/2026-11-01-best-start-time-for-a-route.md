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

2026-11-02 (triage): the 166 ms was the Paradise to Panorama Point route (120 samples), so a 10 km route is
about half a second a start and fifty starts tens of seconds on the phone; `compute(..., reuse = terrain)`
and `covers()` exist, so the terrain is read once. `HotDay.THRESHOLD_C` is the 25 C line. No sweep exists.

Plan: one pass. In `shared`, beside `routing/ShadeAlong.kt`, a sweep over start times for a route: every 30
minutes across daylight with the terrain reused, then 15-minute refinement around the best; the objective
is the fraction of walking time in terrain shade on a hot day (the `HotDay` line) and in sun otherwise;
starts whose terrain-sunset deadline (`Deadline.resolve`) is missed are excluded, so it composes with the
clock. `devtool shade --best-start` prints the table and the pick as JSON; the route card gets one line
("Start by 06:30 for the most shade" or the sun form) computed off the main thread once per plan and
cached with the plan; a `commonTest` on a synthetic east-west valley. No setting: the hot and cold rule is
the existing one, and the task's report states where the objective departs from a quick survey of how
the planning apps phrase a start-time suggestion (ADR 0039, one agent). Out of scope: a date picker.
