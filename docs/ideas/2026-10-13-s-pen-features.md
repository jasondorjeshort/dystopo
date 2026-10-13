---
date: 2026-10-13
kind: feature
status: new
pipeline: none
---

# S Pen features (niche)

Eventually, give the Samsung S Pen some useful behaviour. The owner has one; this is a niche idea
for later, not a priority.

## Open

- Candidates to brainstorm: hover to preview a feature's name and elevation, button-and-tap to
  drop a waypoint, draw a route or an area directly on the map, air actions (remote button for
  next instruction or recentre), handwriting into the search box.
- What the platform offers without a proprietary SDK: stylus hover and button events are in the
  standard Android input API; the Samsung SDK is proprietary, so check its licence against the
  project's rule on dependencies before considering it (CLAUDE.md, Rules).
- Testing: the emulator can inject stylus events; hover cannot be checked on a real pen from here.
