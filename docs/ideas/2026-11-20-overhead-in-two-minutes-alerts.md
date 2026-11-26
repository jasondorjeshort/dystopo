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

2026-11-26 (triage, taking in the coordinator's `New:` of that day): the measurement the earlier plan asked for first
exists (T365, `../findings/2026-11-26-the-error-of-a-guessed-flight-position-measured-from-two-recordings.md`,
`shared/.../aircraft/GuessError.kt` with `FlightPhase`): a 2-minute straight-line guess is 0.18 km off at the median
and 1.7 km at the 90th percentile for cruise, 1.3 and about 10 km for climbing, descending and terminal traffic.

Plan: one pass, the measurement done. (1) `shared/.../aircraft/` gains a pure closest-approach rule over
`AircraftTracker`'s moved-up positions: the time, the distance and the height above the phone at the closest point
within the lead time; the lead time and the alert distance set by `FlightPhase` from the finding's table (cruise up to
two minutes; climbing, descending and terminal traffic a shorter lead and a distance that covers its 90th percentile,
or no alert where the table says the guess cannot carry it), constants each with its reason citing the finding; one
alert per aircraft, none from a stale position, none for an aircraft on the ground. Its tests replay fixtures cut from
the recordings' shape (synthetic, no real flights). (2) The app shows one banner on the map while Flight
tracking is on and the app is open ("<flight> passes 1.2 km north in about 2 min"; a tap selects the flight), placed
by the layout rule (`shared/.../ui/OverlayLayout.kt`), one at a time; a synced setting turns it on, off by default
with a help sentence (ADR 0044: a setting, since near an airport it would never stop and in the hills it is the
point). `GET /aircraft` reports `approach` per aircraft and the banner. Verify: unit tests from fixtures; a replay
on the emulator with the banner viewed at the default text and at font scale 2.0; `requests` the same before and
after. Strings in the report (ADR 0031). Out of scope: notifications, any poll in the background, "low over a
nearby ridge" (needs the terrain along the path), the sun and moon crossing
(`2026-11-20-aircraft-crossing-the-sun-or-the-moon.md`).
