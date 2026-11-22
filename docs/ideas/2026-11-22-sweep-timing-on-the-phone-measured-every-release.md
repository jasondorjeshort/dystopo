---
date: 2026-11-22
kind: chore
related: [2026-11-21-the-horizon-view-moves-with-you.md, 2026-11-21-a-viewpoint-finder-where-a-peak-can-be-seen-from.md]
---

# The sweep's time on a real phone, measured every release

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for new ideas after the triage of 2026-11-22, and recorded on the owner's word). Anchors are from the session's reading, to be checked at triage.

At least four ideas wait on "the sweep's time per point on a real phone" (the horizon's smoothing and its thresholds, the best-view ranking, the Panorama's far layer), and `docs/` records none. A debug route that times the near and far layers of `LayeredSkyline.sweep` at a fixed public point (cold and warm), and the same figures in Send diagnostics and the startup history, so each device and release reports them and the number is read, not guessed at each triage.

**What exists already (to be checked):** The Panorama counts sweeps and times per place (`PanoramaData`, `Sweep`); T317's report has one time per point; no standing measurement or diagnostics field.
