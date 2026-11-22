---
date: 2026-11-21
kind: feature
related: [2026-11-21-one-clock-for-the-whole-app-past-and-future.md, 2026-11-20-the-moon-on-the-horizon-view.md, 2026-11-21-a-mountains-shadow-forecast.md, 2026-11-20-aircraft-crossing-the-sun-or-the-moon.md]
---

# When the sun or the moon stands on a named peak, seen from a chosen place

From a place, a peak sits at one bearing and one angle above level (`Viewshed.peaks`). The sun passes through that
spot of the sky on a few days of the year, at one minute of each. The app can find them: "From Paradise, the sun rises
over Mount Rainier's summit on 3 February and 8 November, about 7:40 AM" (the dates here are made up). The same for
the moon once it has a position model, where a full moon rising behind a peak is the picture photographers plan for.

The reverse question uses the same geometry: for a date and a minute, the line on the map along which the sun stands
on the peak, which is where to put the tripod.

What exists: the sun's position at any instant and its refraction near the horizon (`nav/Sun.kt`, `position`,
`refractionDeg`), its path against the terrain's rim (`Sun.onTerrain`, `horizon/SunPath.kt`), the peak's bearing and
angle. A year at one-minute steps is about half a million positions, so the search can be plain.

Differs from the one-clock idea: that one sets a time and shows the sky then; this one finds the time, and its result
is a jump on that clock. The shadow forecast is a neighbour (its "where to stand" is the far side of the same peak),
and the aircraft-crossing idea is the same alignment with a moving target.

Recorded 2026-11-21 from the coordinator's list after wave 44, on the owner's "we can record them all". Untriaged.

## Open

- How close counts: the sun's disc is half a degree wide; "on the summit", "within the notch", or anywhere on a ridge.
- The moon half waits on the Moon layer's Plan (`nav/Moon.kt`), and the moon's dates do not repeat by the year.
- Where it is asked: a row on a picked peak in the Horizon view ("When is the sun here?"), next to "View from here".
- Weather is unknown offline: it says when the alignment happens, not that it will be seen.
