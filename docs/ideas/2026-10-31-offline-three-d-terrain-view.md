---
date: 2026-10-31
kind: feature
related: [2026-10-29-viewshed-and-peak-labels.md]
---

# An offline three-dimensional terrain view from the pack

The DEM pack is a terrain mesh waiting to happen. MapLibre renders terrain from a raster-dem source and the app
already serves Terrarium tiles from the pack through the loopback server, so a tilted, draped view of the valley
ahead, with the route, the shade and the viewshed on it, may be a style change and a camera pitch rather than a new
renderer. A moonshot in polish rather than plumbing: the hillshade and contours must still read on the slopes, labels
must not swim, and the phone's cost at z14 with terrain on needs measuring. The first slice is a "3D" chip that tilts
the camera over the pack's DEM at one exaggeration, driven by a debug route, with screenshots at Paradise. Open:
MapLibre Native's terrain support on the pinned version; the exaggeration as a setting or one value.

Recorded 2026-10-31 at the close of wave 23 from the coordinator's list, moonshots included at the owner's invitation.

Question: the idea's premise does not hold on the pinned renderer: MapLibre Native for Android 11.13.5
(`gradle/libs.versions.toml`) has a `RasterDemSource` and a `HillshadeLayer` but no terrain (no `terrain` style
property or Java API in its classes, and no terrain strings in its native library, checked 2026-10-31), so a tilt over
the DEM is not a style change. Wait for upstream terrain in MapLibre Native, or build a view of our own? Recommendation:
wait (a `Waits:` of yours on MapLibre Native shipping terrain on Android), and at that release the first slice is as the
idea says (the "3D" chip, one exaggeration, a debug route, screenshots at Paradise). Our own renderer (an OpenGL mesh
from the pack's Terrarium tiles with the map drawn as its texture) is a new rendering engine, which CLAUDE.md ›
Decisions sends through a proposal, and a large one. A cheap middle step if you want something sooner: a still
perspective image of the view ahead rendered in `shared` from the DEM and the hillshade (no interaction), useful for
the trip report page, not a map mode.

Answer: as recommended (owner, 2026-11-11: "use recommendations" for every older open Question).
