---
date: 2026-11-16
kind: feature
related: [2026-11-14-drive-speed-falls-on-winding-roads.md, 2026-11-14-the-route-ahead-spoken.md]
---

# A warning before a sharp bend while driving

`routing/SpeedProfile.kt` (T291) caps a drive's speed at each bend from the bend's radius, so for the route being
driven it knows every bend whose comfortable speed is well under the speed of the road leading into it. Wanted
while navigating a drive: a spoken and shown warning ahead of such a bend ("sharp bend in 300 m"), with no signal,
which is where mountain roads are; `androidApp/.../app/nav/NavSpeech.kt` already speaks the turns. The owner,
2026-11-16: "Warning of bends would be a good feature." It differs from the winding-roads idea, which prices a
bend in the planned time, and from the spoken route ahead, which describes ground to a walker.

## Open

- Safety, flagged: silence must never read as "no bend ahead" (a road's line in the map can be coarse or wrong),
  and no speed is advised unless the survey supports one; the help text says what the warning is drawn from.
- Which bends: by the drop from the approach speed to the bend's cap, a hairpin by its angle, a run of bends
  announced once. The lateral limit behind the cap is not measured against real driving (the winding-roads idea,
  its line of 2026-11-16).
- A survey first (ADR 0039): how navigation and motorcycle apps warn of curves, what they say and how far ahead;
  a setting if they split (ADR 0044).
- Only on a planned route, or also when driving with no route (the road ahead guessed from the heading).
- The phone does not use the profile yet (T291 was devtool only).
