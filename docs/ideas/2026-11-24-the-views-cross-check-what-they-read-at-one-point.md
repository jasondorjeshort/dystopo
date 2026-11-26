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

Plan: one pass, tools only, reading the existing routes (no new route). Checked at triage: `tools/two-maps-part.py`
already reads, at each of the fixture's rows with the network off and on, `GET /packs`, `GET /horizon`,
`GET /panorama` and the rest, and says of itself that "the rows are the reports' evidence, not a verdict". The pass
adds the verdict: a pure function in that file (or a module beside it) over a row's raw answers that names a
disagreement and the view that differs, for three rules: the Horizon's or the Panorama's terrain from the online
source while a map open at the place covers it; no peaks in a view where the map in use has peak names within the
range; the Panorama blank where the Horizon has terrain. Allowed and listed, not red: a coarser map ending sooner than
the online source; a view still working at its `?wait=`. A fault makes the part red and its line names the row and
the view. Tests in `tools/test_two_maps.py` from saved raw answers (a clean row, each rule tripped once). Verify: the
`two-maps` part on `main` (`tools/sweep.ps1 -Only two-maps`) green or red with each line read against the frames.
Out of scope: the public areas of `areas.json` (a later step), the List's route, the in-app check (its own idea).
