# 0067. The List view: the sky views' lists as the switch's fourth segment

Date: 2026-11-23
Status: accepted

Record: the owner's Answer of 2026-11-22 in `docs/ideas/2026-11-22-the-panorama-fills-the-screen-and-the-list-becomes-a-fourth-view.md`
(it stays open for step two); the layout survey
`docs/findings/2026-11-22-research-for-the-clock-the-moving-horizon-and-the-full-screen-panorama.md`; wave 47's
T344 (merged) and T351 in `docs/TASKS.md`.

## Context

ADR 0064 item 1 made the Panorama "the third segment of the Map | Horizon switch" with "no Camera view and no
fourth segment for one". In use the owner found the Panorama's lens too small beside its pane and asked for the whole
screen; the triage's Question (does a fourth view replace the pane, and does it carry the Horizon view's pane too) was
answered yes, in two steps. Wave 47 built step one: T344 moved the Panorama's pane into a List view, the fourth
segment, and T351 fills the window with the lens. This supersedes ADR 0064's item 1 only; its items 2 to 7 (one
Panorama view, the lens, Drag and Follow, the heading correction, the camera as an option behind the drawing, one
label placement, the passes) and its prediction lines stand.

## Decision

1. **Four segments: Map | Horizon | Panorama | List.** The List is one scrolled page for the same place and moment as
   the sky view it was opened from: the layers to show, the terrain line, Flights, Peaks, the Sun's day, Satellites.
   A row's tap turns the Panorama to the item and shows it; a flight's row opens its card. A "List view" button on
   the Panorama is a second way in (its place is the owner's to adjust, ADR 0031). Back from the List does what a sky
   view's Back does. Still no Camera view: the camera stays an option behind the Panorama (ADR 0064 item 3).
2. **Step two, after the owner has seen step one:** the Horizon view's pane moves to the same List. Until then the
   Horizon view keeps its pane, and the List reads the Panorama's data (its Peaks use the Panorama's view range).
3. **The List takes the viewshed's places to go** as a section after Satellites when that is built (the owner's
   acceptance of 2026-11-22, the idea's `Use:` line).
4. The switch's four segments fit at font 2.0 upright by the layout rule, and the map's credit line follows their
   width (T344, `MapLayoutTest`).

## Consequences

The Panorama's lens gets the whole window (T351) and nothing of its pane is lost (`SkyListTest`). The switch is
wider, so the credit line and the first-run card have less room; a fifth segment would not fit at font 2.0 and needs
its own decision. Until step two the List and the Horizon view's pane show two lists for one place, the List's from
the Panorama's range.

- Prediction, check when step two is merged: the Horizon view's pane is gone, the List shows the view it was opened
  from (the Horizon view's 30 km peaks when opened there), and no section of either old pane is missing.
