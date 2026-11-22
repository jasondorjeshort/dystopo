---
date: 2026-11-22
kind: feature
related: [2026-11-22-buzz-when-i-point-at-it.md]
---

# The map shows where the phone points: a line to the ground it aims at

Status: **untriaged** (an idea of the coordinator's own from wave 46's work, offered when the owner asked "Any ideas out of this wave? Moonshots welcome." and recorded on the owner's word, 2026-11-22: "Record them all; we'll triage and think through them later."). The anchors are files wave 46 wrote or read; nothing else here was checked against the code or measured.

On the Map view, a line from the fix along the phone's heading, ending where it meets the ground, with that point's distance and name if it has one. Two pieces of wave 46 make it: the attitude (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/PhoneAttitude.kt`: bearing and tilt of the back of the phone) and the ray that "Can I see it from here?" casts (`shared/src/commonMain/kotlin/io/github/jasondorjeshort/dystopo/horizon/SightLine.kt`, which follows `Viewshed.peaks`' test). Point at a far slope and the map marks the spot; long-press it from there.

It is the Panorama turned inside out: the Panorama names what is in the direction you look, this shows that direction on the map.

## Open

- Tilt matters: with the phone level the ray meets the horizon's ground; pointed down a valley it meets the valley floor. Whether to use the tilt or always take the skyline's ground on that bearing.
- The ray's range and cost per update (a kept horizon answers in a few milliseconds; a fresh ray took 256 to 765 ms on the emulator in T334).
- Where the toggle lives; whether the heading sensor's hold for the map's compass is enough or the attitude source is needed (it runs only for the Panorama in Follow today).
