---
date: 2026-10-29
kind: feature
related: [2026-10-29-sun-and-shade-on-terrain.md]
---

# Peak names on the camera's view of the skyline

Stages 1 and 2 of this idea are built (T182): "What can I see from here" draws the ground visible from a
long-pressed point or the position (`shared/.../dem/Viewshed.kt`, `androidApp/.../app/viewshed/`, devtool
`viewshed`), and lists the named peaks in view with elevation, distance, true bearing and whether each stands on
the skyline. What remains is stage 3.

**Camera labels.** A camera preview with the names of the peaks in view placed by bearing and elevation angle,
corrected by matching the DEM's rendered horizon line to the skyline edge in the image, since the phone's compass
alone is off by 5 to 15 degrees near metal or after a bad calibration. The inputs exist: `Viewshed.peaks` gives
each peak's bearing, distance, apparent angle and skyline flag; the sweep's `HorizonProfile` is the DEM's horizon
line every few hundredths of a degree (`dem/Horizon.kt`); the heading is read (`androidApp/.../controls/Heading.kt`).
Three or more waves.

## Open

- Stage 3 needs a camera dependency (CameraX, Apache-2.0) and the camera permission, which the app does not ask for
  today: a new permission the owner should see before it is built.
- Benchmark first (ADR 0039): how established apps present skyline labels, and what the user is told about accuracy.

Question: stage 3 needs the camera permission and a camera dependency, a permission the app does not ask for today: do you want the camera stage at all, and are stages 1 and 2 (the overlay, then the list of peaks in view) wanted first on their own? Recommendation: stages 1 and 2 as one task when you want them (the horizon sweep shared with the sun-and-shade idea; coarser zooms past a few kilometres; the range capped from a phone measurement, off the UI thread; the benchmark above first); stage 3 only on your word, as its own task with the permission text in its report.
Answer: eventually yes. In the owner's experience with this kind of app, the compass is not accurate enough for it (owner, 2026-10-29).
2026-10-29, on the compass: stage 3 above already treats the compass as a first guess. It aligns the labels by matching the DEM's horizon line to the skyline edge in the camera image, which is what makes the labels usable. Stage 3's proposal shows that match measured on real photos before any camera work is built, and says what happens with no clear skyline (cloud, trees): fewer labels, not wrong ones.
2026-10-30, T182: stages 1 and 2 are built (the viewshed overlay and the peaks in view, the `viewRange` setting of 10, 30 or 100 km); this file now holds stage 3 alone, which waits for its own proposal on the owner's word.
