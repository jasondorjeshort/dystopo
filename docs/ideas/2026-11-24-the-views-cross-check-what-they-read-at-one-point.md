---
date: 2026-11-24
kind: chore
related: [2026-11-07-search-and-routes-find-nothing-outside-a-pack.md, 2026-11-22-a-why-is-this-blank-overlay.md, 2026-11-24-the-app-notices-when-its-views-disagree.md]
---

# The views cross-check what they read at one point

Source: coordinator (claude-opus-5-5), after the ideas triage of 2026-11-24; offered and accepted by the owner; not sanity-checked.

The blank Panorama and the missing Horizon peaks at the phone's own place were found by the owner's eye, not by the
sweep, and persist on v0.1.43-alpha. Each view already reports what it read on its own debug route (`GET /packs`,
`GET /horizon` `peaks`, `GET /panorama`, the List's route), but nothing compares them.

The idea: one debug route (or a sweep step in the `features-sky` part of `tools/sweep.ps1`) asks the map, the Horizon
view, the Panorama and the List what they read at one point: the terrain source and depth, the peak count and the
maps that gave them, the map in use. A disagreement (terrain from the online source while a map covers the point;
peaks none where the map has peak names; the Panorama blank where the Horizon has terrain) fails the step and names
which view differs. Run at the two-maps fixture's rows (T352) and at the public areas of `areas.json`. Lever: it
catches a blank, where the "why is this blank?" overlay only explains one; ADR 0066's prediction (no report of two
views disagreeing) gets a check that runs every sweep.

## Open

- A route of its own, or a step reading the existing routes.
- Which disagreements are faults and which are allowed (a coarser map ends sooner than the online source).
