---
date: 2026-11-15
kind: feature
---

# Bike routes count a turn across a big road as a cost

Wave 33 gave drives a turn table and a driving side (`routing/TurnCost.kt`, `routing/DrivingSide.kt`, ADR 0062);
bike routes pay nothing for a turn. For a rider the turn across traffic on a primary road is a safety cost more
than a time cost. Wanted: the same mechanism under a bike table (a turn across a large road dear, onto or along a
cycleway free), which needs the bike search on directed-edge states as `routing/DriveSearch.kt` is for drives.
The owner, 2026-11-15: yes please.

## Open

- A setting or always on (ADR 0044: research how the cycling routers do it and whether riders split).
- The cost of moving the bike search to edge states (the drive's was about twice the states).
