---
date: 2026-11-21
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md, 2026-11-21-one-clock-for-the-whole-app-past-and-future.md]
---

# Peak names for your old photos, offline, from the photo's place and time

A photo carries where and when it was taken (EXIF GPS and time; a compass heading on some phones). From that, the app
can draw the predicted skyline from that point with the peaks named and the sun at that moment, using the Horizon
view's sweep (`Gnss.horizonAt`, T317's chosen point) and the peak ranking (T309), so the photo's mountains can be named
without a camera feature or a network. A side-by-side (the photo above, the drawn ribbon below, scrolled to the heading
or by hand) is the first form; matching the photo's skyline to the drawn one for an exact overlay is the later,
harder half (it shares work with the horizon-match idea, `2026-11-02-horizon-match-fix-from-the-camera.md`).

The owner, 2026-11-21: "Definitely, eventually."

## Open

- Picking the photo: Android's photo picker, no storage permission; EXIF location is stripped by some share paths.
- It is the Panorama view at a chosen point and a chosen time, so it waits for the Panorama view and the one-clock idea.
