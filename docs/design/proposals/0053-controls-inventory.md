# 0053 inventory: every control and setting on the web page and the phone

Date: 2026-11-02
Status: reference for proposal 0053 (`0053-one-definition-of-controls-and-settings.md`); the owner edits the
Category column
Pinned to: `origin/main` at `07b570a92738d5a42cb780a92b46872707ed4954`. A control added after that commit is not
here; wave 26's T205 edits the web page's title and credits (no control) and T203 appends rows to the phone's settings.

## How to read it

- **Web** is the browser page every server serves at `/` (`tools/demo/index.html`, with the trip editor
  `tools/demo/trips.js` and `tools/demo/trip-sync.js` it loads in live mode). The `/phone` page of `serve --live` is a
  bare map for checking the phone style and has no control.
- **Phone** is the Android app. `<app>` below stands for
  `androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app`. Settings rows come from
  `<app>/settings/SettingsLayout.kt` (groups and rows in sheet order, the same list `GET /settings/layout` answers),
  drawn by `<app>/settings/SettingsPages.kt` and, for the Map row, `<app>/options/MapOptionsSection.kt`.
- **Key** is what the control writes: a map option of `/style.json` (`MapOptions.KEYS`, the one surface both clients
  share, validated by the server with a 400), a phone settings key (`POST /settings`; synced between devices as
  `app.<key>`, map options as `app.map.<key>`, from `<app>/sync/SettingsSync.kt`), a browser `localStorage` key, or
  "-" for an action or a page-only state. "per device" marks a phone setting that is stored but not synced.
- **Zone** is where it sits: on the web, the side panel (top left, `details#panel`, collapsed under 520 px), the
  Trips box (top right), the map's corners, or the map itself; on the phone, the settings sheet (group › row), the
  map's top row and right chip column, the lower right buttons, the lower left strip, a card or sheet, or a screen.
- **Category** is empty on purpose: the owner fills it (ADR 0031: the categories and the wording are the owner's).
  Proposal 0053 suggests a set; see its Recommendation.
- Every phone settings row also carries a help icon (`?`, the `help = "..."` key `HelpRegistryTest` checks); the web
  has `title=` tooltips on a few controls. Neither is counted as a control.

## 1. The shared surface: what both clients offer, and its one-sided neighbours

Nineteen items exist on both clients. The six rows marked "(one side)" exist on one client only and stand here beside
their counterpart (corrected after the critic's review, objection 6; the first count said 25).

| Item | Web: id, label, kind | Phone: row › label, kind | Key (shared) | Category |
|---|---|---|---|---|
| Hillshade method | `opt-hillshade` "Hillshade": select single light, multidirectional, off (Map options, panel) | Map › Hillshade: chips Off, Single, Multi | `hillshade` (`app.map.hillshade`) | |
| Hillshade on/off | `hill` "Hillshade (from our DEM tiles)": checkbox, page only (panel top) | the Off chip above | page state; `hillshade=off` on the phone | |
| Hillshade intensity (one side) | no control (read from `?hillshadeIntensity=`) | Map › Intensity: stepper in 10 % steps (shown when hillshade is on) | `hillshadeIntensity` | |
| Slope shading | `opt-slope` "Slope shading": select off, avalanche, steepness, with legend (live mode only; disabled in the static demo) | Map › Slope shading: chips Off, Avalanche, Steepness, with legend | `slope` | |
| Contour interval | `opt-interval` "Contour interval": select auto and the unit's intervals | Map › Contours: chip Auto and a `<` `>` stepper | `contourInterval` | |
| Contours on/off (one side) | `contours` "Contours, feet (computed live)": checkbox, page only | none (no way to hide contours) | page state | |
| Contour labels (one side) | no control (the page's own style ignores it) | Map › Contour labels: chips Sparse, Normal, Dense | `contourLabels` | |
| Trails | `opt-trails` "trails": checkbox | Map › Layers: chip Trails | `trails` | |
| Water | `opt-water` "water": checkbox | Map › Layers: chip Water | `water` | |
| Land cover | `opt-landcover` "land cover": checkbox | Map › Layers: chip Land cover | `landcover` | |
| POIs | `opt-pois` "POIs": checkbox | Map › Layers: chip POIs | `pois` | |
| Boundaries | `opt-boundaries` "boundaries": checkbox | Map › Layers: chip Boundaries | `boundaries` | |
| All OSM layers (one side) | `osm` "Trails, roads, water, names (OSM)": checkbox, page only | none | page state | |
| Drainage (one side) | no control (the page's own style has no drainage layer) | Map › Drainage: chips On, Off | `drainage` | |
| Night | `opt-night` "Night": checkbox | Map › Night: chips On, Off | `night` | |
| Units | `unitsel` "Units": select feet, metres, reset to the region default (panel); `?units=` for one load | Units and display › Units: Feet, Metres | browser `dystopo.units`; phone `units` (`app.units`) | |
| Text size | `text` "Text size": slider 0.8 to 2, page only, not stored | Units and display › Text size: `-` `+` stepper | phone `textScale` (`app.textScale`) | |
| Scale bar (one side) | display only, lower left, one unit | Map › Scale bar: chips One unit, Both units; the bar lower left | phone `scaleBar.both` (`app.scaleBar.both`) | |
| Print a map sheet | Print box (panel, live with `GET /print`): `printscale` Scale, `printpaper` Paper, `printlandscape` landscape, `printgrid` Grid, `printsheet` "Make the sheet (PDF)" | Map › Print a map sheet: one chip per scale, paper in the system dialog | - (action) | |
| Position | `GeolocateControl` "locate me" button, top right (live mode) | the position button, lower right (section 4) | - | |
| Rotation and north | `NavigationControl` compass button, top right; right-drag rotates | the orientation button and "Keep north up" (sections 3 and 4) | phone `orientationLock` | |
| Plan a route | Trips box "Plan a route", clicks on the map | the long-press menu and the route card (section 4) | - | |
| Trips list, open, delete | Trips box › Saved trips: Open, Delete | the Routes chip and its list (section 4) | - | |
| Send trips to the server, get them | Trips box: "Send to server", "Get from server" | sync with the server (automatic; Sync status sheet) | - | |
| Waypoints on a trip | Trips box › Waypoints: Add waypoint, name, Show, Remove, drag | the trip card's waypoints (section 4) | - | |

## 2. Web: every control, in page order

| # | Id | Label as shown | Kind | Drives | Zone | When | Category |
|---|---|---|---|---|---|---|---|
| W1 | `#panel` summary | "Dystopo: Mount Rainier" or "Dystopo: live world" | disclosure | opens, closes the panel | panel | always (closed under 520 px) | |
| W2 | `hill` | Hillshade (from our DEM tiles) | checkbox | hillshade layer visibility | panel | always | |
| W3 | `contours` | Contours, feet (computed live) | checkbox | contour layers visibility | panel | always | |
| W4 | `unitsel` | Units | select: feet, metres, reset to the region default | `dystopo.units`; reloads | panel | always | |
| W5 | `osm` | Trails, roads, water, names (OSM) | checkbox | every OSM layer's visibility | panel | always | |
| W6 | `osmsel` | OSM archive | select: outdoors profile, OpenMapTiles profile | `?osm=`; reloads | panel | static demo only | |
| W7 | `text` | Text size | slider 0.8 to 2, step 0.1, default 1.3 | label sizes, not stored | panel | always | |
| W8 | `#options` summary | Map options | disclosure | opens the section (opened when the URL carries an option) | panel | always | |
| W9 | `opt-hillshade` | Hillshade | select: single light, multidirectional, off | `hillshade` | panel › Map options | always | |
| W10 | `opt-slope` | Slope shading | select: off, avalanche, steepness | `slope` | panel › Map options | live (disabled otherwise) | |
| W11 | `opt-interval` | Contour interval | select: auto and the intervals in the units | `contourInterval` | panel › Map options | always | |
| W12 | `opt-night` | Night | checkbox | `night` | panel › Map options | always | |
| W13 | `opt-trails` | trails | checkbox | `trails` | panel › Map options | always | |
| W14 | `opt-water` | water | checkbox | `water` | panel › Map options | always | |
| W15 | `opt-landcover` | land cover | checkbox | `landcover` | panel › Map options | always | |
| W16 | `opt-pois` | POIs | checkbox | `pois` | panel › Map options | always | |
| W17 | `opt-boundaries` | boundaries | checkbox | `boundaries` | panel › Map options | always | |
| W18 | `#build` summary | Build a pack here | disclosure | opens the section | panel › operator tools | live, with a stored token or `?tools=1` | |
| W19 | `packname` | Pack name | text field | `POST /build` `name` | panel › operator tools | as W18 | |
| W20 | `packmin` | Zooms (from) | number | `minZoom` | panel › operator tools | as W18 | |
| W21 | `packmax` | (to) | number | `maxZoom` | panel › operator tools | as W18 | |
| W22 | `packprofile` | Vector | select: outdoors profile, OpenMapTiles | `profile` | panel › operator tools | as W18 | |
| W23 | `packsource` | OSM from | select: auto, an extract, Overpass | `source` | panel › operator tools | as W18 | |
| W24 | `packsources` | What would it use? | button | `GET /build/sources` | panel › operator tools | as W18 | |
| W25 | `packreplace` | replace a pack of that name | checkbox | `overwrite` | panel › operator tools | as W18 | |
| W26 | `buildpack` | Build pack here | button | `POST /build` | panel › operator tools | as W18 | |
| W27 | `buildcancel` | Cancel | button | `DELETE /build/{id}` | panel › operator tools | while a build runs | |
| W28 | `packtoken` | Server token | password field | `dystopo.token`; `?token=` sets it | panel › operator tools | as W18 | |
| W29 | `#print` summary | Print a map sheet | disclosure | opens the section | panel | live, when `GET /print` answers | |
| W30 | `printscale` | Scale | select: 1:10,000, 1:25,000, 1:50,000, fit the view | `GET /print` `scale` | panel › print | as W29 | |
| W31 | `printpaper` | Paper | select: by region, A4, Letter, A3 | `paper` | panel › print | as W29 | |
| W32 | `printlandscape` | landscape | checkbox | `landscape` | panel › print | as W29 | |
| W33 | `printgrid` | Grid | select: UTM, latitude and longitude ticks, both, none | `grid` | panel › print | as W29 | |
| W34 | `printsheet` | Make the sheet (PDF) | button | downloads the PDF | panel › print | as W29 | |
| W35 | NavigationControl | (zoom in) | button | camera | map, top right | always | |
| W36 | NavigationControl | (zoom out) | button | camera | map, top right | always | |
| W37 | NavigationControl | (compass) | button | north up | map, top right | always | |
| W38 | GeolocateControl | (locate me) | toggle button | follow the position; seeds units once | map, top right | live | |
| W39 | AttributionControl | (i) | toggle button | shows the credits | map, bottom right | compact under 520 px | |
| W40 | map | drag, scroll, right-drag | gestures | pan, zoom, rotate; the view in the URL hash | map | always | |
| W41 | `#trips` summary | Trips | disclosure | opens the box (closes the panel under 520 px) | Trips box, top right | live | |
| W42 | `trip-plan` | Plan a route | toggle button | planning mode | Trips box | live | |
| W43 | `trip-mode` | Mode | select: Hike, Bike, Drive, Crow | the route's mode | Trips box | live | |
| W44 | map | click | gesture | start, end, then a stop | map | while planning | |
| W45 | via marker | drag; right-click | gesture | move; remove a point | map | while planning | |
| W46 | `trip-name` | Trip name | text field | the trip's name | Trips box › card | a route shown | |
| W47 | `trip-profile` | (elevation profile) | hover | readout and a point on the map | Trips box › card | a route shown | |
| W48 | `trip-dirs-more` | (all directions / fewer) | button | expands the turn list | Trips box › card | a long route | |
| W49 | `trip-save` | Save trip | button | saves in this browser | Trips box › card | a route shown | |
| W50 | `trip-gpx` | GPX file | button | downloads GPX | Trips box › card | a route shown | |
| W51 | `trip-clear` | Clear | button | removes the route from the map | Trips box › card | a route shown | |
| W52 | saved trip | Open | button | loads the trip | Trips box › Saved trips | per trip | |
| W53 | saved trip | Delete | button | deletes the trip | Trips box › Saved trips | per trip | |
| W54 | `sync-pull` | Get from server | button | pulls trips from the server | Trips box | live | |
| W55 | `trip-push` | Send to server | button | pushes this browser's trips | Trips box | live | |
| W56 | `trip-dsync` | .dsync file | button | downloads a sync file | Trips box | live | |
| W57 | `wpt-add` | Add waypoint | toggle button, then a click on the map | adds a waypoint | Trips box › card › Waypoints | a trip open | |
| W58 | `wpt-name` | Name (optional) | text field | the next waypoint's name | Trips box › card › Waypoints | a trip open | |
| W59 | waypoint row | (name) | text field | renames | Trips box › card › Waypoints | per waypoint | |
| W60 | waypoint row | Show | button | centres the map on it | Trips box › card › Waypoints | per waypoint | |
| W61 | waypoint row | Remove | button | removes it | Trips box › card › Waypoints | per waypoint | |
| W62 | waypoint marker | drag; right-click | gesture | move; remove | map | per waypoint | |

Shown but not controls: the scale bar (lower left), the status line (zoom and centre, bottom), the sources box (live, top
right), the slope legend, the hint line, the trip card's figures.

URL-only options (read by the page, no control): `?hillshadeIntensity=`, `?units=` (one load), `?live=1`, `?tools=1`,
`?token=`, `?dem=xyz`, the view hash. Map options the page ignores: `contourLabels`, `drainage`.

## 3. Phone: the settings sheet, in sheet order

Groups (list order): Data and storage, Map options, Units and display, Navigation and tracking, Sync and sharing, About
and advanced (`SettingsGroup`; titles from `Screens`). The sheet opens on the group list (two panes on a wide window).

| # | Group › row (id) | Label as shown | Kind and choices | Key | Category |
|---|---|---|---|---|---|
| S1 | Data › Sources (`sources`) | Sources: server field ("http://host:port") | text field, Save, Test, Packs › | `server` (per device) | |
| S2 | Data › Sources | Mobile data | choice Use, Off (only with no server) | `directOnMobileData` (per device) | |
| S3 | Data › Sources | Limit per session | choice (only with no server) | `directSessionMb` (per device) | |
| S4 | Data › Storage (`storage`) | Storage › | link to the Storage screen (budgets, Clear; section 4) | cache budgets (per device) | |
| S5 | Data › Offline areas (`offlineAreas`) | Offline areas › | link to the Offline areas screen (section 4) | - | |
| S6 | Map › Map options (`mapOptions`) | Hillshade | chips Off, Single, Multi | `hillshade` | |
| S7 | Map › Map options | Intensity | stepper (when hillshade is on) | `hillshadeIntensity` | |
| S8 | Map › Map options | Slope shading | chips Off, Avalanche, Steepness; legend | `slope` | |
| S9 | Map › Map options | Shade | chips On, Off; when on: day `<` `>`, date picker, Today, time stepper, time slider | - (off at each launch) | |
| S10 | Map › Map options | What can I see from here | chip From where I am (closes the sheet) | - (action) | |
| S11 | Map › Map options | View range | chips 10, 30, 100 km (in the units) | `viewRange` (same value as S21) | |
| S12 | Map › Map options | Contours | chip Auto, stepper `<` `>` | `contourInterval` | |
| S13 | Map › Map options | Contour labels | chips Sparse, Normal, Dense | `contourLabels` | |
| S14 | Map › Map options | Layers | chips Trails, Water, Land cover, POIs, Boundaries | `trails`, `water`, `landcover`, `pois`, `boundaries` | |
| S15 | Map › Map options | Drainage | chips On, Off | `drainage` | |
| S16 | Map › Map options | Night | chips On, Off | `night` | |
| S17 | Map › Map options | Scale bar | chips One unit, Both units | `scaleBar.both` | |
| S18 | Map › Position button (`mapControls`) | Position button | 1 button, 2 buttons, 3 buttons | `mapControls` | |
| S19 | Map › Close zoom (`recenterZoom`) | Position button: Close zoom | zoom levels | `recenterZoom` | |
| S20 | Map › Keep north up (`orientationLock`) | Keep north up | On, Off | `orientationLock` | |
| S21 | Map › View range (`viewRange`) | View range | 10, 30, 100 km | `viewRange` | |
| S22 | Map › Print a map sheet (`printSheet`) | Print a map sheet | one action per scale (system print dialog) | - (action) | |
| S23 | Display › Units (`units`) | Units | Feet, Metres | `units` | |
| S24 | Display › Text size (`textSize`) | Text size | `-` `+` stepper | `textScale` | |
| S25 | Navigation › Route snap (`routeSnap`) | Route snap | distances | `routeSnapM` (per device) | |
| S26 | Navigation › Spoken directions (`navSpeech`) | Navigation: Spoken directions | On, Off | `navSpeech` | |
| S27 | Navigation › GPS fix stale (`navStale`) | Navigation: GPS fix stale after | seconds | `navStaleS` (per device) | |
| S28 | Navigation › Snooze (`navSnooze`) | Navigation: Snooze | minutes | `navSnoozeMin` (per device) | |
| S29 | Navigation › Route times (`pace`) | Route times | Your pace, Use the standard estimate, Reset | `paceStandard`, `paceFit`, `paceSince` | |
| S30 | Navigation › Turn back by (`turnDeadline`) | Navigation: Turn back by | Sunset, Civil twilight, Terrain sunset, A time I type (HH:MM field) | `turnDeadline` | |
| S31 | Navigation › Turn-back margin (`turnMargin`) | Navigation: Turn-back margin | minute presets, other (field) | `turnMarginMin` | |
| S32 | Navigation › Recording: Activity (`trackActivity`) | Recording: Activity | one per activity | `trackActivity` | |
| S33 | Navigation › Recording: Accuracy limit (`trackAccuracy`) | Recording: Accuracy limit | distances, Off (for the activity) | `trackProfile.<activity>` | |
| S34 | Navigation › Recording: Auto-pause (`trackAutoPause`) | Recording: Auto-pause | On, Off | `trackProfile.<activity>` | |
| S35 | Navigation › Recording: Network location (`trackFallback`) | Recording: Network location | On, Off | `trackProfile.<activity>` | |
| S36 | Navigation › Recording: Interval (`trackInterval`) | Recording: Interval | seconds, minutes | `trackProfile.<activity>` | |
| S37 | Navigation › Recording: Battery (`trackBattery`) | Recording: Battery | link: Battery settings › (Android's) | - | |
| S38 | Navigation › Readout: Speed (`readoutSpeed`) | Readout: Speed | On, Off; mph or km/h, Pace, Knots; damping; `±` | `readout.<activity>.speed` | |
| S39 | Navigation › Readout: Climb (`readoutClimb`) | Readout: Climb | as S38 (the entry's subset) | `readout.<activity>.climb` | |
| S40 | Navigation › Readout: Course (`readoutCourse`) | Readout: Course | as S38 (the entry's subset) | `readout.<activity>.course` | |
| S41 | Navigation › Readout: Elevation (`readoutElevation`) | Readout: Elevation | as S38 (the entry's subset) | `readout.<activity>.elevation` | |
| S42 | Navigation › Readout: Barometer (`readoutBarometer`) | Readout: Barometer | On, Off | `readoutBarometer` | |
| S43 | Navigation › Readout bar (`readoutBar`) | Readout bar | On, Off | `readoutBar` | |
| S44 | Navigation › Climb window (`climbWindow`) | Readout: Climb window | durations | `climbWindow` | |
| S45 | Navigation › Pressure-fall warning (`pressureFallWarning`) | Pressure-fall warning | Off, More warnings, Standard, Fewer | `pressureFallWarning` | |
| S46 | Navigation › Water along the route (`waterAlongRoute`) | Water along the route | On, Off | `waterAlongRoute` | |
| S47 | Navigation › Water: Spoken (`waterSpoken`) | Water: Spoken | On, Off | `waterSpoken` | |
| S48 | Navigation › Route times: Show as (`timeEstimateForm`) | Route times: Show as | Range, One number | `timeEstimateForm` | |
| S49 | Navigation › Terrain shade: Spoken (`shadeSpoken`) | Terrain shade: Spoken | Off, On hot days, Always | `shadeSpoken` | |
| S50 | Navigation › Set my height now (`setHeight`) | Set my height now | height field (action) | - | |
| S51 | Sync › Sync status (`syncStatus`) | Sync status › | link to the Sync status sheet | - | |
| S52 | Sync › Share (`share`) | Share › | link to the Share screen | - | |
| S53 | About › Updates (`updates`) | Updates | version, Check for updates, Install | - | |
| S54 | About › Diagnostics (`diagnostics`) | Diagnostics | memory figures, Copy report | - | |
| S55 | About › Report a problem (`reports`) | Report a problem | crashes and kills listed, Open an issue | - | |
| S56 | About › Contour warm-up (`warmup`) | Contour warm-up | On, Off | `warmup` (per device) | |
| S57 | About › Credits (`credits`) | Credits: Map data and sources › | link to the About screen | - | |

The sheet's row list has 44 entries; S6 to S17 are the twelve rows of the one `mapOptions` entry, and S1 to S3 the
Sources entry's parts. Phone settings kept outside the sheet (section 4): the trip corridor width (`tripCorridorM`,
synced, Offline areas screen), the cache budgets (`cacheBudgetMb`, `pinnedCacheMb`, Storage screen, reached from S4),
the sync server and its token (Sync status sheet, per device), the map's orientation (`mapOrientation`, per device,
the lower right buttons). Per-device keys on no screen: `navStartM`, `decoder`, `debugFixedPort` (debug routes only).

## 4. Phone: the map's controls, cards, menus and screens

From `<app>/MainActivity.kt` › `MapScreen` and every overlay it composes, in its order of surfaces below. A chip set
whose chips do the same thing with different values (the four travel modes, the six activities) is one row. Help icons
(`?`, about twenty keys on these surfaces) and the help pop-up's OK are not counted, as in section 3. The map has no
tilt or compass control; the scale bar, the Online status pill, the shade and viewshed overlays and the pack outlines
take no input.

### Top row, right (always; the Packs screen hides it): `<app>/packs/PacksScreen.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M1 | Settings | chip | opens or closes the settings sheet | always | - | |
| M2 | Packs | chip | opens the Packs screen | always | - | |

### Search, top left: `<app>/search/SearchOverlay.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M3 | Search places | text field | a place query and its result list | always | - | |
| M4 | (keyboard Search key) | gesture | picks the first result | while typing | - | |
| M5 | × | button | clears the query and the selection | a query or a result | - | |
| M6 | Keep offline around here | link | opens the area picker around the result | a result chosen | - | |
| M7 | (result row) | list item | moves the map to the place, with a marker | the list open | - | |

### Online line, under the search box: `<app>/online/OnlineOverlay.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M8 | Download this region | button | opens the area picker's region card for the view | online, nothing running | - | |
| M9 | (Downloading... line) | tap target | cancels the region download | a download runs | - | |
| M10 | (error line) | tap target | dismisses it | a start failed | - | |

### Track bar, right chip column under the top row: `<app>/tracking/TrackControls.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M11 | Waypoint | chip | a waypoint at the current fix | always | - | |
| M12 | Tracks, or the recorded distance in red | chip | unfolds the bar | folded | - | |
| M13 | Pause, Resume | chip | pauses or resumes the recording | open, recording | - | |
| M14 | Tracks | chip | opens or closes the tracks list | open | - | |
| M15 | Record, Stop | chip | Record opens the activity picker; Stop ends | open | - | |
| M16 | × | chip | folds the bar | open | - | |

### Recording panel and activity picker, under the chips: `<app>/tracking/ActivityPicker.kt`, `TrackControls.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M17 | On foot, Bike, Car or bus, Train, Boat, Flying | chip set | starts the recording as that activity, or sets a track's | after Record, or an activity link | `trackActivity` (as S32) | |
| M18 | Cancel | button | closes the picker | picker open | - | |
| M19 | (activity) › | link | opens the picker for the recording | recording | - | |
| M20 | The way back starts here | button | restarts the way-back clock here | recording, not navigating | - (per device) | |

### Tracks list, under the chips: `<app>/tracking/TrackControls.kt`, `<app>/sync/SyncNowAction.kt`, `<app>/share/ShareScreen.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M21 | Import | button | shows the GPX files to import | list open | - | |
| M22 | Pick file | button | the system file picker | after Import | - | |
| M23 | (file).gpx | list item | imports it | after Import | - | |
| M24 | Sync now | button | syncs in the background | list open | - | |
| M25 | Sync status | link | opens the Sync status sheet | list open | - | |
| M26 | Share | link | opens the Share screen | list open | - | |
| M27 | (track name) | list item | shows the track on the map | per track | - | |
| M28 | (activity) › | link | sets the track's activity | per track | - | |
| M29 | Profile | button | the elevation profile panel | per track | - | |
| M30 | Report | button | prints the trip report (system dialog) | per track | - | |
| M31 | Go | button | navigates the track | per track | - | |
| M32 | GPX | button | exports the track | per track | - | |
| M33 | edited on 2 devices | tap target | shows which value was kept | a sync conflict | - | |

### Track panels, bottom: `<app>/tracking/TrackControls.kt`, `<app>/tracking/BatteryExemption.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M34 | (waypoint name) | text field | edits the name | a waypoint selected | - | |
| M35 | Rename | button | saves the name | a waypoint selected | - | |
| M36 | Delete | button | deletes the waypoint | a waypoint selected | - | |
| M37 | Close | button | deselects | a waypoint selected | - | |
| M38 | Close | button | closes the profile | profile open | - | |
| M39 | Open battery settings | button | Android's battery list | first Record, until answered | per device | |
| M40 | Not now | button | remembers and closes | as M39 | per device | |

### Routes chip and list: `<app>/trips/TripControls.kt`, `<app>/routing/RouteImportPicker.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M41 | Routes | chip | opens the Routes list | right column, under the track bar | - | |
| M42 | Import | button | the system picker for a GPX route | list open | - | |
| M43 | Close | button | closes the list | list open | - | |
| M44 | (route row) | list item | opens the route in the trip card | list open (bottom sheet) | - | |

### Trip card, bottom: `<app>/trips/TripControls.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M45 | Done | button | closes the editor | editing | - | |
| M46 | Re-plan | button | re-plans untimed drive legs | such legs | - | |
| M47 | Download | button | confirms an offline download | only after a debug route | - | |
| M48 | Cancel, Close | button | closes that offer | as M47 | - | |
| M49 | Show N directions, Hide directions | toggle | the turn list | directions saved | - | |
| M50 | (water line) | tap target | opens the water list | `waterAlongRoute` on | - | |
| M51 | Stop keeping, Keep offline, Retry, Offline areas | link (one at a time) | the trip's offline corridor | by corridor state | - | |
| M52 | Drag, +Via, +Waypoint | toggle set | what a tap on the map does | editing | - | |
| M53 | Hike, Bike, Drive, Crow | toggle set | re-routes in that mode | editing | - | |
| M54 | Remove via: 2, 3, ... | button | removes that via point | more than two | - | |
| M55 | Go | button | navigates the trip | editing | - | |
| M56 | GPX | button | exports | editing | - | |
| M57 | Share link | button | a read-only link on the sync server | editing | - | |
| M58 | Delete | button | deletes the route | editing | - | |

### Route card, bottom (planning; hidden while navigating): `<app>/routing/RouteControls.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M59 | Hike, Bike, Drive, Crow | chip set | changes the mode and re-plans | a route phase | - | |
| M60 | Go | button | navigates the route | planned | - | |
| M61 | Keep | button | keeps it as a trip | planned | - | |
| M62 | Save | button | saves the route | planned | - | |
| M63 | GPX | button | exports | planned | - | |
| M64 | Turn on location | button | asks for the permission or opens Android's location settings | no location | - | |
| M65 | Try Crow | button | re-plans as a straight line | no route found | - | |
| M66 | Clear | button | clears the route | a route phase | - | |
| M67 | Change | link | the From choices | choices exist | - | |
| M68 | Your location | link | From is the position | offered | - | |
| M69 | Choose on map | link | the next long-press sets the start | offered | - | |
| M70 | Swap | link | swaps From and To | offered | - | |
| M71 | Show N directions, Hide directions | toggle | the turn list | directions | - | |
| M72 | (water line) | tap target | opens the water list | `waterAlongRoute` on | - | |
| M73 | (terrain line) | tap target | opens the terrain sheet | not Drive, elevation under the route | - | |

### Long-press menu, a card at the bottom: `<app>/tracking/LongPressMenu.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M74 | (long-press on the map) | gesture | opens the card at the point; during route planning sets the route's point | always | - | |
| M75 | Route to here | menu item | plans from the position to the point | card open | - | |
| M76 | Waypoint here | menu item | a waypoint and its sheet | card open | - | |
| M77 | Route from here | menu item | sets the start; the next long-press is the end | card open | - | |
| M78 | Keep offline around here | menu item | the area picker around the point | card open | - | |
| M79 | What can I see from here | menu item | the viewshed from the point | card open | - | |
| M80 | I am at (name), (height) | menu item | sets the readout's height from a named peak or pass | a tagged feature near the fix | - | |
| M81 | Cancel | menu item | closes the card | card open | - | |

### Viewshed sheet, bottom: `<app>/viewshed/ViewshedSheet.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M82 | Close | button | closes the viewshed | open | - | |
| M83 | Sort: Distance, Elevation, Bearing | link set | sorts the peaks | peaks in view | - | |
| M84 | (peak row) | list item | shows the peak | peaks listed | - | |

### Navigation card, top (while navigating): `<app>/nav/NavOverlay.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M85 | (speaker) | icon button | mutes speech for this navigation | `navSpeech` on | - | |
| M86 | Dismiss | button | the "cannot speak" notice | notice shown | - | |
| M87 | Snooze (N) min | button | silences the off-route alert | off route | reads `navSnoozeMin` | |
| M88 | Quiet until back | button | silences it until back on route | off route | - | |
| M89 | Unsnooze | button | ends the snooze | snoozed | - | |
| M90 | Recenter, Heading up, North up | button | recentres, or turns the map | navigating | `mapOrientation` (per device) | |
| M91 | Stop | button | ends navigation | navigating | - | |

### Pressure card, top: `<app>/readout/PressureCard.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M92 | Show the way down, Hide the way down | button | draws the quicker way down | a warning on a ridge | - | |
| M93 | Dismiss | button | closes the warning | a warning | - | |

### Position and orientation buttons, lower right: `<app>/controls/RecenterButton.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M94 | (padlock) | round button | Keep north up on or off | `mapControls` 3 | `orientationLock` | |
| M95 | (orientation) | round button | north up, heading up | `mapControls` 2 or 3 | `mapOrientation` | |
| M96 | (position) | round button | centres on the fix (and turns, with one button) or asks for location | always | `mapOrientation` | |
| M97 | (long-press on M95 or M96) | gesture | toggles the lock | layouts 1 and 2 | `orientationLock` | |

### Readout strip and credit line, lower left: `<app>/readout/ReadoutStrip.kt`, `<app>/about/`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M98 | (elevation with a pin mark) | tap target | says where and when the height was set | a pinned height | - | |
| M99 | (credit line) | link | opens the About screen | always | - | |
| M100 | ‹ Map | button | back from About | About open | - | |
| M101 | (licence and home page links) | link | opens the address | About open | - | |

### First-run card, bottom (no pack, no online mode): `<app>/firstrun/FirstRunCard.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M102 | Download the map around me | button | a pack around the fix | not dismissed | - | |
| M103 | Show the map around me | button | the area from online sources | direct mode or a server | - | |
| M104 | Choose from server | button | the server's catalog in Packs | a server | - | |
| M105 | Import pack files | button | the system picker | always on the card | - | |
| M106 | Settings | link | Settings at Sources | always on the card | - | |
| M107 | Not now | link | hides the card for the session | always on the card | - | |

### Update card, bottom: `<app>/update/UpdateOverlay.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M108 | OK | button | acknowledges a refusal | refused | - | |
| M109 | Install | button | installs | ready, not navigating | - | |
| M110 | After navigation | button | installs when navigation ends | ready, navigating | - | |
| M111 | Install now | button | installs at once | ready, navigating or waiting | - | |
| M112 | Not now | button | declines this version | ready | per device | |

### Settings sheet chrome: `<app>/SettingsSheet.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M113 | Close | button | closes the sheet | open | - | |
| M114 | ‹ Settings | link | back to the group list | one pane, a page open | - | |
| M115 | (system Back) | gesture | page to list, else closes | open | - | |

### Packs screen, full screen: `<app>/packs/PacksScreen.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M116 | Offline areas | chip | opens Offline areas | open | - | |
| M117 | Close | chip | closes | open | - | |
| M118 | From server | disclosure | the server's catalog, outlines on the map | open | - | |
| M119 | Refresh | chip | reloads the catalog | catalog open | - | |
| M120 | Set one in Settings › Sources › | link | Settings at Sources | no server | - | |
| M121 | (catalog row) | list item | shows the pack's box | catalog open | - | |
| M122 | Download, Update | chip | downloads or updates the pack | by status | - | |
| M123 | Delete | chip | deletes an installed catalog pack | installed | - | |
| M124 | (pack URL) | text field | a pack's address | catalog closed | - | |
| M125 | Download | chip | downloads from the URL | catalog closed | - | |
| M126 | Import files | chip | the system picker | catalog closed | - | |
| M127 | (pack row) | list item | makes it the primary pack and goes there | usable | - | |
| M128 | Delete | chip | deletes the pack | listed | - | |

### Offline areas screen, bottom sheet: `<app>/areas/AreasScreen.kt`, `CountryList.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M129 | Close, Back | button | closes, or leaves the country list | open | - | |
| M130 | Choose a country | button | the country list | main list | - | |
| M131 | Draw an area | button | the area picker in Draw mode | main list | - | |
| M132 | Keep each trip offline: Off, 250 m, 500 m, 1 km, 2 km, 5 km | chip set | the trip corridor width | main list | `tripCorridorM` (synced; no settings row) | |
| M133 | Show | button | fits the map to the area | per area | - | |
| M134 | Pin, Unpin | button | pins it | not a pack | - | |
| M135 | Retry, Update, Download again | button | restarts the download | not downloading | - | |
| M136 | Dismiss | button | removes a failed record | failed | - | |
| M137 | Rename | button | edits the name | not a pack | - | |
| M138 | (area name) | text field | the new name | renaming | - | |
| M139 | Save | button | saves the name | renaming | - | |
| M140 | Remove | button | removes the area | per area | - | |
| M141 | Filter by name or code | text field | filters countries | country list | - | |
| M142 | (country row) | list item | the country's summary card | country list | - | |

### Area picker card, bottom: `<app>/areas/AreaPicker.kt`, `AreaDraw.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M143 | Cancel | button | back or cancel | open | - | |
| M144 | Undo | button | undoes the last drawn corner | Draw mode | - | |
| M145 | Name | text field | the area's name | open | - | |
| M146 | 2, 5, 10, 20 km (1, 3, 5, 10 mi) | chip set | the radius | Around mode | - | |
| M147 | Other | text field | a typed radius | Around mode | - | |
| M148 | (the other detail levels) | link | switches the detail | alternatives exist | - | |
| M149 | Download | button | starts the download | no refusal | - | |
| M150 | (tap on the map) | gesture | adds a corner | Draw mode | - | |
| M151 | (drag a corner or a midpoint) | gesture | moves it, or adds a corner | Draw mode | - | |
| M152 | (long-press a corner) | gesture | removes it | Draw mode | - | |

### Storage screen, bottom sheet (from S4): `<app>/storage/StorageScreen.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M153 | Close | chip | closes | open | - | |
| M154 | Small 1 GB, Medium 8 GB, Large 128 GB | chip set | the cache budget | open | `cacheBudgetMb` (per device) | |
| M155 | Custom | chip | the size row | open | - | |
| M156 | Size in GB | text field | a typed size | Custom | - | |
| M157 | Set | chip | applies it | Custom | `cacheBudgetMb` | |
| M158 | 10 %, 25 %, 50 % of free | chip set | a size from free space | Custom | `cacheBudgetMb` | |
| M159 | Clear | chip | empties that store | clearable stores | - | |
| M160 | (sizes for offline areas) | chip set | the pinned areas' budget | Offline areas row | `pinnedCacheMb` (per device) | |
| M161 | Packs › | chip | opens Packs | always | - | |
| M162 | Offline areas › | chip | opens Offline areas | always | - | |

### Share screen and QR scanner, full screen: `<app>/share/ShareScreen.kt`, `QrScanner.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M163 | Close | chip | closes | open | - | |
| M164 | (nearby phone row) | list item | fills the address | phones found | - | |
| M165 | Address | text field | the other phone's address | open | - | |
| M166 | Code | text field | its code | open | - | |
| M167 | Scan QR code | chip | the scanner | open | - | |
| M168 | Sync | chip | syncs with it | open | - | |
| M169 | Use its maps | chip | adds it as a map source | open | per device | |
| M170 | Forget | chip | removes the phone | a phone kept | per device | |
| M171 | Download | chip | a pack from the phone | it has packs | - | |
| M172 | Get | chip | an area from the phone | it has areas | - | |
| M173 | New code | chip | a relay session through the server | open | - | |
| M174 | Sync again | chip | syncs the relay again | session running | - | |
| M175 | Stop | chip | stops the relay | session running | - | |
| M176 | Code from the other phone | text field | the relay code | open | - | |
| M177 | Join | chip | joins the relay | open | - | |
| M178 | Close | button | closes the scanner | scanner open | - | |

### Sync status sheet, bottom (from M25 or S51): `<app>/sync/SyncNowAction.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M179 | Sync now | button | syncs | open | - | |
| M180 | Close | button | closes | open | - | |
| M181 | Sync peer | text field | the sync server's address | open | per device | |
| M182 | Token | password field | its bearer token | open | per device | |
| M183 | Save | button | saves both | open | per device | |
| M184 | Clear token | button | removes the token | a token set | per device | |

### Other map gestures: `<app>/tracking/TrackOverlay.kt`, `<app>/trips/`, `<app>/controls/MapOrientation.kt`

| # | Label | Kind | Does | When | Key | Category |
|---|---|---|---|---|---|---|
| M185 | (tap a waypoint) | gesture | selects it, opens its sheet | a waypoint shown | - | |
| M186 | (tap with +Via or +Waypoint) | gesture | adds a via point or a waypoint | trip editor | - | |
| M187 | (drag a via point) | gesture | moves it and re-routes | trip editor, Drag | - | |
| M188 | (pan, pinch, two-finger turn) | gesture | moves, zooms, turns the map; turning off with the lock | always | `orientationLock` | |

## 5. What one client has and the other lacks

The web page lacks (the phone has): search; recording and the tracks list; navigation and its card; the live readout,
barometer and pressure warning; the long-press menu (route to or from here, waypoint here, keep offline, what can I see
from here, set my height); offline areas, packs and storage; sharing with a nearby phone; updates, diagnostics, problem
reports; contour labels, drainage, hillshade intensity, terrain shade and the viewshed as controls (the first three are
map options the page reads from the URL or ignores); the scale bar's both units; the position button's layouts and the
orientation lock; every navigation and recording setting.

The phone lacks (the web page has): a zoom control (pinch only); hiding all contours or all OSM layers at once; the
OSM archive choice (static demo only); building a pack on the server, and the server token for it; print options
for paper, orientation and grid (the system dialog sets paper; the phone offers scales only); saving a trip as a
`.dsync` file; the elevation profile on hover (the phone's profile is a panel); per-waypoint Show.

Both have it, in different places: map appearance (web: the side panel's Map options; phone: Settings › Map options,
not on the map); print (web: the panel; phone: Settings › Map, and a trip report in the tracks list); trips (web: a
Trips box top right; phone: the Routes chip, the route card and the trip card at the bottom); the server's address and
token (web: the panel's operator tools; phone: Settings › Sources for tiles and the Sync status sheet for sync, two
places for one server); the trip corridor (phone only, on the Offline areas screen, not in Settings); the cache
budget (phone: the Storage screen, reached from Settings); units (web: the panel; phone: Settings › Units and display).

## 6. Counts

| Client | Surface | Rows |
|---|---|---|
| Web | the side panel (W1 to W34) | 34 |
| Web | the map's controls and gestures (W35 to W40) | 6 |
| Web | the Trips box and its map gestures (W41 to W62) | 22 |
| Web | total | 62 |
| Phone | the settings sheet (S1 to S57; 44 row entries) | 57 |
| Phone | the map, its cards, menus, sheets and screens (M1 to M188) | 188 |
| Phone | total | 245 |
| Both | the shared surface (section 1: 25 rows, six marked one side) | 19 items |

Counted from the files at the pinned commit by hand and by a read-only sweep of the overlays; a chip set is one row,
help icons are not rows.
