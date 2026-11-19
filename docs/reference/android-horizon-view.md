---
verified: 2026-11-19
source: observed on an API 35 emulator at Paradise in the Rainier pack (T307, T308); moved from android-app.md in T308
---

# The Horizon view

The map view's counterpart (the owner, 2026-11-19): the sky around the phone on one square plot, with layers turned on
and off by toggle buttons. Code: `app/horizon/` (the screen, the peaks and the sun, the routes), `app/gnss/` (the
satellites layer, and the plot itself in `SkyPlot.kt`), `shared/.../horizon/` (the layers, the sun's path, the rim's
peaks). Debug routes: `android-debug-routes.md` (Horizon view, GPS view). The map's own switch into the view is a later
pass (`docs/ideas/2026-11-19-the-horizon-view-beside-the-map-view.md`).

## The screen (T308)

Settings › Navigation and tracking › "Horizon view ›" (the page's last two rows: the link, then "Sky plot"; the row's id
is still `gnssStatus`, "GPS status" before T308) opens it over the map, day or night as the map style is (the style's
paper, ink and terrain tokens, through `NightColors` at night; light status-bar icons at night). The plot is a square
`min(width, height)` of the window inside the system bars, fixed at the top in portrait (411 dp on a Pixel 6 AVD) and
at the left in landscape (363 dp); the rest is a heading that stays ("Horizon view", its help, Close; a picked peak's
line under it) over an area that scrolls: "Show:" with the three toggles (Satellites, Peaks, Sun; a tick and a filled
button when on), "Sky plot:" (Follow the phone, North up), the terrain line and the band's legend, then the Peaks, Sun
and Satellites sections in that order (the satellites' list is the long one, so it comes last). Back lets go of the
menu, the confirmation and a picked peak, then closes. The plot keeps T307's rim (the horizon), rings at 30 and 60
degrees, N E S W with north marked, and the land's skyline as a band; it turns with the compass in "Follow the phone"
(`Heading.setGnssWanted`, held while the view is open and started).

Each toggle is a synced switch (`horizonSatellites`, `horizonPeaks`, `horizonSun`, `app.` in the sync log; on by
default; no settings row: the buttons set them through `SettingsRoutes.apply`). With Satellites off the GNSS is not
held (`GET /horizon` `gnss.held` false, `engine` off) and the view follows the map's own fix (`Gnss.position`): the
skyline, the peaks and the sun move after `POST /location`. With every layer off the plot shows the skyline alone.

**The terrain's horizon** is T307's: `Horizon.sweep` at the position (1 degree bearings, eye height 1.7 m, 30 km) over
`ShadeService.pyramidAt` (the offline map, else the online DEM), on a thread of its own, kept with the terrain it read
until the position moves 100 m. At Paradise it took 0.6 s on a settled emulator and 123 s on one just booted (T307 saw
129 s once).

**Peaks** (`HorizonData.peaksFor`): the named peaks of the active pack's `mountain_peak` layer within the profile's
range (`Viewshed.readPeaks`, as `ViewshedSession` reads them) tested on the same terrain and profile
(`Viewshed.peaks(terrain, horizon, peaks)`, the overload on a `HorizonProfile`: no viewshed sweep), once per horizon on
a thread of its own. The list has every peak on the skyline, clockwise from north (the name, bold when the rim names
it; height, distance, compass point and bearing). The rim names at most one per 30 degree sector of true bearing, the
highest (`PeakRim.labelled`), and none within 10 degrees of a higher one named; each name is set inward from its
triangle at the first of four steps where its box touches no other (else it is left to the list), drawn over the
satellites with a halo. A tap on a name or a row picks the peak: its line ("Little Tahoma, 11138 ft, 4.5 mi, NNE 14°")
under the heading and its name in the link colour; a second tap, the line's × or Back lets it go. At Paradise: 149
peaks read from 47 tiles in 0.6 s, 20 on the skyline, 6 named (Little Tahoma, Unicorn Peak, Plummer Peak, Chutla Peak,
Osborne Mountain, Mount Rainier).

**Sun** (`HorizonData.sunFor`, `shared/.../horizon/SunPath.kt`): the day's path at the point (the date whose solar noon
it is, `Sun.dayAt`, or a `date=` from the debug route), sampled every 5 minutes down to 1 degree below the horizon and
drawn at the sun's centre, solid in view and dashed behind the terrain, with a dot and the hour (the phone's zone) on
each whole hour; the sun now as T307 drew it. The section: where the sun is now, "Clears the terrain at …, goes behind
it at …" with any gaps behind a peak, the hours of sun, and the flat horizon's sunrise and sunset. The terrain times are
`Sun.onTerrain` on the plot's own profile (the upper limb's), the call the turn-around clock reaches through
`ShadeTiles.pointAnswer` (`ShadeService.terrainAt`): `GET /horizon?compare=1` at Paradise for 2026-11-19 gave the same
sunrise and sunset to the millisecond (`sunsetDifferenceMs` 0). Over the flat horizon while there is no profile.

