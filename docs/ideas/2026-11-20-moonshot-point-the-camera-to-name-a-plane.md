---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md, 2026-10-29-viewshed-and-peak-labels.md]
---

# Moonshot: point the camera at the sky to identify a plane

What: the camera shows the sky and the app draws a label over each aircraft it knows is there (flight, type, origin
and destination). The phone's orientation and position give a bearing and elevation for every aircraft in the layer, so
labels are placed with no image recognition, as the peak-label idea does for the skyline.

Why it suits Dystopo: it is the augmented-reality form of the sky dots, built on the same angles; the camera is only
the backdrop. Offline-first holds only as far as the feed does.

Data: the layer's positions (adsb.lol, ODbL) and the orientation sensors; nothing new to license.

## Open
- Compass error and position age (a few seconds at 250 m/s is a kilometre) will misplace labels; dead reckoning and a calibration step matter.
- Camera permission and the cost of a live preview; a big feature for a moonshot, so the owner decides whether it is wanted.
- Whether it should wait for the peak-labels-on-camera idea and share its overlay.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

Question: build a camera view that labels aircraft in the sky, a new camera permission and a large feature?
Recommended: not as its own work; if the peak labels on the camera (`2026-10-29-viewshed-and-peak-labels.md`) are
ever built, add aircraft to that overlay then, since placement, calibration and compass error are shared work, and
position age (a kilometre in four seconds at cruise) makes plane labels the harder half.

Answer: the owner, 2026-11-21, by multiple choice: "I think camera view is a fourth type of view where it shows any direction, not just the skyline. Unless this can be merged into panorama view somehow. Lets record but park it for now." (asked whether it is part of the Panorama view: the Panorama as planned is a drawn ribbon with no camera.) Parked: a camera view, a possible fourth view looking in any direction, with peaks and planes labelled.
