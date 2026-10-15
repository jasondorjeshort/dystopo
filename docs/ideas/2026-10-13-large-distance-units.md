---
date: 2026-10-13
kind: feature
status: done
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

## Resolved

T101 (wave 14). Before: `UnitFormat.distance` already read feet below a tenth of a mile and miles above
(T33), with two decimals below 10 miles ("5.06 mi"), so the trip card, route stats, navigation and search
never showed thousands of feet; the strings that bypassed it were the pack catalog's box size (always km),
the area card's corridor line (km and m) and the router's own messages and stored route description (km
and m). Now one rule in `UnitFormat`: feet below 528 ft, then miles with one decimal below 100 ("0.6 mi",
"5.1 mi", "50.9 mi"), metric unchanged; the catalog size follows it, the corridor line moves in T106 and
the router's texts are a follow-up. Elevations and contour labels stay in feet; the preference stays
`ft`/`m`. Yards and inches: not built.
