---
date: 2026-11-19
kind: question
related: [2026-11-19-peak-names-on-the-sky-plots-skyline.md, 2026-11-19-todays-sun-path-across-the-skyline.md, 2026-11-19-the-sky-from-a-long-pressed-point.md]
---

# The Horizon view: the map view's counterpart, a sky with layers

The owner, 2026-11-19: "How do I get into the horizon view? I feel like this is more than just a 'gps tools' view
now and could have a front page button." Today the GPS view (T307) opens only from Settings › Navigation and
tracking › "GPS status ›". With the skyline, the sun's path, peak names and a point of one's choosing (the related
ideas), it is a sky view with a GPS section, not a diagnostic page.

Candidates, cheapest first:
- A long press on the map's compass (`OverlayIds.COMPASS`) opens it: no new control, and the compass is already
  the "which way am I facing" control; but a long press is hidden until someone tells you.
- An entry in the Menu of Layout C's top row (the owner's answer of 2026-11-11, built in T239's follow-ups).
- A chip of its own on the map: most visible, but it takes room the layout authority (`shared/.../ui/OverlayLayout.kt`,
  the yield order) gives to the cards and the readout, and at font scale 2.0 something else hides.
- The readout's accuracy or fix-age entry, tapped (the glance view's tap was cut from T307 because it closes on any
  tap; the strip itself has a child-tap precedent, T197's pin mark).

Also its name: "GPS status" reads as a tool; "Sky" or "Sky and GPS" fits what it shows.

2026-11-19, the owner on the name and the shape: "I'd call it the horizon view, as opposed to the map view. Horizon
view can show peaks/summits, GPS satellites in the sky, or...other things I haven't thought of...up in the sky with
toggle buttons." So: the **Horizon view**, the map view's counterpart (a switch between the two, not a tool page),
with layers turned on and off by toggle buttons over one sky plot and its skyline. GPS status becomes the
satellites layer plus its summary and check. Layers that fit, from what the app already has or can have offline:

- Satellites (T307), with the summary and Check my GPS.
- Peaks and summits on the skyline (`2026-11-19-peak-names-on-the-sky-plots-skyline.md`; `Viewshed.peaks`).
- The sun now and today's path, with when it goes behind the ridge and returns (`2026-11-19-todays-sun-path-across-the-skyline.md`).
- The moon, its path and phase (the same astronomy as `nav/Sun.kt`, published formulas).
- Your own places in view: waypoints, huts, the car, the route's next pass or the end, each at its bearing with its
  distance; the way back to the start.
- Compass bearings: a bearing to a chosen point, the declination (`nav/Wmm.kt`).
- Bright stars and planets at night (a public-domain bright-star catalogue, a few hundred KB, offline).
- Later, with data the app does not hold yet: satellite passes such as the space station (orbits, as the coverage
  forecast idea needs), and the camera behind it all (stage 3 of `2026-10-29-viewshed-and-peak-labels.md`).

## Open

- Benchmark first (ADR 0039): how apps switch between a map and a sky or peak view, where the switch sits, and how
  they present layer toggles there.
- The point it is drawn for: here by default, a long-pressed point (`2026-11-19-the-sky-from-a-long-pressed-point.md`).
