---
date: 2026-11-22
kind: feature
related: [2026-11-21-when-the-sun-or-moon-stands-on-a-named-peak.md, 2026-11-21-a-mountains-shadow-forecast.md, 2026-11-21-a-viewpoint-finder-where-a-peak-can-be-seen-from.md]
---

# "Where to stand" as a map tint for any sky event

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

Pick a peak and an event (the moon rising behind it, the sun on its summit, its shadow) and the map tints the band of ground where that happens at the clock's time: the line-of-sight function with a bearing and elevation filter from the body's position. One screen for three ideas' "where to stand".

**What exists already (to be checked):** The viewshed overlay draws a tint; `nav/Moon.kt` and the sun model exist; the one clock does not yet.

Question: a map tint of where to stand for a sky event over a peak needs the one clock (its proposal is pending), the
moon (`nav/Moon.kt`), the sun-on-peak search (the Plan of `2026-11-21-when-the-sun-or-moon-stands-on-a-named-peak.md`)
and the line-of-sight primitive's `toward` call; is it wanted as one screen for all three events, and when?
Recommendation: yes, after the clock and the primitive land, starting with the sun on a peak (its search is already
planned) as that idea's second step; the moon and the shadow follow on the same tint.
Options:
A. After the clock and the primitive, sun first, following the sun-on-peak pass (recommended)
B. Now, at the current time only, without the clock
C. Fold it into the sun-or-moon idea as a `Use:` line on the primitive's file and close this one
D. Not wanted

Answer:
