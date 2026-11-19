---
date: 2026-11-19
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md, 2026-11-19-todays-sun-path-across-the-skyline.md, 2026-11-19-the-horizon-view-beside-the-map-view.md]
---

# Peak names on the sky plot's skyline

The GPS view (T307, `app/gnss/`) draws the land's skyline around the phone from the DEM (`Horizon.sweep`'s
`HorizonProfile`). T182's viewshed already finds the named peaks in view with their true bearing, apparent angle
and whether each stands on the skyline (`shared/.../dem/Viewshed.kt`, `Viewshed.peaks`). Put the two together:
label the peaks that stand on the skyline at their bearing on the plot's rim, with the height and distance on a
tap ("Little Tahoma, 3,410 m, 7 km"). With the plot following the phone, turning to face a ridge names it, so
the view works as a peak finder without a camera (the related idea's stage 3 is the camera version).

## Open

- Crowding: a dense range gives dozens of peaks; keep the highest few per sector of the rim, the rest in the list.
- The viewshed's sweep at its range setting (10, 30 or 100 km) costs more than the plot's own sweep; reuse one.
