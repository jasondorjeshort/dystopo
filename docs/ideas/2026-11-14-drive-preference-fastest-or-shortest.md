---
date: 2026-11-14
kind: question
related: [2026-11-14-drive-routes-pay-for-turns.md, 2026-11-14-drive-speed-falls-on-winding-roads.md]
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
