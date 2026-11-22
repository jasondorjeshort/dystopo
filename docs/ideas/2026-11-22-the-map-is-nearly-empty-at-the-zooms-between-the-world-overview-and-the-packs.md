---
date: 2026-11-22
kind: bug
related: [2026-11-22-a-downloaded-map-must-never-make-the-view-worse-than-online.md, 2026-11-22-sky-from-here-in-colorado-says-no-elevation-data-with-a-pack-and-the-internet.md]
---

# The map is nearly empty at the zooms between the world overview and the packs, and bare of relief above them

From the owner (2026-11-22, v0.1.41-alpha, default map settings, Colorado, the same phone as the pack reports above;
"maybe related"). Two frames of the same area:
1. **Scale bar 50 mi, chip "Online · from the world overview":** only four city names (Denver, Colorado Springs, Santa
   Fe, Albuquerque) and a lake fragment on a flat cream ground. No roads, no state line, no rivers, no relief.
2. **Scale bar 20 mi, chip "Online · on this phone":** main roads, rivers, park outlines, the state line and about six
   city names, still with large empty areas and **no relief of any kind** (no hillshade, no contours, no land-cover
   tint) across the Colorado Rockies and the Sangre de Cristo, though the attribution line names "Elevation:
   Copernicus, USGS". The elevation readout in frame 2 shows dashes (no fix) against 601 ft in frame 1; a separate
   matter, noted for the log only.

**What the code and the docs say (read, not yet run).**
- The bundled world overview is **z0 to z5 only** and Natural Earth only: water, country borders (state lines z4),
  and cities; **"No roads: they start at z4 from OSM for little gain"** (`tilebuild/PROFILE.md` › World overview;
  `docs/reference/android-app.md`, "The world overview (T264)": the chip reads "from the world overview" when no terrain
  does). A view at a 50 mi bar is about z6 to z7, **above** the overview's top zoom, so frame 1 is the overview
  overzoomed one or two levels, which is why it holds nothing more than cities.
- The owner's packs (the US and Colorado maps) start at **z8**. The z6 and z7 views therefore lie in a gap: below every
  pack, above the overview. The online source (OpenFreeMap, which has tiles at every zoom) is what the chip's "Online"
  suggests but, at these zooms, the view is the overview. If the online vector source were asked at z6 and z7 the roads
  (motorway from z4, trunk z5, primary z7), rivers (z8) and land cover (z7 glaciers and rock, z8 the rest) would show.
- The profile **thins roads by zoom** (secondary z9, tertiary z11, minor z12, trails z10) and rivers start at z8, so
  frame 2 at about z8.5 is sparse **by design** of the profile: only motorway, trunk, primary and some rivers.
- Relief is a **separate layer**: the Hillshade setting (Off, Single, Multi) and the contours
  (`androidApp/.../options/MapOptionsSection.kt`); frame 2's missing relief means either the default is Off or the layer
  has a minimum zoom above the view, or it has no elevation to draw from at that zoom. The pack's elevation covers z8 to
  z13 (the card's "Zooms z8-14, elevation to z13"), so z8 is its first level.

**The two possible faults.**
- **A gap:** z6 and z7 are served by neither a pack nor the online vector source, but by the overview alone, although
  the phone is online. The fix would be the finest-source rule of the idea above (use the online source where nothing
  finer is on the phone), plus ending the overview at z5 only where the online source is absent.
- **A bare default:** at z8 to z10 the roads and labels are sparse by profile and relief is off or absent, so a big
  mountain region reads empty. That is a profile and style choice (the layers' minimum zooms, the labels' density, the
  Hillshade default) for the owner's eye, not a defect in the data.

**Two requests from the owner (2026-11-22), now ideas of their own:** a zoom label by the scale bar as an option
(`2026-11-22-a-zoom-label-by-the-scale-bar-as-an-option.md`) and turning an offline map off without deleting it
(`2026-11-22-turn-an-offline-map-off-without-deleting-it.md`; item 2 below needs it).

**What more we would want to see.**
1. The two zoom levels as numbers (the debug build's `/stats`, or a camera read-out) and the chip's text at each, so the
   gap's edges are known: where the chip switches from the overview to "on this phone" or the online source.
2. The same view with **no pack selected** (or with the packs removed) and the network on, to see whether the online
   vector source fills z6 and z7 by itself and whether the pack is what hides it.
3. Settings: the Hillshade and Contours rows as set (Off, Single, Multi), and whether turning Hillshade on at frame 2's
   zoom draws relief; if it does, the default is the question, not the data.
4. A third frame at z10 and one at z12 of the same area, to see where the map becomes full, and what the owner would
   want at z7 to z9: more roads, relief, land cover, place names, parks, or state lines.

Possible plan (the coordinator's suggestion, not triaged): reproduce first on the emulator with the same two packs and the network on (`GET /stats`, `/features` at each
zoom), then decide between a gap fix (the online source serves what no pack or overview can) and a profile or style
change (the owner's eye, ADR 0031) as one task.
