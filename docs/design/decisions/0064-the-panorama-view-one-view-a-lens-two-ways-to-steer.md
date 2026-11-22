# 0064. The Panorama view: one view, a lens, two ways to steer, the camera an option behind it

Date: 2026-11-21
Status: accepted

Record: the planning with the owner on 2026-11-21 and the survey of that day, both in
`docs/ideas/2026-11-20-a-panorama-view-a-scrolling-ribbon-of-the-horizon.md`; wave 44's paragraph and
T322 in `docs/TASKS.md` (the critic's edits); the Horizon view it stands beside:
`docs/reference/android-horizon-view.md`.

## Context

The owner asked for a third view beside Map and Horizon (2026-11-20): the horizon as a ribbon to scroll
around in, with the Horizon view's layers and more room for names. Two other ideas pointed at a camera
(peak names on the camera's view; a camera pointed at the sky to name a plane), and on 2026-11-21 the
owner asked whether the third view should instead be a Camera view with the Panorama as its locked form;
a camera view had been parked as a possible fourth view.

The survey (ADR 0039; makers' pages for four apps, search snippets for three) found: the skyline apps
that have both a drawn panorama and a camera make the camera a mode of the one panorama, the drawing in
front; the sky apps keep one view with a drag mode and a sensor mode, the camera behind it an option of
the sensor mode; only flight and sun apps keep the camera on its own screen. Compass error is admitted
everywhere and handled by letting the user drag the drawing onto a known landmark, the offset shown and
cancellable. Whether following the phone starts on is split. No source gave field-of-view numbers.

The owner's words that settle the shape (2026-11-21): "I think it's Panorama mode -> scroll vs phone
controlled -> phone camera behind the image is an option available when phone controlled"; "In camera
mode we're aiming in any direction ... DOWN is always possible on an airplane for instance"; "while
locked (not following the phone) up is always up (when going to phone aim mode up is whatever direction
is up on the phone)"; the first version draws the skyline with a depth tint, and the four additions of
the same day (layered skylines, the longer range, the flat-skyline check, the bearing tape and the ties
to the lists) go in.

What the code gives: every layer of the Horizon view is a thing at a bearing and an elevation angle;
`HorizonLabels` ranks and places names through `RimGeometry`, the round plot alone; `HorizonProfile`
holds an angle and a distance per bearing; `Viewshed.peaks` gives angles below the horizon unclamped.

## Decision

1. **One view, named "Panorama"**, the third segment of the Map | Horizon switch. There is no Camera
   view and no fourth segment for one.
2. **Two ways to steer**, a synced setting with drag as its default (ADR 0044: the apps split).
   - Drag: a horizontal drag turns the bearing and goes round without end; a vertical drag tilts from
     below the horizon to overhead; a pinch zooms; up on the screen is always up (no roll).
   - Follow the phone: the view aims where the back of the phone points, in any direction, down
     included, and rolls with the phone. A drag while following becomes an offset, shown by name and
     cancellable; it is how compass error is corrected, before any camera.
3. **The camera behind the drawing** is an option offered only while following the phone; its permission
   is asked when it is first turned on. It is not built until the measurement pass of
   `docs/ideas/2026-11-02-horizon-match-fix-from-the-camera.md` has numbers. Peak names on the camera and
   plane names in the sky are this option, not features of their own.
4. **The projection is perspective** (the coordinator's, after the wave's critic): a view direction
   (bearing, tilt, roll) and a field of view, as a lens. Looking straight down, the roll and a later
   match to the camera's image all need it; a cylindrical strip gives none of the three. The cost is the
   owner's whole-circle ribbon: the widest view is about 100 degrees across. The endless horizontal
   scroll and the Horizon view (the whole circle at once) cover the rest.
5. **One placement for both plots.** `HorizonLabels.place` takes a projection that supplies where an
   angle and a bearing fall (or that they are off the view), the px per dp, which way is away from a
   mark, and the separation of two marks. `RimGeometry` is one projection and its output does not move
   (its separation is the difference of bearings, as today); the lens's separation is the screen
   distance over a named reference scale, so a narrower view names more. Layers, toggles, the place
   line and the lists are the Horizon view's, shared and not copied.
6. **The drawing**: the skyline swept at a few ranges out to the view range (`viewRange`, to 100 km) and
   drawn far to near, each in its own tint; a bearing tape, the level line; names as on the round plot.
   The default field of view is chosen from renders of a steep and a flat public area; a vertical
   stretch while dragging (never with the camera behind) is the fallback for flat skylines.
7. **Built in three passes** (the idea's Plan): `shared` and devtool `panorama` (T322, wave 44); the
   phone view with drag steering; Follow the phone with the offset.

Out of scope, each with what revives it: the terrain drawn below the horizon and its names (the window
seat's second pass, `docs/ideas/2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md`); the view
from an aircraft (its idea, after pass two); a choice of drawing styles (when the owner asks, the survey
having found users split); a whole-circle strip as a picture to share
(`docs/ideas/2026-11-21-share-a-labelled-panorama-picture.md`, which the devtool render can make);
what the Panorama segment does with a place selected on the map (the open Question of
`docs/ideas/2026-11-21-which-way-is-it-the-panorama-facing-a-selected-place.md`, due before pass two).

## Consequences

- The round plot and the lens share ranking, placement and layers, so a layer added once shows in both;
  the price is that a change to `HorizonLabels` now has two plots to hold, pinned by a placement test on
  the rim written before the refactor.
- The camera, when it comes, is a backdrop and a field of view to match, not a new screen: no fourth
  segment, no second label pipeline. Its permission stays out of the app until then.
- A lens cannot show the whole horizon at once. If the owner wants that on the phone, it is a second
  projection behind the same interface (a cylindrical strip for the drag mode only), not a change of
  this record's steering or of the camera option.
- Follow the phone needs the full attitude from the rotation sensor, not the heading alone
  (`controls/Heading.kt` serves the round plot's turn today).
- What humans see is shown, then adjusted (ADR 0031): T322's report carries the level render that shows
  the lens against a strip, and pass two's report the first frames and strings.

We would supersede this if the measurement pass shows the camera cannot be aligned well enough to draw
names over it (then item 3 goes and the camera ideas close), or if the owner, seeing the lens, wants the
strip as the view's main form.

- Prediction, check when T322 is merged: the round plot's placed names at Paradise are the same before
  and after, and `HorizonViewTest.kt` was not edited. Outcome 2026-11-21: held. T322's pin test passed
  with the test file untouched, and T321's verifier compared a build of the commit before the wave with
  the wave's on one emulator at the same fix, time and layers: the same 8 names, the same 5 leaders.
- Prediction, check when pass two is merged: no file of `shared/.../horizon/` had to fork for the lens
  (one placement, one layer list), and the Horizon view's sweep step still passes unchanged. Outcome
  2026-11-22: held. Pass two (T324 to T327, wave 45) added files to the package and forked none:
  `HorizonLabels.place` gained one optional argument (a forced name kept inside the lens's view), the round
  plot's call and its pin test unchanged, and the flights' names on the lens are `placeLayers`' second pass.
  The release `horizon` step passed on T326's and T327's branches; its only edits were the wave's own (the
  switch's spoken label with a third segment, and no Close button, T325).
- 2026-11-22, pass three merged (T329, T330 and T331, wave 46), so item 7's three passes are built. Two things narrowed in the building, both from the wave's research and critic: the "offset" of item 2 is a **heading correction** only, a drag along the level line that turns the drawing about the vertical (tilt and roll come from gravity and are never corrected), shown as "Compass adjusted N° east" or "west" with Reset, dropped on leaving Follow and on closing the view and **not kept between sessions** (compass error changes with the place and the phone's surroundings); and in Follow nothing turns the view but the phone: a row's or a name's tap sets the target and the edge arrow is a sign that says which way to turn. The steering is the synced setting `panoramaSteering`, Drag by default, flipped by the plot's Follow phone button. Item 3 (the camera) is untouched.
- Prediction, check when the camera option is proposed: it adds a backdrop and a field-of-view match to
  the Panorama screen and no new view.
