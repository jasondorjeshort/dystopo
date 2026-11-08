---
date: 2026-11-07
kind: feature
related: [2026-11-07-the-whole-world-on-one-screen.md]
---

# "Crow" becomes "Line", follows the geodesic, and works anywhere, the open ocean included

The owner, after a third flight on 2026-11-07: "Crow routing should probably just be called line. It's really
helpful as a measurement. Except this may not be using great circles and correct routing? Unclear but we'd like it
to work at long distances. The algorithm is simple enough even accounting for the ellipse of the earth. If not done
already let's research and throw this in at some point. Line routing should work into the middle of the ocean; no
limitations needed."

What the code does: `shared`'s `routing/Crow.kt` plans the leg through `RouteEditor.straight` between the two
points and measures it with `TrackStats` (haversine between samples), with a bearing and a plain-language
description ("in a straight line, bearing N°"). Whether `straight` samples along the great circle or along a
straight line in latitude and longitude (a rhumb-like line on the Mercator map, far from the shortest path over a
long leg) is the first thing to read; and whether a plan is refused outside a pack's or the terrain's coverage,
which an ocean leg has none of.

## Open

- The geodesic on the ellipsoid (Karney's method, or Vincenty's) for the distance, the initial bearing and the
  sampled points, in `shared` with a test against known pairs (a few thousand kilometres, antipodal-ish, across the
  antimeridian); the owner's "ellipse of the earth" is the ellipsoid.
- The mode's label "Line" where the user reads it (the planning card's mode chips, the description's "in a straight
  line" kept), the stored mode value unchanged (ADR 0033); the strings listed.
- No coverage requirement: a line with no terrain under it plans with no profile and says so, over the ocean
  included.
