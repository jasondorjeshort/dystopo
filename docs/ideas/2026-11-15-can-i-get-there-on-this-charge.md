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

2026-11-16, the owner (who took it for recorded already; it is this file): yes, and "It implies a new driving mode
though "electric/hybrid"". So the vehicle is a choice the user makes beside Plan drives for, not a hidden model. A
hybrid is the nearer half: the fuel model with braking and descent earning some energy back, where
`routing/FuelModel.kt` and the speed profile give braking nothing; a battery car is the full one. The
coordinator's addition, a moonshot on top: the ground in reach drawn on the map as an area, there and back on this
charge, since the elevation model is what the project has that a road atlas has not. The question below (a vehicle
to check against) still stands.

Question: do you have an electric car or an e-bike to check this against, and is it worth a proposal now?
Recommended: at rest until the speed profile (`2026-11-15-a-drive-is-a-speed-profile.md`) has landed, then a
proposal, car first. The survey of 2026-11-15: the search side is known (Eisner, Funke and Storandt, 2011: a
potential from elevation makes regeneration's negative edges non-negative, and the battery's full and empty limits
become a simulation along the found path), so `routing/FuelModel.kt`'s clamp is replaceable. The doubt is the
figure: products ask for the vehicle (mass, drag, reference consumption) and show a charge on arrival; an e-bike
tool can only give a figure per assist mode, since the rider's power is unknown, and its makers call it a rough
guide. An honest band is the survey's guess, about 15 percent for a car and 25 to 35 for an e-bike, measured by
nobody here, and a wrong "you will make it" strands someone on a pass. It needs real trips to check against
before it is shown, which is why the first question is whether you have the vehicle.

Answer:
