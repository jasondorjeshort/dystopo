---
date: 2026-10-31
kind: feature
---

# The readout bar and the turn-back clock on a watch

On a hike the phone stays in the pocket and the glance goes to the wrist. A Wear OS companion showing the readout bar
(elevation and climb), the turn-back clock and its colours, the next water, and the storm arrow, fed by the phone
over the Wear data layer; a complication for the watch face with the clock's time; haptics for the margin and the
turn time so the phone's notification need not be heard. A moonshot: a new module, a second APK, the Wear tooling in
the emulator matrix, and a design that keeps `shared` pure. The first slice is one screen mirroring the readout
strip's JSON, which the device tour can drive through the same debug routes. Open: whether to start with a tile for
third-party watch faces instead of an app, and the battery cost on the watch.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Question: a watch companion is a new module (`wearApp/`), a second APK on the release path and a new data flow (the
phone pushing readings to the watch over the Wear data layer, a Google Play services dependency the app does not
have); do you want it, and is Wear OS the platform? Recommendation: yes eventually, but as a proposal first, because the
Wear data layer library is the first Play services dependency (its licence and the no-Play-services builds need an
answer) and the module changes the release and the emulator matrix. Start the proposal from the slice the idea names:
one tile (not a full app) showing the readout strip's JSON as the phone's `GET /readout` › `bar` already serves it
(`app/readout/LiveReadout.kt`) and the turn clock's `TurnClock.json` (`nav/TurnAround.kt`), plus a complication with the
turn-back time; haptics for the margin and the turn from the same `TurnAlerts`. The proposal's survey (ADR 0039) is how
the established hiking apps split phone and watch, and its measurement the watch's battery cost per hour.

Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
