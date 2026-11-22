---
date: 2026-11-22
kind: bug
related: [2026-11-20-horizon-view-from-another-place-and-saying-where-it-is.md]
---

# "Sky from here" at a far-away place: a white view, then "no elevation data", with the pack present and the internet on

From the owner (2026-11-22, v0.1.41-alpha; reported as "packs either not registering or not communicating"). Not
recorded as a fix; recorded to be reproduced.

**What was seen.** The owner is not in Colorado; the Colorado offline pack should not matter at launch. They look at a
Colorado peak on the map, choose "Sky from here" (the long-press card's action, T317; not the peak's own "View from
here", T321), and the view is **white**; the Panorama view then gives the reason, a no-elevation-data text
(`GnssText.NO_TERRAIN_HERE`, "No elevation data at this point, so no terrain horizon: the flat horizon is used", or
`NO_TERRAIN`, "no offline map, no online source"). Two things the owner says are wrong: **elevation data exists there**
(the Colorado pack holds it), and **the phone is online**, so the app should fall through to the network source when the
offline one has nothing. Also: the message appears in the Panorama view only, after a white Horizon view first.

**Screenshots (the owner, 2026-11-22, all v0.1.41-alpha; two frames of the same selected place, 1583 mi W of the
phone, "Back to here" offered).**
- **Panorama view:** the lens is empty (a plain sky gradient, no skyline), with the text "No elevation data here:
  download an offline map around this point first." drawn in the plot and again under the heading (that wording is
  `ViewshedText.NO_DEM`, a viewshed string, not the Horizon view's `GnssText` ones: the Panorama reuses a message
  written for a different feature). Peaks stays on "Finding the peaks in view..." and does not finish; the Sun section
  works ("Now 15° up toward SE", the curve, sunset 18:46) and says "a flat horizon".
