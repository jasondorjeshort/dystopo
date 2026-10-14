---
date: 2026-10-13
kind: feature
status: planned
pipeline: T101
related: [2026-10-13-map-scale-bar.md, 2026-10-13-turn-directions.md]
---

# Imperial is not just feet: miles at large scales, inches some day

ADR 0022 makes units a preference (`ft` or `m`), but in imperial, large distances must read in
miles (and short ones in feet, perhaps yards), not in thousands of feet. Metric already switches
between metres and kilometres. Inches may matter one day (a paper-scale readout).

## Open

- Where the switch happens (for example feet under about 0.1 mile, miles above); one rule shared
  by the scale bar, track and route stats, turn directions ("in 30 feet", "in 1 mile") and search
  results, in `shared` next to `UnitFormat`.
- Whether contour labels and elevations stay in feet always (they do today) while distances switch.
- Whether the preference grows a third value or stays `ft`/`m` with the formatting rule underneath.
- Check what `UnitFormat.distance` does today at long distances before designing.
