---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md, 2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md]
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

Question: build "the view from that plane" inside the Panorama view's first task or as its own task after it, and does
the view freeze at the tap or follow the aircraft? Recommended: its own task after the Panorama view lands (your
words: Panorama after the airplane tracks), freezing at the tap first; a moving observer at 10 km needs the downward
band and long range the window-seat idea also wants, and the two should share that work.

Answer:
