---
date: 2026-11-24
kind: feature
related: [2026-11-22-one-line-of-sight-function-in-shared.md, 2026-11-22-the-map-shows-where-the-phone-points.md, 2026-11-24-sort-the-list-view.md]
---

# A List row opens the map with the sight line drawn

Source: coordinator (claude-opus-5-5), after the ideas triage of 2026-11-24; offered and accepted by the owner; not sanity-checked.

The List view (T344, ADR 0067; `androidApp/.../app/skylist/SkyListScreen.kt`) names the peaks in sight, but a row
leads nowhere on the map. The idea: a tap (or a row action) switches to the map centred to show both the viewpoint
and the peak, with the sight line between them drawn and its clear or blocked stretches marked, from the one
line-of-sight function in `shared` (its own idea). Back returns to the List. Lever: it closes the loop from the newest
view to the map, and gives the line-of-sight function a second caller beside the two-places sight line.

## Open

- A row tap or a row action, given that a tap may already do something (check T344's row).
- Whether the map keeps the line after the user pans away, and how it is dismissed.
