---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md, 2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md]
---

# The view from that plane: tap a flight and open the Panorama view from it

What: tap an aircraft on the map or in the sky and open the Panorama view standing at its position and altitude,
looking where it is heading: what its passengers see now. The Panorama view takes an observer position and height;
this supplies another one instead of the phone's.

Why it suits Dystopo: it turns the layer from a tracker into a way to look at terrain, using the elevation model and
the named peaks the app has offline. It needs only the aircraft's position and altitude from the feed.

Data: the live position (adsb.lol, ODbL) and the shipped DEMs; a view from 10 km up needs the coarse world overview, a
long range and a downward band of elevation angles (the window-seat idea's flip). The Panorama view is queued next,
after the aircraft layer, so this waits for it.

## Open
- A downward band of angles and a 300 km range cost render time; what range the Panorama view allows.
- Whether the view then follows the aircraft (a moving observer) or freezes at the tap.
- Names of features below the horizon: the same open question as the window-seat idea.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

2026-11-21, the owner, by multiple choice, asked whether to build it inside the Panorama view's first task or as its
own task after it, and whether the view freezes at the tap or follows the aircraft: its own task after the Panorama
view lands, frozen at the tap (recommended; a moving observer at 10 km needs the downward band and the long range the
window-seat idea also wants, and the two should share that work).

Plan: one pass, after the Panorama's pass three has merged (it works in the same files; the view itself landed in
wave 45). `HorizonPoint` (`shared/.../horizon/HorizonPlace.kt`) gains a height above the ground and an "as of" time;
the sweeps take that height (`Horizon.sweep` has the parameter) and a range that reaches the horizon from it over the
coarse world layer, their time measured on the emulator and capped, the report saying what the cap costs in
distance. The flight card (`app/aircraft/AircraftCard.kt`) gains "View from this flight": the Panorama opens at the
aircraft's moved-up position and altitude as of the tap, facing its track, tilted below level, and stays there; the
amber place line reads "From <flight>, <altitude>, as of <time>" with "Back to here"; Satellites and Flights are off
as at any chosen point. Names below the horizon are placed (pass one made those angles placeable). `POST /panorama`
takes the same from a replayed flight. Verify: `devtool panorama --area rainier` with `--eye-m` at a cruise height
viewed; on the emulator a replay's flight tapped, the frame viewed at the default text and at font scale 2.0, the
sweep's time in the report. Strings in the report (ADR 0031). Out of scope: following the aircraft, the round plot
turned over (pass two of `2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md`), the ground drawn as a
surface.
