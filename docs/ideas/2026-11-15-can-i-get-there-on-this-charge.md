---
date: 2026-11-15
kind: feature
---

# Energy routing beyond fuel: can I get there and back on this charge

`routing/FuelModel.kt` (ADR 0063) already prices climb and credits descent for one model car. With a battery
capacity and regeneration in place of the capped descent credit, the same search answers offline what is hardest
to guess in the mountains: whether a car or an e-bike reaches the pass and returns on its charge, with the charge
left at the far end on the card. The owner, 2026-11-15: "Ooh cool."

## Open

- A moonshot: the model needs a vehicle (mass, battery, efficiency) the user enters, and an honest error band.
- Negative edge costs under regeneration: the search needs the standard potential shift; research first (ADR 0044).
- E-bike first may be the nearer case (rider power is the unknown).
