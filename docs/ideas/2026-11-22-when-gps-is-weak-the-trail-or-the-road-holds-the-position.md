---
date: 2026-11-22
kind: feature
related: [2026-11-19-a-weak-gps-note-while-recording.md, 2026-11-19-gnss-signal-recorded-with-each-track-point.md, 2026-11-20-no-reroute-when-the-driver-leaves-the-planned-route.md]
---

# When GPS is weak, the trail or the road holds the position: dead reckoning on the known line

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

T333 notices when the GPS signal stays unusually weak (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/gnss/WeakSignalWatch.kt`) and says so; this is what the app could do about it. On foot: steps, the barometer's height and the trail being walked carry the position along the line through a bad stretch (a canyon, heavy forest), where today the fix wanders or stops.

**Driving, even more** (the owner, 2026-11-22, on this idea: it "applies even more to driving with dead reckoning"): a car stays on the road, its speed changes slowly and its turns show in the gyroscope, so through a tunnel or a deep valley the position can run along the road at the last known speed, corrected at each bend the sensors feel. Tunnels are already listed among the app's known limits in the release notes.

Anchors: the recorder and its fixes (`androidApp/.../app/tracking/Tracking.kt`), navigation's projection onto the route (`NavPath.project`, named in the board's Later for T163), the attitude source of wave 46 (`androidApp/.../app/nav/NavDevice.kt`) for turns.

## Open

- A design with a research pass first (ADR 0044): how car navigation and pedometer dead reckoning are done in published work, what error to expect per minute, and when to give the line back to the GPS.
- It must be visible: a reckoned position drawn differently from a fix (the puck hollow, the track dashed), and recorded as such in the track, never passed off as GPS.
- Off the known line (no route, no trail under the user) there is nothing to hold onto: say so and do nothing.
- The driving half may be the first to build: one dimension (distance along the road), a clear start and end (a tunnel's portals are in the map data).

Question: take this as a research pass then a proposal (dead reckoning along a known line, driving first: distance along the road through a tunnel from the last speed and the gyroscope's turns, `NavPath.project` for the line), rather than any build now?
Recommendation: yes, research then a proposal, driving first; it changes what position the app shows and records, so the reckoned position's look (hollow puck, dashed track), its record in the track and when GPS takes back over want one decision, and the published error per minute decides whether it is worth it. The walking half (steps, barometer, trail) follows on the driving numbers.
Options:
a) Research, then a proposal, driving first (recommended).
b) Research, then a proposal covering walking and driving together.
c) Rest it until routing's next work.
d) Not worth building.

Answer:
