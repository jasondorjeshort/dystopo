---
date: 2026-11-22
kind: feature
related: [2026-11-02-horizon-match-fix-from-the-camera.md, 2026-11-22-the-panorama-fills-the-screen-and-the-list-becomes-a-fourth-view.md, 2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md]
---

# Fix the compass and position from the camera, on the full-screen Panorama

Status: **untriaged and not sanity-checked** (an idea of the coordinator's own, offered when the owner asked for more and recorded on the owner's word, 2026-11-22). Nothing here has been read against the code or measured; the anchors and the list of what exists are from memory of the session's reading, to be checked at triage.

Match the camera's skyline to the terrain model to correct the heading (and, further, the position), with the full-screen Panorama as the screen that delivers it: the owner marks the skyline against the drawn one and the app offsets the heading, which is the Panorama's heading correction made automatic.

**What exists already (to be checked):** **Exists:** `2026-11-02-horizon-match-fix-from-the-camera.md`, and ADR 0064's camera-behind-the-drawing option; T331 (wave 46) adds the manual heading correction.