The sweep opens the view in `android-ui.py features` (step `horizon`, frames `horizon` and `horizon-scrolled`; `gps`
and `gps-plot` before T308) and `android-a11y.py` (screen `horizon`, `gps` before T308).

## The satellites (T307)

T307 built this as the GPS view; T308 made it the Horizon view's Satellites layer (`app/gnss/`; the verdict and the summary are `shared/.../gnss/GnssHealth.kt`). Its section of the scrolling area, top to bottom: the heading "Satellites" with its menu, the summary (satellites used of in view, the four best C/N0 and their mean, the systems, the band line "L1 + L5" or "L1 only" from the carrier frequencies, accuracy, fix age, height from the GPS and from the map, speed, course, time to first fix, the fix's time); "Check my GPS" and its verdict; the legend with the ramp in dB-Hz; the satellites heard, used first then by signal (two lines each above font scale 1.3). The layout, the setting and the verdict's figures come from the two surveys in the idea of 2026-11-19 (the idea file was deleted with T307; its text is in git). While the view is open with Satellites on, and only while the activity is started, it holds the GNSS (`Gnss.hold`): a `GnssStatus.Callback` and its own GPS listener on the recorder's looper, both removed when the last holder lets go; "Follow the phone" runs the app's one compass (`Heading.setGnssWanted`). Time to first fix is the app's own: from the first `MapController.startDeviceLocation` of the process to its first GPS fix. The terrain's horizon is `Horizon.sweep` at the position (1 degree bearings, eye height 1.7 m) over `ShadeService.pyramidAt` (the offline map, else the online DEM), on a thread of its own, kept until the position moves 100 m; at Paradise it took 0.5 to 2.3 s on a quiet emulator and 129 s once on one just booted under the map's first tiles; with no elevation data the plot has no band and the line says why. "Check my GPS" reads the status once a second for 120 s, stops with a message if the position moves more than 20 m, and judges: healthy (T4, the mean of the four strongest of the best system above the terrain, at least 35 dB-Hz, and at least 4 used), "May be a hardware fault" (T4 under 25, nothing above the terrain over 30, at least 8 heard above it), else weak signal; satellites behind the terrain (under its angle plus 2 degrees) never count against the phone; the first fix is shown, not judged. The fault verdict's 25 is uncalibrated. The menu's "Reset assistance data" asks first, then `sendExtraCommand` (`delete_aiding_data`, time and orbit injection; `ACCESS_LOCATION_EXTRA_COMMANDS`) and says whether the phone took it. The last check rides in Send diagnostics (`diagnostic-dump.md`, section `gnss`). On the plot each satellite is a shape per system filled on a colour-blind-safe C/N0 ramp, a thick outline when used in the fix, the number beside it, a ring when heard on a second band. Routes: `GET|POST /gnss`, `/gnss/ui`, `/gnss/check`, `/gnss/reset` (`android-debug-routes.md`).
