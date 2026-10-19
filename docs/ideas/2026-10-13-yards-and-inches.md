---
date: 2026-10-13
kind: feature
---

# Imperial below feet: yards for short distances, inches for a paper-scale readout

The part of the large-distance-units idea (recorded 2026-10-13) that T101 did not build. Imperial
distances read feet below 528 ft and miles above, one rule in `UnitFormat` (`shared`) for the scale
bar, track and route stats, turn directions and search. Short distances could read in yards, and
inches may matter one day for a paper-scale readout. The units preference is `ft` or `m` (ADR 0022).

Question: should imperial short distances read in yards, and below what length? It changes what every distance shows (scale bar, stats, directions, search) through the one rule in `UnitFormat`, so it is yours. Recommendation: keep feet below 528 ft as today, which is what US trail signs and the mainstream map apps use; inches only if a paper-scale readout is ever built, so that part waits for it.
Answer:
