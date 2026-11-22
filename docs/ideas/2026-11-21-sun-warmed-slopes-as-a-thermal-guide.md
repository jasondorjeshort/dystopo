---
date: 2026-11-21
kind: feature
related: [2026-11-01-shade-aware-routing.md]
---

# Sun-warmed slopes: where the sun strikes steep ground hardest at an hour, as a rough guide to thermals

The sun-and-shade layer (T188, shadow tiles by the minute) already knows, for each cell, whether the sun reaches it.
The sun's angle of incidence on the slope (from slope and aspect, which the hillshade computes) gives how hard it
strikes; summed over the morning, the most-warmed slopes are where thermals tend to start, which paraglider and
hang-glider pilots and soaring-bird watchers read from terrain by eye. A map layer of warming (this hour, or the morning
so far) is a new reading of data the app already computes.

The owner, 2026-11-21: "Interesting. Eventually."

## Open

- It is a rough guide, loudly labelled: wind, cloud, ground cover and moisture decide real thermals, and none is known
  offline. A safety reading (flying) means the wording is the owner's.
- With the one-clock idea, the layer follows the set time.

Question: "Interesting. Eventually": leave it at rest, or name what it waits for? Recommended: it rests until the one
clock lands (`2026-11-21-one-clock-for-the-whole-app-past-and-future.md`), since a warming layer is read for an hour
you set, not for now; then one pass could add it as a second reading of the sun-and-shade layer (the sun's angle on
each slope, from the slope and aspect the hillshade already works out, summed since sunrise). Its label and its help
are yours before it ships: it would be read as flying advice, and wind, cloud and ground cover, which decide real
thermals, are unknown offline.

Answer:
