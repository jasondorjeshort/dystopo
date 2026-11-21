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

Question: one proposal for a single pack and graph revision that carries all of it, yes? Recommended: yes
(`proposal` skill, a critic, an ADR), covering the driving side per pack, a named-roads count, stop and signal
points (`2026-11-14-drive-stops-and-signals-cost-time.md`) and the graph builder's bridge and tunnel heights (the
next wave's task, which would otherwise spend a graph version alone), so packs and graphs are rebuilt once. Found
2026-11-15: pack facts already have a home, the archive's metadata (`dystopo:*` keys from `tilebuild`'s
`Attribution.extraMetadata` and `OutdoorsProfile.extraArchiveMetadata`, read through `PmtilesReader.metadata()`
by the phone, the server and the graph builder), so the format question is which keys, not a new file. The road
names of `montblanc` first, a chore before the proposal: the profile writes road names at z14 only
(`OutdoorsProfile.kt`) and the graph builder reads the pack's top zoom, so a pack built below z14 has none; read
that pack's top zoom and one tile's `name` attributes. If that is the cause, the fact to record is the zoom and
the fix is in the profile or the pack recipe, not in a count.

Answer: the owner, 2026-11-21, by multiple choice: yes, one proposal (recommended), after the chore on the montblanc pack's road names.
