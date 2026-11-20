---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md, 2026-11-19-the-horizon-view-beside-the-map-view.md, 2026-11-20-the-moon-on-the-horizon-view.md]
---

# Aircraft crossing the sun or the moon, for photographers

What: predict transits, a plane crossing the solar or lunar disc as seen from the observer, and say when and where to
stand (a few seconds and a few hundred metres across the track is what a photograph needs). The Horizon view already
draws the sun's path and the moon is a planned idea; a transit is the aircraft's projected path meeting that disc.

Why it suits Dystopo: sun and moon positions exist in `shared`, the aircraft's angles exist in the new layer; the new
part is the intersection and the forward projection. A planned shot is a reason to open the app.

Data: live positions (adsb.lol, ODbL) for aircraft already in the air; no schedule data (none is free or offline).
Accuracy depends on the position's age and the poll interval; dead reckoning between polls helps.

## Open
- It needs a short horizon (minutes) and a fast poll near the event, which spends quota.
- The disc is about 0.5 degrees across: how often a prediction can be trusted against the position error.
- Whether a planning mode (where to stand so a known flight crosses the sun) is wanted, or only the live alert.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.
