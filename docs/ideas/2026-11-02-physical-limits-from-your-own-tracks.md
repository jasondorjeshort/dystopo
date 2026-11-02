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
