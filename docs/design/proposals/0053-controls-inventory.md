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

## 1. The shared surface: map options both clients offer

| Item | Web: id, label, kind | Phone: row › label, kind | Key (shared) | Category |
|---|---|---|---|---|
| Hillshade method | `opt-hillshade` "Hillshade": select single light, multidirectional, off (Map options, panel) | Map › Hillshade: chips Off, Single, Multi | `hillshade` (`app.map.hillshade`) | |
| Hillshade on/off | `hill` "Hillshade (from our DEM tiles)": checkbox, page only (panel top) | the Off chip above | page state; `hillshade=off` on the phone | |
| Hillshade intensity | no control (read from `?hillshadeIntensity=`) | Map › Intensity: stepper in 10 % steps (shown when hillshade is on) | `hillshadeIntensity` | |
| Slope shading | `opt-slope` "Slope shading": select off, avalanche, steepness, with legend (live mode only; disabled in the static demo) | Map › Slope shading: chips Off, Avalanche, Steepness, with legend | `slope` | |
| Contour interval | `opt-interval` "Contour interval": select auto and the unit's intervals | Map › Contours: chip Auto and a `<` `>` stepper | `contourInterval` | |
| Contours on/off | `contours` "Contours, feet (computed live)": checkbox, page only | none (no way to hide contours) | page state | |
| Contour labels | no control (the page's own style ignores it) | Map › Contour labels: chips Sparse, Normal, Dense | `contourLabels` | |
| Trails | `opt-trails` "trails": checkbox | Map › Layers: chip Trails | `trails` | |
| Water | `opt-water` "water": checkbox | Map › Layers: chip Water | `water` | |
| Land cover | `opt-landcover` "land cover": checkbox | Map › Layers: chip Land cover | `landcover` | |
| POIs | `opt-pois` "POIs": checkbox | Map › Layers: chip POIs | `pois` | |
| Boundaries | `opt-boundaries` "boundaries": checkbox | Map › Layers: chip Boundaries | `boundaries` | |
| All OSM layers | `osm` "Trails, roads, water, names (OSM)": checkbox, page only | none | page state | |
| Drainage | no control (the page's own style has no drainage layer) | Map › Drainage: chips On, Off | `drainage` | |
| Night | `opt-night` "Night": checkbox | Map › Night: chips On, Off | `night` | |
| Units | `unitsel` "Units": select feet, metres, reset to the region default (panel); `?units=` for one load | Units and display › Units: Feet, Metres | browser `dystopo.units`; phone `units` (`app.units`) | |
| Text size | `text` "Text size": slider 0.8 to 2, page only, not stored | Units and display › Text size: `-` `+` stepper | phone `textScale` (`app.textScale`) | |
| Scale bar | display only, lower left, one unit | Map › Scale bar: chips One unit, Both units; the bar lower left | phone `scaleBar.both` (`app.scaleBar.both`) | |
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
Sources entry's parts. Synced keys not on any row: `tripCorridorM` (the trip card, section 4). Per-device keys on no
row: `navStartM`, `decoder`, `debugFixedPort` (debug routes only).

## 4. Phone: the map's controls, cards, menus and screens

(Filled from the sweep of `<app>/MainActivity.kt` › `MapScreen` and the overlays it composes; see below.)

## 5. What one client has and the other lacks

(See below.)

## 6. Counts
