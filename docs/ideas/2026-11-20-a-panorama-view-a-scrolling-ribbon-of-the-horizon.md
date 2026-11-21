---
date: 2026-11-20
kind: feature
related: [2026-11-19-the-horizon-view-beside-the-map-view.md, 2026-10-29-viewshed-and-peak-labels.md, 2026-11-20-moonshot-point-the-camera-to-name-a-plane.md, 2026-11-10-window-seat-mode-with-the-fix-moved-up-to-now.md, 2026-11-20-the-view-from-that-plane.md]
---

# A Panorama view: looking sideways at the horizon, the third view beside Map and Horizon

The owner, 2026-11-20: a third view. With it the app has three: **Map**, **Horizon** and **Panorama**. The Panorama
view shows just a ribbon of the horizon (the skyline, flattened to a strip) that you scroll around in, instead of
the Horizon view's round sky plot. It shows what the Horizon view shows at the same angles: peaks first, and the
other layers too (GPS satellites, the sun's path, flights, your own places; see the layer list of
`2026-11-19-the-horizon-view-beside-the-map-view.md`). What it adds is room: a view as wide as you care to scroll
gives each label space and allows zooming in, which the round plot cannot (T309's closest-peaks-first rim and its
leader lines exist because the rim is crowded).

Anchors: the switch is `app/controls/ViewSwitch.kt` (`SEGMENTS`, a third entry at the end); the skyline is
`dem/Horizon.kt` (`HorizonProfile`, an angle and a distance per bearing); the names are ranked and placed by
`shared/.../horizon/HorizonLabels.kt` through `RimGeometry`, which is the round plot only; the layers, the place line
and the lists are the Horizon view's (`shared/.../horizon/`, `app/horizon/`), to share rather than copy.

2026-11-20, the owner: "Panorama is soon but after the airplane tracks." (The tracks shipped in v0.1.38-alpha.)

2026-11-21, the survey (ADR 0039; makers' pages for PeakFinder, swisstopo, The Photographer's Ephemeris and
Flightradar24, search snippets for PeakVisor, Stellarium and SkySafari). The three skyline apps that have both a drawn
panorama and a camera make the camera a mode of the one panorama view, the drawing staying in front; the sky apps keep
one view with a drag mode and a sensor mode, the camera behind it an option of the sensor mode; only the flight and
sun apps keep the camera on a screen of its own. Compass error is admitted everywhere and handled by a drag: the user
slides the drawing onto a known peak or the sun, the offset is shown and can be cancelled. Another viewpoint is common
and is drawn without the camera. Zooming in names more peaks. Whether following the phone starts on is split. No
source gave field-of-view numbers, label contents or orientation rules.

2026-11-21, the owner, asked whether this is a Camera view with the Panorama as its locked form: "I think it's
Panorama mode -> scroll vs phone controlled -> phone camera behind the image is an option available when phone
controlled." On direction: "In camera mode we're aiming in any direction - nominally locked to where the phone is
facing, but DOWN is always possible on an airplane for instance." On tilt: a vertical drag tilts up and down, and
"while locked (not following the phone) up is always up (when going to phone aim mode up is whatever direction is up
on the phone)." Terrain in the first version: the skyline with a depth tint. So the camera idea parked as a fourth
view (`2026-11-20-moonshot-point-the-camera-to-name-a-plane.md`) and stage 3 of the peak labels
(`2026-10-29-viewshed-and-peak-labels.md`) are both an option of this view, not views of their own.

## Shape

- **One view, "Panorama"**, the third segment of the switch. It looks one way with a field of view, as through a lens;
  the Horizon view stays the whole circle at once.
- **Two ways to steer.** Drag (the default; the survey's split makes it a synced setting, ADR 0044): a horizontal drag
  turns the bearing and goes round without end, a vertical drag tilts from below the horizon to overhead, a pinch
  zooms, and up on the screen is always up. Follow the phone: the view aims where the back of the phone points, in any
  direction, down included, and rolls with the phone; a drag while following becomes an offset that is shown by name
  and can be cancelled (the surveyed practice for compass error).
- **The camera behind the drawing** is a later option, offered only while following the phone, its permission asked
  when it is first turned on. It waits for the measurement pass of `2026-11-02-horizon-match-fix-from-the-camera.md`.
- **The drawing** is the skyline filled and tinted by distance (`HorizonProfile.distanceM`); layered ridge lines or a
  style choice come later.
- **Projection** (the coordinator's, 2026-11-21): perspective, because looking down, the roll and the camera all need
  it. The widest view is then about 100 degrees across, not the whole circle as a strip; the endless scroll and the
  Horizon view cover the rest.

Plan: three passes in this order; the file stays until the third lands. No further survey (one per question).
Pass one, `shared` and `devtool` only: the label placement takes a projection (where an angle and a bearing fall, or
that they are off the view; which way is away from a mark; the degrees per dp), `RimGeometry` is one, giving the
placements it gives today, and a lens view (bearing, tilt, roll, field of view) is the other; the density gap follows
the zoom, so a narrower view names more; angles below the horizon are placed, not clamped; the sweep's step is fine
enough for the narrowest zoom, its cost measured; devtool `panorama` renders an area to a PNG and reports the names
placed and the timings. Done when: `check` green with `HorizonLabels`' tests unchanged; `panorama --area rainier`
viewed level, tilted down, rolled and zoomed in.
Pass two, the phone: the third segment; a Panorama screen on the Horizon view's own pane (the place line with "Sky
from here" and Back to here, the toggles, the lists, the flight card) with the lens view as its plot; drag, tilt and
pinch as above; names tapped as on the round plot; `GET` and `POST /panorama` (bearing, tilt, field of view, the
screenshot), a sweep step and an a11y screen. Done when: the device tour's frames are viewed at the default text and
at font scale 2.0, upright and sideways, at the fix and at a chosen point.
Pass three, the phone: Follow the phone (the attitude from the rotation sensor, any direction, the roll), the steering
setting, the named offset and its cancel, a debug route that sets an attitude. Done when: frames at a set attitude
looking down and rolled are viewed, and the offset is seen set and cancelled.
Out of scope in all three: the camera, the terrain drawn below the horizon (the window seat's pass two), the view from
an aircraft, layered ridges.
