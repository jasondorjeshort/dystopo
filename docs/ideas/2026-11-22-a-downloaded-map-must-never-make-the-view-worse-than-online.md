---
date: 2026-11-22
kind: feature
related: [2026-11-22-sky-from-here-in-colorado-says-no-elevation-data-with-a-pack-and-the-internet.md, 2026-11-22-a-much-smaller-detail-level-the-whole-us-in-under-10-gb.md]
---

# A downloaded map must never make the view worse than the online one: use the finest source at each point

From the owner (2026-11-22, v0.1.41-alpha, with the Offline maps screen showing the "US" pack's outline over the
eastern half of the country, a Chicago area inside it and a Colorado pack at its western edge): "we might want to fall
back to online sources when available if they're higher res than our offline pack. Having our view be downgraded by
downloading data isn't good."

**The rule.** Having downloaded a pack must never leave the view worse than not having it. At each point and zoom the
app picks the **finest source that can serve it**: among the open packs and areas, then the online source when the
network is on (Mobile data setting, ADR 0023: no Wi-Fi gates, data use steered by visible limits), the one with the
highest resolution there, and steps down only when the finer one is out of reach (offline, no signal, out of
budget). This is the same ordered lookup as the overlapping-packs rule in the idea above; this idea adds the online
source as a member of it, not only the last resort.

**Where it bites now (to be confirmed).**
- The owner's US pack is Medium: vector to z14 but **elevation only to z13**; the online elevation source is read to a
  deeper zoom (the area downloads cap it with `demMaxZoom`, `app/areas/AreaDownload.kt`, ADR 0047). At the zooms where
  the pack's elevation runs out, the contours and the hillshade built from it are coarser than the online ones would be,
  so a downloaded map can look worse than the same place browsed online. Where the pack answers and the online source is
  never asked, the view is downgraded by the download.
- Where the pack's vector top zoom (z14) is below what a view shows, the map overzooms the pack's tiles; the online
  vector source may go no deeper, in which case there is nothing to gain; to be read from `sources.json`.
- The same applies to the horizon and the shade: the terrain read for a chosen point (the Sky-from-here bug above) should
  take the finest terrain there, not the first pack that covers the point.
- A pack's tiles and the **online store** (ADR 0060: every downloaded tile, the kept ones of offline maps in the budget
  among them) share one place, so "which answers" is a lookup over what is on disk plus what the network would fetch.

**What to check before choosing.**
1. How `TileService` and `OnlineService` choose today between a region or area and the online source for a tile and for
   an elevation tile (`app/TileService.kt`, `app/online/OnlineService.kt`, `app/online/DirectSources.kt`): first match
   wins, or best resolution?
2. Whether a mixed view (some tiles from the pack, a zoomed-in sliver from the network) flickers or seams at the pack's
   edge; the feathering of coverage edges (`Composite.over`, the project's conventions) is for elevation edges, not a
   change of resolution inside one view.
3. The data cost: the network is only asked for what is finer than the pack, so a view inside a Medium pack costs data
   only at the deepest zooms; the visible limits and the "Counts in the budget" line decide, and the setting's default.
4. Whether the user wants the choice: researched practice (ADR 0044: a standing order when users or apps split) is
   whether the established offline apps prefer downloaded or online data when both exist; a setting such as "Use the
   network for more detail" is the researched answer if they split.

**What more we would want to see.**
- A side-by-side: the same view at the deepest zoom inside the US pack with the network on and with it off, and the
  same view browsed online with no pack. If the online one is finer in the contours, the hillshade or the labels, the
  fault is confirmed and the measured gain is the size of the gain.
- Which sources the phone's "Online" line names ("Online · from the world overview" in the screenshot is the map at a
  zoom below the packs' z8 floor, so it is not a sign of the fault) when zoomed in inside the pack.

**Also seen in the screenshot (recorded, not diagnosed).**
- The US pack's outline is a slanted polygon that covers part of Canada, Lake Michigan and the Atlantic: the "US" name
  is for a drawn box, not for the country (see the idea of the smaller detail level).
- A pack named "WB" (875 MB) has no say what it is; a name the owner gave, probably.

Question: when a view is at a zoom where the network could give more than the pack, does it fetch the finer tiles
automatically (counted in the budget), or only when the owner turns a setting on? Recommended: automatically while
the network is on and Mobile data is allowed, with a setting to hold it to the pack, since the owner's wording says a
download must never lower the view.

Answer: a setting, defaulting ON: the finer online tiles are fetched where the network can give more than the pack; a user who wants to save network data turns it off and accepts the lower detail (the owner, 2026-11-22, by multiple choice at triage).

2026-11-23 (T350): proposal `docs/design/proposals/0066-one-lookup-for-what-the-phone-holds-here.md` is under review; it confirms the fault in the code (a map's DEM answers every zoom below its first, so the online source is never asked inside it) and recommends the online source ahead of a map only where the map would synthesise or overzoom, under the setting `onlineDetail` ("More detail from online", default on).
