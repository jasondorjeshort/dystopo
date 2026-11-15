---
date: 2026-11-14
kind: question
related: [2026-11-14-drive-speed-falls-on-winding-roads.md]
---

# A drive preference: fastest, balanced or shortest

The owner, 2026-11-14, asked whether drive search optimises distance, time or both and whether it is an option. It
is time only today (`routing/Router.kt`, T73) with no choice. Established apps offer fastest and shortest and often
avoid motorways, tolls and unpaved roads; users split (a long drive wants fastest, a fuel-saver or a cyclist's
support car wants shortest), which under ADR 0044 means a setting, with fastest as the default.

Research first (`benchmark.md`, survey of how the established apps and routers word and default it, ADR 0039): the
set of choices, the wording, and what "shortest" means beside time (a blend by a weight on the distance term).
Then the plan: a `DriveCriterion` in `Router.kt` (the cost model's weight of distance against time), a synced
setting and a row in Settings, Navigation and tracking, a debug route, and tests that fastest and shortest differ on
a synthetic graph. Built after the turn cost, which changes what "fastest" means.

## Open

- Avoid-unpaved, avoid-tolls and avoid-motorway as separate toggles beside it: not this idea.
- The owner, 2026-11-14, at wave 33's launch: the drive choice starts as "most efficient algorithm", "least gas" and
  "least time". Wave 33 built that list (T282, T284, T285; ADR 0063): Least time, Least fuel, Quicker planning.
  Shortest is not in it and still waits: a third metric of the same form, when someone asks for it.

Question: is a Shortest drive wanted now, or does it stay at rest until someone asks? Recommended: at rest. The
survey of 2026-11-15 (routers' docs) found every default is time; one mainstream app offers a shorter-distance
route only as an extra, with a warning that it may take dirt roads and be slower; the two routers that expose it as
a dial blend distance with time and never use pure distance. Built, it would be that blend (distance plus a share
of time, turn costs and access kept, help text "may use smaller roads and take longer"), and it is not "a third
metric of the same form": `routing/DriveOverlay.kt` treats every metric that is not time as fuel (the cell file's
name, magic and turn table), so the overlay is generalised first. The idea that puts both routes on the card
(`2026-11-15-the-card-offers-the-other-route.md`) is the better home for a third row if it is ever wanted.

Answer:
