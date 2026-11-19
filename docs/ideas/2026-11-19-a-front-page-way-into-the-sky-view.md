---
date: 2026-11-19
kind: question
related: [2026-11-19-peak-names-on-the-sky-plots-skyline.md, 2026-11-19-todays-sun-path-across-the-skyline.md, 2026-11-19-the-sky-from-a-long-pressed-point.md]
---

# A way into the sky view from the map, not only from Settings

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

## Open

- Benchmark first (ADR 0039): where established topo and outdoor apps put a sun, sky or peak-finder view, and what
  they call it.
