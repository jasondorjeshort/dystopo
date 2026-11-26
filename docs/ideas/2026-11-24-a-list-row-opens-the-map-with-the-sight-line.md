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

Plan: one pass, after `2026-11-22-one-line-of-sight-function-in-shared.md` lands (it gives `LineOfSight.between`); not
in a wave that owns the List's step two (ADR 0067). Checked at triage: a peak row's tap already picks the peak
(`SkyList.show`, the `PickedPeak` line in `app/skylist/SkyListScreen.kt`), so the entry is an action, "Show on map", on
that picked line, not the row tap (ADR 0031: built, shown in the report). It switches to the map framed on the viewpoint
and the peak, draws the line in two styles, clear up to the blocking ridge and dashed beyond it (`Sight.ridgeM`; one
style for in view, none drawn for cannot tell), as a map layer of its own; the line stays until the map's Back, a tap
on it, or another pick, and Back returns to the List at its scroll. Files: `SkyListScreen.kt`, `SkyListRoutes.kt`
(a `POST /skylist` parameter that does the action and answers with the drawn line's figures), a new layer beside `app/tracking/LongPressMenu.kt`'s sight code, help text. Verify:
unit test of the line's split in `shared`; on the emulator at Paradise (Rainier) a visible and a hidden peak, the map
frames viewed, Back returns to the List. Out of scope: lines for the Sun, satellites or flights; a line kept across
restarts.
