---
date: 2026-11-20
kind: feature
related: [2026-11-20-aircraft-paths-on-the-map-and-dots-in-the-sky.md, ../findings/2026-11-20-aircraft-layer-sources-and-display.md]
---

# "Overhead in 2 minutes": an alert when a flight will pass close or low

What: project each aircraft's track forward (position, ground speed, track, vertical rate) and notify when one will
pass within a set distance of the phone, or low over a nearby ridge, in the next few minutes. The alert names the
flight and the bearing it comes from.

Why it suits Dystopo: the sky views already compute bearing and elevation angle of every aircraft; the prediction is
the same arithmetic moved forward in time. Useful for photographers and spotters outdoors, and for noticing a
helicopter coming toward a hiker.

Data: the live positions only (adsb.lol, ODbL); no new source. The cost is the poll: an alert needs the layer running
in the background, but the plan forbids background polling to protect the 500-requests-a-day quota and the battery.

## Open
- Whether alerts may poll in the background at all, and at what interval; a foreground-only version is cheap.
- A notification is a new permission and flow, so the owner decides it.
- How accurate a straight-line projection is over two minutes (turns); the replay source can measure it.

Source: The owner asked for these to be written up on 2026-11-20 from the coordinator's review of the flights plan.

2026-11-21, the owner, by multiple choice, asked whether the app may alert when a flight will pass close or low, and
if so only while the app is open (an in-app banner, no new permission) or also in the background (notifications and
background polling, against the rule that protects the 500-a-day quota and the battery): the banner while the app is
open (recommended, as one task after the replay feed measures how far a two-minute straight-line projection drifts);
no background alerts.

Plan: one pass, the measurement first. (1) `devtool aircraft` gains a report over a replay file: each aircraft's
position projected ahead in a straight line from its speed, track and vertical rate, against where the replay shows
it one and two minutes later, as the median and the 90th percentile miss, level, climbing and turning apart. (2)
`shared/.../aircraft/` gains a pure closest-approach rule over `AircraftTracker`'s moved-up positions: the time, the
distance and the height above the phone at the closest point within two minutes; one alert per aircraft when the
pass comes inside a distance or under a height that step 1's figures can carry (constants, each with its reason);
none from a stale position, none for an aircraft on the ground. (3) The app shows one banner on the map while Flight
tracking is on and the app is open ("<flight> passes 1.2 km north in about 2 min"; a tap selects the flight), placed
by the layout rule (`shared/.../ui/OverlayLayout.kt`), one at a time; a synced setting turns it on, off by default
with a help sentence (ADR 0044: a setting, since near an airport it would never stop and in the hills it is the
point). `GET /aircraft` reports `approach` per aircraft and the banner. Verify: unit tests from fixtures; a replay
on the emulator with the banner viewed at the default text and at font scale 2.0; `requests` the same before and
after. Strings in the report (ADR 0031). Out of scope: notifications, any poll in the background, "low over a
nearby ridge" (needs the terrain along the path), the sun and moon crossing
(`2026-11-20-aircraft-crossing-the-sun-or-the-moon.md`).
