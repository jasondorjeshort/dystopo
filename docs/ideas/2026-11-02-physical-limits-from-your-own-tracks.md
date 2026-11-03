---
date: 2026-11-02
kind: feature
related: [2026-10-30-pace-and-climb-zones-with-a-tone.md]
---

# The readout's physical limits come from your own tracks, not constants

T204 (wave 26) caps a barometric climb at 3 m/s on foot and 5 by bike, figures the project set itself
because no survey source gave any; T172's accuracy bounds and T158's activity profiles are constants too.
The phone holds the user's tracks with their climb rates, speeds and accuracies per activity (T164 already
fits route times to them). Fit the caps the same way: the 99th percentile of the user's own climb rate and
speed per activity, with the constants as the floor until enough tracks exist, so a trail runner's readout
does not re-anchor on a real sprint and a walker's rejects a sensor glitch sooner. The same per-user
limits feed the recorder's flagged-fix rule and the terrain estimate's stride prior.

## Open

- A setting ("Limits from my tracks", on) or always on (ADR 0044: one survey).
- Shown in Diagnostics with the fitted figures and the track count behind them.

Recorded 2026-11-02 at the close of wave 26 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.

2026-11-02 (triage): a stored track carries position, GPS or fused elevation, time, accuracy, Doppler speed,
course and flags per point (`model/UserData.kt`); no barometric column (the recorder writes `fix.ele`, and
only the replay reads a pressure column). The caps are per activity in `readout/ReadoutTuning.kt` (foot 3,
bike 5, road 10, air 100 m/s). So a climb-rate percentile from the store would measure GPS height noise,
not the barometric rate the cap guards; the speed bound could be fitted from the stored speed today, but
the stored points are already filtered by the current bounds (points over them are flagged).

Question: should the recorder store the barometer's pressure with each point (a format change phones
sync, ADR 0033, a few bytes a point), which is what a fitted climb cap needs? Recommendation: yes, as the
first pass of this idea and the prerequisite of the self-test idea's honesty too; the fitting (the 99th
percentile per activity with the constants as the floor, shown in Diagnostics with the track count) as the
second pass, always on, no setting: a limit from your own data has no wrong default to escape.
Answer:
