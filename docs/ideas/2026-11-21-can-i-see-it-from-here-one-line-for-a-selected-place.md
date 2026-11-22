---
date: 2026-11-21
kind: feature
related: [2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md, 2026-10-29-viewshed-and-peak-labels.md]
---

# "Can I see it from here?": one line for a selected place

With a place selected on the map (a long-pressed point, a search result, a tapped peak), one line answers whether it
is in view from where you stand: "In view, 12.4 mi NNW", or "Hidden by a ridge 3.1 mi away" with how far it is hidden.
Today the answer takes two steps and a reading: "What can I see from here" draws the whole visible area (T182), and
the peaks list says in view or not for named peaks only.

It is one ray, not a sweep: the terrain's highest angle along the bearing to the place (`dem/Horizon.kt`, `RayMarch`)
against the place's own angle with the earth's drop (`Horizon.dropM`). When the fix's horizon is already kept
(`Gnss.horizons`, T321) it is a lookup: `HorizonProfile.angleAt` and `distanceAt` at that bearing. `Viewshed.peaks`
does the same comparison for every named peak.

Differs from the which-way idea: that one turns the Panorama to face the place; this one answers before any view is
opened, and the Panorama's mark for a hidden place could carry the same line.

Recorded 2026-11-21 from the coordinator's list after wave 44, on the owner's "we can record them all". Untriaged.

## Open

- Where the line shows: a row of the long-press card and of a search result, the place line of the two views.
- "By how much" in words a walker can use: degrees mean little; "you would need to be 40 m higher", or the name of the
  ridge in the way when the map has one.
- The place's height: the ground there, or a summit's recorded elevation when it is a peak.
- Beyond the elevation data on the phone or the view range (`viewRange`): it says it cannot tell, never "hidden".
