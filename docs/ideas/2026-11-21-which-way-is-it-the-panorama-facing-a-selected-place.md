---
date: 2026-11-21
kind: feature
related: [2026-11-20-horizon-view-from-another-place-and-saying-where-it-is.md]
---

# "Which way is it?": the Panorama at your location, facing a place chosen on the map

From the planning of the Panorama view (the coordinator, 2026-11-21; the owner: "Record all of these"). With a place
chosen on the map (a long-pressed point, a peak, a waypoint, the car, a hut, a search result), the Panorama view opens
where you stand, turned to face it, with the place marked even when the land hides it ("behind the ridge, 12 km"):
the answer to "where is it from here". The bearing and distance are plain geometry; whether it is in sight is the
skyline test the Horizon view's peaks already use (`shared/.../horizon/PeakRim.kt`, the clearance against
`HorizonProfile`); the mark joins the names through `LabelKind` in `shared/.../horizon/HorizonLabels.kt`.

It meets the owner's switch rule of the same day (`2026-11-20-horizon-view-from-another-place-and-saying-where-it-is.md`:
with a place selected, the switch opens the view **at** that place, "and later Panorama"). The two are different
questions about one selection: the view from there, or the way to there from here.

Question: with a place selected on the map, does the switch's Panorama segment open at that place (as the Horizon
segment will) or at your location facing it? Recommended: at the place, so both segments follow one rule, and "Which
way is it?" as its own action on the long-press card and on a place's card, opening the Panorama at your location
turned to face it; built after the Panorama's pass two.

Answer:
