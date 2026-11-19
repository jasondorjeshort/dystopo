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

2026-11-19, the survey of the switch (read-only, search snippets and store listings only, so low confidence): no app
found puts a circular sky plot beside the map as a top-level switch, and none combines the terrain's skyline, the sun,
peaks and satellites in one view. Apps built around a sky or panorama make it their full-screen home (PeakFinder,
GPSTest, the sun planners, Garmin's page ring); map-first apps open it as a tool (Locus Map's skyplot window from its
GPS button, PhotoPills' AR inside a section). PeakVisor is nearest: its panorama and 3D map are peers, and a
double-tap on its 3D compass opens the 3D map, a precedent for the compass as an entry. Layers in these views are a
settings sheet or a filter, not a row of chips. Most allow any chosen point, not only the user's. Its recommendation:
the switch at the bottom (the top row holds Search and Settings), a two-segment "Map | Horizon" control just above
the readout bar first and a chip by the compass second, with a tap on the compass as a shortcut; toggles named with
plain nouns ("Skyline", "Sun", "Peaks", "Satellites"), no "AR"; check that the toggles under a square plot do not
shrink it on a short phone. The owner, 2026-11-19: "Up top or down bottom are both possible but we may just need
trial and error."
