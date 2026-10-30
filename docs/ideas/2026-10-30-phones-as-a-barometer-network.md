---
date: 2026-10-30
kind: feature
related: [2026-10-29-group-positions-without-signal.md]
---

# Phones in a group as each other's weather stations

T173 fetches a public station's sea-level pressure when there is signal; in the backcountry there
is none and the nearest station may be far. A group of phones sharing positions over the
group-positions transport could share pressure readings too: each phone's barometer with its GNSS
height and the estimator's confidence, so the group cross-calibrates its altimeters and a phone with
no fix borrows a prior from one that has (`androidApp/.../app/readout/PressureSource.kt` would take
peers as a second source). Over hours, the group's readings form a local pressure history for the
storm-warning idea. Open: it waits on the transport proposal with key attestation that the
group-positions idea names; what a reading reveals about a peer's position (pressure plus height is
not a location, but a timestamped series is a pattern).

Question: should pressure readings be part of the group-positions payload when that transport is designed? It is a new data
flow between phones, the owner's to flag. Recommendation: yes, as an optional field of the position record in the one transport
proposal: the hPa, the GNSS height and the estimator's sigma, adding little to a position the group already sees. Build it
only after stage 1 of group positions and after the storm warning has run on real hikes. `PressureSource.kt` would then take
peers as a second prior beside the station, through the same `StationPressure`-style sigma by distance and age. Nothing is
buildable before the proposal.
Answer:
