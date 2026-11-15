---
date: 2026-11-15
kind: feature
---

# A pack carries its facts: driving side, whether roads have names, tunnel and bridge heights

Three things wave 33 worked around belong in the map build (`tilebuild/`): the driving side is a hand-written table
of boxes, wrong for kilometres near some borders (`routing/DrivingSide.kt`, T283); the `montblanc` pack has no
road names, found only when the through-exit rule fell back to class (T282); a tunnel's height is the mountain
above it, patched at pricing time (T286). Wanted: the build writes per pack what it knows (the side per region
from the data, a count of named roads, bridge and tunnel heights interpolated between portals) and readers use it,
the boxes staying as the fallback for a pack without it. The heights are the next wave's task already; this idea
is the rest and the common record. The owner, 2026-11-15: okay.

## Open

- A format question (a pack field other phones read): a proposal before building (`CLAUDE.md`, Decisions).