- **Horizon view, same place, same moment:** it is **not** white. It says "Terrain: highest -1° toward NNW, 10.3 mi
  away; from the online." and "No offline map with peak names here, so no peaks." So the Horizon view **did** get
  elevation from the online source, and the Panorama of the same place did not: the two views read terrain by different
  paths, which is the "not communicating". (The white view of the first report may have been the Panorama's empty lens.)
- **The online terrain looks wrong:** a Colorado peak's horizon should rise well above 0°; "highest -1°" is what a
  point with no real terrain around it reads (sea level, or a tile that came back empty or the wrong tile). Whether the
  point is the peak's own place or a spot elsewhere, a -1° maximum within 10 mi needs explaining.
- **Peaks:** "No offline map with peak names here, so no peaks" is true only for the offline pack; the Colorado pack
  the owner holds should have named them if the point lay in it, which it may not (the point's distance from the pack's
  edge is item 4 below). If the point is inside the pack, the pack and the Horizon view are not talking either.

**Where to look first (not yet verified).**
- The two views' terrain paths: why Horizon gets "from the online" and Panorama reports no data. Both should share one
  terrain read for a chosen point.
- The terrain reader for a point other than the phone's: `Sky from here` and `View from here` build a `HorizonProfile`
  at a chosen point (`app/gnss/`, the point source of T317). Does it ask the same elevation chain as the map (open
  regions, then areas, then the online source, as `ShadeService.TERRAIN_CACHE` and `MapCoverage` in
  `app/search/PlaceSearch.kt` do), or only the regions open when the view was made?
- A negative cache: `ShadeService` remembers a point with no elevation data "as such". Was the point asked for while the
  Colorado pack was not yet open (T328's note: a second pack opens after each update by `BundledAssets.reloadWhenUp`),
  so the "none" stuck for the session?
- The packs' registration: is the Colorado pack listed by `GET /startup` or `GET /firstrun` while the view fails? A pack
  that is on disk but not registered would match "not registering"; one registered but not consulted for a far point
  would match "not communicating".
- The online fall-through: the horizon from another place needs elevation tiles around a point that can be 100 km wide;
  does the online source cover that ring, or only the visible map window? The white screen (no message at all in the
  Horizon view) is a second defect beside the wrong message.

**The owner's answers (2026-11-22).**
- **Cold restart:** twice in a row it did not clear the fault. The Horizon view shows things after a cold start, but the
  Panorama still gives the no-elevation message, and this includes a cold start straight after updating to v0.1.41. So
  it is **not** a stale "no data" remembered from an earlier session; it is the Panorama's own read, repeatably. Whether
  the Panorama ever draws terrain at a distant place (a place with the phone's own pack, or an online-only place) is
  untested.
- **Online source:** Colorado is drawn on the map, so the network works for tiles. The owner has never seen an "online
  sources" switch; the setting they know is **Mobile data**, which is on. (Correction to the question above: there is
  no such switch to look for; the question is which setting or mode gates the online elevation read, and whether the
  Panorama honours the same one as the Horizon view.)
- **Distance and pack:** the place is 20+ miles inside or beyond the pack's edge (the owner's words: "20+ miles but
  technically the horizon in some directions is out of range"); the pack is multiple gigabytes, so it is large and the
  point is not near a small pack's edge. The horizon's far range can still reach past the pack in some directions.
- **Device:** USB debugging is available, so a log can be captured from the phone without a debug build.

**Two overlapping packs (the owner, 2026-11-22).** The place lies inside **two** packs: a 30 GB low-resolution US map
and an 8 GB high-resolution Colorado-only map. So "which pack answers for a point" is in play: the horizon may take
the first pack that covers the point, or the coarser one, or ask each in turn and stop at one that cannot give a
ring of terrain. The same question applies to the map's own choice of pack at a view, and to the packs' registration
order at launch (T328's note: a second pack opens after each update). Candidate: the Panorama asks one pack, finds
no data in it (the US pack's resolution or edge, or the Colorado pack opened late), and does not try the other or
the online source.

**The owner's rule for overlapping packs (2026-11-22).** Keep packs as they are (the alternative, downloading by a
grid of cells and keeping the best resolution anywhere in a cell, was the owner's earlier suggestion and "has downsides").
Then every reader must **look at the highest resolution available at the point and expect to step down to a coarser
pack once it leaves the finer one**: one ordered lookup (finest covering pack first, then coarser packs, then the online
source), used by the map, the Horizon view, the Panorama, the shade and the routes alike, and a horizon ring reads
each ray's samples through that lookup rather than from one pack. That is the rule the Panorama's task should end in,
and it is a candidate for the common read the idea above names ("one terrain read for a chosen point").

**The US pack's bounds (the owner's card, 2026-11-22).** It reads 33.40, -106.68 to 44.94, -72.85 at zooms z8-14 with
elevation to z13: its western edge is at -106.68°, which is **inside Colorado** (the state runs from about -109° to
-102°). A Colorado place west of that line is in the Colorado pack only, and one east of it is in both; a horizon from a
western Colorado peak looks west past the US pack's edge, and the card's name "US" hides that. Check the place's
longitude against -106.68 before assuming the overlap above applies, and note the Panorama's ray ring (out to its range)
can leave the US pack even when the viewpoint is inside it.

**Bonus bug (the owner, same message).** Choosing the US map and then going back shows the recurring "no map on this
phone yet" message, though two packs are open. The owner thinks it is the same fault. It is the family of
`2026-11-20-no-map-message-shown-with-a-bulk-downloaded-region.md` and of T332 (wave 46, in flight), whose candidate 2
(the regions not yet open when the card asks, and nothing asking again) fits a pack that is selected and then
deselected. T332's reproduction should include: two overlapping packs installed, choose the larger one in Offline
maps, go back, and read `GET /firstrun` and `/startup`. If T332's rule ("a region is open or an area exists") holds
in that case, the message here is a third reader of pack state (the Panorama's terrain read) disagreeing with the
other two.

**What more we would want to see.** (Items 2 to 4 above are answered; what remains:)
- Whether the fault follows the **pack choice**: with the US pack alone selected, with the Colorado pack alone, and
  with both, does the Panorama draw terrain at the same place, and does the "no map on this phone yet" message appear
  after Back from each? One line each is enough.
- Which pack the map itself is drawn from at that place (the high-resolution one should win).
- With USB debugging: `adb logcat` for the app across the "Sky from here" tap and the Panorama opening (the lines that
  say where each view read its terrain, and any `no elevation data` line), and `adb shell dumpsys` is not needed. A
  release build has no debug routes, so the routes' figures need the debug build installed beside it, which is a
  choice for the owner.
- Whether the Panorama draws terrain at a place **inside** the pack on the map, versus one far outside it: if inside
  works, the Panorama lacks the online fall-through; if neither works, it lacks the pack.

**The original list of what more we would want to see:**
1. The version and build (debug or release), and whether the phone showed the first-run card or "Offline maps" listing
   the Colorado pack; `GET /startup` and `GET /firstrun` at the moment of the white view (debug build), or Settings >
   Send diagnostics sent straight after.
2. Whether the same point gives terrain after a **cold restart** of the app (a negative cache or a late-opened pack),
   and whether the peak's own "View from here" (selection) behaves like the long-press "Sky from here".
3. Whether the online elevation source is **on** in Settings (the online-sources switch) and whether the map itself
   draws Colorado tiles from the network at that moment; if the map is blank too, the fault is the online source, not
   the horizon.
4. How far the point is from the nearest edge of the Colorado pack (a point inside the pack, near its edge, or outside),
   and the pack's size: a horizon needs terrain out to the range, beyond a small pack's edge.
5. A screenshot of the white Horizon view and of the Panorama's text; Send diagnostics gives the log lines, including any
   `no elevation data` line.

**Reproduction plan (for the task).** On an emulator: Colorado pack installed, location mocked elsewhere, online source
on and then off; long-press a point inside the pack, "Sky from here", read the view and `GET /horizon` (or the
panorama route) at once and after 5 s; then the same after restarting the app. The result is a finding either way.

**Overlap.** T332 (wave 46, in flight) reproduces the "no map" messages and writes one rule for "the phone holds a map";
it does not touch the Horizon or Panorama views' elevation reads. This is the same family (what the phone says about its
maps does not match what it holds) and should be read with T332's report before a task is written.

Possible plan (the coordinator's suggestion, not triaged): reproduce as above, then one task: the horizon's terrain read at a chosen point goes through the whole elevation
chain (regions, areas, online source) and says "loading" rather than a white view while it waits.
