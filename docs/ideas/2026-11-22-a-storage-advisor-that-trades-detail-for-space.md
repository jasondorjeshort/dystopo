---
date: 2026-11-22
kind: feature
related: [2026-11-22-a-much-smaller-detail-level-the-whole-us-in-under-10-gb.md]
---

# A storage advisor that trades detail for space

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for more and recorded on the owner's word, 2026-11-22). Nothing here has been read against the code or measured; the anchors and the list of what exists are from memory of the session's reading, to be checked at triage.

"Your US map's Medium tiles are 60 GB of a possible 90; Less would free 40 GB": a one-tap trade on the Storage or Offline maps screen, with the per-zoom split of each map shown, which needs the smaller level and a way to drop the top zoom of an existing map.

**What exists already (to be checked):** The Storage screen and the budget lines exist (`storage/StorageScreen.kt`, "Counts in the budget"); dropping a map's top zoom does not.
