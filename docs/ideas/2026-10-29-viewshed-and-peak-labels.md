---
date: 2026-10-29
kind: feature
related: [2026-10-29-sun-and-shade-on-terrain.md]
---

# What can I see from here: a viewshed, then peaks named on the skyline

Press a point (or the current fix) and the map shades every cell visible from it at eye height, out
to a chosen range. A second stage turns that around: list the named peaks in view with bearing and
distance, and later draw their names over the camera's view of the skyline. The DEM is already on
the phone (`shared/.../dem/ElevationGrid.kt`, `TileWindows.kt`, the per-cell gradient in
`render/Slope.kt`), the peaks are already in the outdoors layer (`outdoor_poi`, `natural=peak`,
`tilebuild/PROFILE.md`), and the heading is already read (`androidApp/.../controls/Heading.kt`).

Stages, each useful alone:

1. **Viewshed overlay.** A radial line-of-sight sweep over an `ElevationGrid` from the observer,
   with earth curvature and standard refraction (k about 0.13), drawn as a translucent overlay like
   the slope bands (`tiles/ShadeTiles.kt`). Devtool: `viewshed --area rainier --at <point>` writes
   a PNG and a JSON report (cells visible, range, time). About one wave.
2. **Peaks in view.** The `natural=peak` points inside the viewshed, ranked by prominence or
   angular height, as a list with bearing, distance and elevation; tap one to show it on the map.
3. **Camera labels.** Camera preview with names placed by bearing and elevation angle, corrected by
   matching the DEM's rendered horizon line to the skyline edge in the image, since the phone's
   compass alone is off by 5 to 15 degrees near metal or after a bad calibration. Three or more waves.

## Open

- Range against data: packs hold detailed DEM only to their area's edge, and a summit view reaches
  100 km or more. Use coarser zooms past a few kilometres (z10 at 50 km loses little), read from the
  online tiles when there is a signal, and say where the data stopped.
- Cost on the phone: a 30 km sweep at z12 is millions of cells; measure, then cap the range or step
  down zooms with distance. Must not block the UI thread (T144).
- Stage 3 needs a camera dependency (CameraX, Apache-2.0) and the camera permission, which the app
  does not ask for today: a new permission the owner should see before it is built.
- Benchmark first (ADR 0039): how established apps present viewsheds and skyline labels, and what
  the user is told about accuracy.

Question: stage 3 needs the camera permission and a camera dependency, a permission the app does not ask for today: do you want the camera stage at all, and are stages 1 and 2 (the overlay, then the list of peaks in view) wanted first on their own? Recommendation: stages 1 and 2 as one task when you want them (the horizon sweep shared with the sun-and-shade idea; coarser zooms past a few kilometres; the range capped from a phone measurement, off the UI thread; the benchmark above first); stage 3 only on your word, as its own task with the permission text in its report.
Answer:
