---
date: 2026-11-21
kind: feature
related: [2026-11-21-one-clock-for-the-whole-app-past-and-future.md, 2026-11-21-a-viewpoint-finder-where-a-peak-can-be-seen-from.md]
---

# A mountain's shadow forecast: when a peak's shadow falls across the sky, and where to stand to see it

At sunrise and sunset a high peak casts a shadow across haze and cloud, a dark wedge stretching from the summit toward
the antisolar point (Mount Rainier's is well known). From the elevation model and the sun's position (`nav/Sun.kt`) the
app can say when a peak's shadow will show (the sun within a few degrees of the horizon, behind the peak as seen from
the observer), in which direction it points, and from where it can be seen (on the sun's side of the peak, with a clear
line toward the antisolar point).

The owner, 2026-11-21: "Very cool; lets do it. Is this another shading layer on the map?" Answered in the chat: not
mainly. The map already shades the ground from the sun (T188); this shadow is cast into the air, so it is best drawn in
the Horizon and Panorama views (a wedge from the peak toward the antisolar point, at the set time). On the map it is a
"where to stand" area or line plus the times, and the existing shade layer at that minute shows the shadow on the
ground.

## Open

- Visibility needs haze or cloud the app cannot know offline: the forecast says when and where it would show, not that
  it will.
- Which peaks: the dominant summits by prominence within range (T309's ranking), not every bump.
- With the one-clock idea, the forecast is "the next time this peak's shadow shows from here" and a jump to that time.

Waits: the one clock built; then one pass, low priority, with the read-only pass on where a summit's shadow can be seen from first (the owner, 2026-11-22, by multiple choice at triage).
