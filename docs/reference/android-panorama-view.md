---
verified: 2026-11-22
source: observed on an API 35 emulator at Paradise and at Mount Rainier's summit in the Rainier pack (T326, T327)
---

# The Panorama view

The third view of the switch ("Map | Horizon | Panorama"; ADR 0064, pass two of
`docs/ideas/2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md`): the land's skyline one way, as through a
lens, beside the Horizon view's round plot of the whole sky. Code: `app/panorama/` (the screen, the plot, the terrain
and sweeps, the routes), the scene and the aim in `shared/.../horizon/` (`PanoramaScene`, `PanoramaAim`,
`PanoramaSweep`, `LayeredSkyline`, `LensView`; devtool `panorama` draws the same scene, `devtool-commands.md`). Debug
routes: `android-debug-routes.md` (Panorama view). The choices it is built by are wave 45's (a) to (j) in
`docs/tasks/` (the wave's record) and `docs/TASKS.md` while the wave is open.

## Opening, the place and the switch

- **The switch** (`app/controls/ViewSwitch.kt`, `android-horizon-view.md` › The switch): from the map, "Panorama" opens
  the view at the map's selection by the same call as "Horizon" (`ViewSwitch.openAtSelection`: an open long-press card,
  else a search result's marker, else the fix); between Horizon and Panorama the view keeps the place it stands on and a
  picked peak; "Map" closes either. The pure rule is `HorizonPlace.switchAction` and `switched` (`SkyView`).
- **One open state** (choice i): `HorizonUi.open` is true for either sky view and `HorizonUi.view` says which; the
  Panorama's own state (the aim) is `PanoramaUi`'s. "Sky from here", "View from here" and "Back to here" are the Horizon
  view's and act on the same point; Back lets go of a pick, then a chosen point, then closes (`SkyViewEffects`).
- **The aim at opening** (choice c; an opening is the segment from the map or from the Horizon view; a "View from here"
  hop and "Back to here" keep the aim): 60 degrees across the plot's width, the tilt that puts the level line two thirds
  down, and the bearing by `HorizonPlace.openingBearing`: toward a picked peak, else at the fix the compass heading (the
  compass starts at the opening; its first reading within 1.5 s is taken once unless the user has steered), else north.
  `GET /panorama` `aim.opening` says which (`peak`, `heading`, `heading-late`, `north`). On an emulator the rotation
  vector reads the magnetic north, so at Paradise the heading is about 14.5 degrees true (the declination) when it comes
  in time; `POST /camera/heading?deg=` sets a simulated one.

## The screen

- **Layout** (choice e): upright, the plot is the window's full width and three quarters of that tall, at most 45
  percent of the window's height (411 by 309 dp on a Pixel 6), the pane under it; on its side the plot is at the left
  (the window's width less the pane, the wider of 320 dp and 40 percent of the width) and the window's height less the
  switch's room, the pane at the right. Where the pane's list would have under 96 dp, only the place line stays fixed and
  the heading and a picked peak's line scroll with the list (`headingScrolls`).
- **The pane** hosts the Horizon view's parts (`app/horizon/SkyViewParts.kt`, `SkyViewSections.kt`): the place line with
  "Back to here", the heading "Panorama view" and its help, a picked peak's line with "View from here", "Show:" with the
  Horizon view's toggles (Satellites, Peaks, Sun, and Flights while Flight tracking is on: the same synced settings; at a
  chosen point Satellites and Flights disabled with the Horizon view's sentence), the terrain line, then the Flights,
  Peaks (its count says the view range), Sun and Satellites sections in the Horizon view's order, and a flight's card
  over the pane's lower part. No Close button.
- **The plot** (`PanoramaPlot.kt`) draws T324's scene: the skylines at 3, 10 and 30 km and the view range far to near,
  each tinted from the style's tokens between the paper's line tone and the hillshade's shadow (night by the map's night
  colours), the sky from the paper's light at the level to a cool tone overhead, the level line dashed, the bearing tape
  on a plate along the top, the candidates' small triangles and the names placed by `HorizonLabels.place` through the
  lens (the plot writes `HorizonUi.namedKeys`, so the list's rows bold the names drawn), each on a plate of the card's
  colour with a thin edge (a pale plate with no edge read as a blob lying over a ridge), a hollow triangle an outline
  only. A tap on a name picks its peak.
  While nothing is ready a line stands in the plot (the reason, or "Working out the skyline...").
- **Steering** (choice d): a horizontal drag turns without end, a vertical drag tilts (45 degrees below the level to
  straight up), a pinch and the "Zoom in" and "Zoom out" buttons (48 dp, `PanoramaScene.zoomButtons`) zoom from 100 to 10
  degrees, all through `PanoramaAim`; during a drag or a pinch each name keeps its offset from its mark and the names are
  placed again when it ends (choice g). The plot's node says "Panorama from: <place line>; Facing <compass> <bearing>°,
  <field of view>° across; Peaks: ..." and carries four actions: Turn left and Turn right (half the field of view), Look
  up and Look down (10 degrees).

## The other layers, the target and the edge arrow (T327)

- **The layers through the lens** (`PanoramaLayers.kt`), drawn as the round plot draws them but where they fall (nothing
  sits on a rim; a mark below the level line stays where it is): the Sun's day path solid in view and dashed behind the
  terrain, its hour dots and labels, the sun now while it is up (`SkyPlot.kt`'s `sunMark`); the satellites' marks and
  numbers (`satelliteMark`); the flights' tails, dots, rings and names (`HorizonScreen.kt`'s `drawFlightLayer` through a
  projection) and the feed's credit in the plot's lower left. The order, bottom up: the ground, the sun, the satellites,
  the flights, the peaks' marks and names, the tape. The flights' names are the second pass of `placeLayers` against the
  peaks' names as placed (kept off their boxes, marks and leaders; the closest not forced), so no peak name moves for a
  flight's; during a drag, a pinch or a turn they keep their offsets as the peaks' do. The holds are the Horizon view's:
  the GNSS while Satellites draws, the flights' poller (owner `horizon`) while Flights draws, neither at a chosen point,
  both let go when the view closes (`SkyViewEffects`).
- **One target** (`PanoramaTarget.kt`, the pure rule `PanoramaTargets.next`): a tap on a row (a peak, the sun's "Now ..."
  line while the sun is up, a flight, a satellite) or on a name or mark on the plot makes it the target and turns the
  view to it by `PanoramaAim.turnTo` (the shorter way round; the tilt only when it would be off the view), over 300 ms
  with the names held, at once when the system's animations are off. A peak's pick and a flight's card are the target as
  picked; a pick kept across the switch is the target at the opening; the newest choice wins. Back lets go of a sun or
  satellite target first, then a flight, then a peak (then the chosen point, then the view closes); the pick's ×, the
  card's Close, a second tap on the row or a tap on empty plot let it go. A target below the tilt's limit turns the view
  to its bearing at -45 degrees and its arrow reads "down".
- **The edge arrow**: while the target is off the view, a plate at the edge `OffView` gives (6 dp inside, under the
  tape, off the zoom buttons' corner) with the arrow and its line ("Mount Rainier, 40° left"), at least 48 dp tall, its
  spoken label the line; a tap turns the view to the target. It goes when the target is in view or let go.

## The terrain, the sweeps and what is kept (choice f)

The terrain is read to the `viewRange` setting as the viewshed reads it (`Viewshed.plan` and `Viewshed.terrain` over the
offline maps' elevation packs; never the network), off the main thread (`PanoramaData`), then the skylines are swept
round the whole circle at `PanoramaSweep.steps` for the zoom: 1 degree first, then the step fine enough (0.5 at 60
degrees on a 1080 px plot, 0.1 at 10). The scenes of two places are kept (the fix's and one other), each by place within
100 m, range and step; a changed `viewRange` drops the other ranges and sweeps again. The peaks are read once per place
and range at `Viewshed.peakZoom` and tested on the farthest skyline, again on each finer one. Measured on a settled
emulator at Paradise, a fresh process: 30 km, 51 tiles, 8.5 MB, 0.53 s, the 1 degree sweep 0.28 s, the 0.5 degree 0.16
s, the first skyline 1.06 s after the tap; 100 km, 54 tiles, 9.0 MB (the pack ends inside the range), 0.63 s, 0.38 s
and 0.20 s, the first skyline 1.27 s; the 0.1 degree sweep at 10 degrees, 534k samples, 0.78 s. Just after a boot the
same terrain read took 70 s. The scene is built on the main thread: a 30-step drag measured 1.5 to 6.5 ms a frame (the
first build of a process 18 to 24 ms).
