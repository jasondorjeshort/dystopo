---
date: 2026-11-02
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md, 2026-10-31-offline-three-d-terrain-view.md]
---

# A fix from the horizon: point the camera at the skyline and match it to the elevation model

T203 (wave 26) pins a position along the trails from the barometer and the pace; it fans out on flat
ground and knows nothing off the trail graph. The phone has another sensor the terrain can answer: the
camera. The viewshed code (T182, `shared/.../viewshed/`) already computes the skyline the DEM predicts
from a point in every direction; the reverse problem, "which point on the map sees this skyline", is a
search over candidates near the last fix (the terrain fix's particle cloud is a ready set) scoring the
camera frame's horizon line against each candidate's predicted skyline, with the compass giving the
azimuth and the accelerometer the pitch. One photo of the ridge line gives a fix where the GPS is gone,
on or off trail, and a second from a different bearing pins it; the barometer height constrains the
third axis. No network, no new data: the DEM pack and the sensors.

- **Pipeline:** the frame's horizon by a simple sky/ground edge (brightness and the sky's colour, pitch
  from the accelerometer), the DEM's skyline per candidate (T182's ray march, reused), a score (the edge
  curves' distance after aligning the azimuth), the best candidates fed back to the terrain fix's cloud.
- **Shown:** the camera view with the predicted skyline drawn over the real one as the user turns, so
  the match is visible; a tap takes the fix with its spread and a "from the horizon" source word.
- **Agent-testable:** a devtool command renders the DEM skyline at a known point and scores synthetic
  frames; a unit test on a rendered horizon; the phone's debug route takes a frame file.

## Open

- Canopy, fog and night defeat it; it must say so instead of guessing (a score threshold).
- Camera permission is a new flow for the owner (ADR 0031 flags it).
- Whether it lives in the terrain-fix source as one more measurement or as its own action.

Recorded 2026-11-02 at the close of wave 26 from the coordinator's list at the owner's invitation
("moonshots welcome"). Untriaged and not sanity checked: the premises have not been read against the code.
