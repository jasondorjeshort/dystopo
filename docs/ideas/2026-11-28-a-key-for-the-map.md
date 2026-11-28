---
date: 2026-11-28
kind: feature
related: [2026-11-20-fewer-or-more-peak-names.md, 2026-11-22-every-value-that-can-dash-or-dim-gets-a-question-mark-and-a-reason.md]
---

# A key for the map

Source: coordinator (claude-fable-5-2), a wave close; offered and accepted by the owner; not sanity-checked.

The owner, 2026-11-28, accepting it: "Key needs to be fit into the UI somehow, possibly another popup."

Since wave 49 a long press on any dot, marker or dashed line says what it is ("Track start", "Water", "Gap in the
track", "Rejoin point"); the names come from one table, `androidApp/.../app/tracking/MapMarks.kt`, which a test
holds against the layers the map draws. To learn what a mark is, a user must still press each one.

The idea: a key that lists every kind of mark and line in the current view with its drawing and its name, read
from the same table and from what is on screen now (`GET /map/marks` already lists the layers present), so it
never names something the map does not show and cannot drift from the long-press names. Tapping a row could
flash the marks of that kind.

Lever: answers "what is this dashed line" for the whole view at once; reuses a table that is already tested.

## Open

- Where it opens from, the owner's point: a pop-up from the status chip's card, from the map options, or from the
  long-press card ("What else is on this map?").
- Whether the base map's own symbols (trails by kind, contour intervals, land cover) belong in it, or only the
  marks the app draws.
