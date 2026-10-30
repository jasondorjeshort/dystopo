# Changelog

One line per merged task, newest first, grouped by release (the Phase 0 log in [docs/PLANNING.md](docs/PLANNING.md) has the measurements and findings behind each line; task goals are in [docs/TASKS.md](docs/TASKS.md)). Releases are tag-driven drafts published by hand after verification (CLAUDE.md › Publishing); alphas ship on GitHub Releases.

## Unreleased

(nothing yet)

## v0.1.15-alpha (2026-10-30)

### 2026-10-30

- T175: Sweep leftovers 16: the crow route 500, the Record-as picker in the release-realism script, NOTICES (added at the close, 2026-10-30)
- T173: The sea-level pressure from public weather stations (after T161)
- T161: The readout on the phone (after T160 and T172)
- T172: The activity picker, the profile rows, the diagnostics rows, the exemption prompt (after T159)
- T163: The turn-around clock (when an emulator slot frees)
- T164: Route times fitted to your own tracks (after T171)
- T170: Sweep and tooling leftovers 15, and the small follow-ups of wave 21
- T159: Recording on the phone: the GPS stays on, the notification, the watchdog, the restart (after T158 and T162)
- T174: The drainage layer in the OSM archive (after T165, when the wave has room)
- T160: The live readout estimator in `shared` (after T158)
- T171: The track smoother and the stop cut (after T158)
- T166: Terrain-matched position without GPS: the measurement
- T167: The printed map sheet: devtool and the live viewer
- T162: The open view: the most recent of the last fix, the saved view and the pack's view; the `MapViewport` start-up crash
- T169: Proposal: the routing graph shipped with a pack, and the streaming build
- T165: Streams from the DEM: the algorithm and the devtool
- T158: The recorder core in `shared`: accuracy kept, gaps as data, activity profiles
- T168: Closed waves move out of the board: the rules (step A)

### 2026-10-28

- T157: A middle detail tier: elevation to z13 with vector to z14 (after T153)

## v0.1.14-alpha (2026-10-28)

### 2026-10-28

- T154: Sweep and tooling leftovers 14

### 2026-10-27

- T155: DEM tiles whose online fetch failed are answered again
- T153: The top "Download this region" button through the size-first card (after T152)
- T156: Interrupted area downloads survive the background and the process: the WorkManager chain (after T151)
- T151: Interrupted area downloads: the cursor, the repair at start and the cheap check (after T152)
- T152: Downloads past the heap limit: an estimate instead of nothing, the walk as rows, the download proceeds
- T150: The app rests in the background: the location listener, the polling loops, the activity stack, StrictMode in debug builds
- T149: No whole-pack graph or search build behind a download or at launch, and a graph build bounded by the heap

### 2026-10-26

- T148: The issue link stops asking for a paste that repeats the body

## v0.1.13-alpha (2026-10-26)

Offline areas can now be picked three ways: a country from a bundled list (Natural Earth boundaries, grouped by continent with a filter box, each country's size measured on the phone and opened at less detail unless full detail fits the budget), a disc around a point ("Keep offline around here" from the long-press menu or a search result, with presets and a radius), or a shape drawn on the map (corners added by tap, dragged, removed by a long press, with Undo), each ending in a summary that gives the size first with "Less detail" one tap away. Under the hood areas are polygons with a tile cover, a per-ring limit and a manifest version that older builds decline politely. Every build gains a Diagnostics row (heap, memory, trims, the previous process's exit reason, Copy report through a scrubber that removes locations) and Report a problem (crashes, freezes and kills kept on the phone, the last five, opened as a GitHub issue with locations removed). The UI thread no longer waits on a lock: two freezes after saving a view as a pack are gone, a watchdog in every build writes one dump per freeze, and a stalled map tile is refreshed without a reload. Navigation card chips lay out in the card's own pass, and a route can be replayed at a set speed for tests. Tooling: the sweep's both-units part by default, a build lock for parallel emulator starts, the memory probe's fallback levels.

### 2026-10-25

- T142: The area picker, first pass (second half; after T140 and T141 merge)
- T145: Crashes, freezes and kills kept on the phone and filed as a GitHub issue (second half; after T143 and T144 merge)
- T146: Phone follow-ups of wave 19: the chips and the replay
- T143: The Diagnostics row: heap and memory on every build, and the report scrubber
- T140: Polygon areas: the kind, the cover, the readers, the server and the phone's plan
- T144: The UI thread never waits: the freeze after saving a pack, a watchdog, and the OSM refresh
- T147: Tooling leftover sweep 13
- T141: The bundled country set from Natural Earth

## v0.1.12-alpha (2026-10-24)

The app asks Android for a large heap (192 MB to 576 MB on the API 35 emulator), so the phone's own rendering of an unseen place no longer waits on memory: cold views at zooms 8 to 11 went from 119 to 245 s to 84 to 138 s, and a memory-pressure trim also empties the decoded elevation blocks and the geoid memo. A roads-and-labels tile whose download stalled is fetched again after a short wait and drawn in place with no map reload. The navigation card gains a speaker button that mutes spoken directions for this navigation only and a notice with Dismiss when the phone has no text-to-speech engine or no English voice; the online indicator stays below the card while navigating. The Offline areas sheet opens over the trip editor without the trip card showing through, the trip corridor width is synced between phones, an edit during a corridor download cancels the old download first, and a raised pinned budget builds a corridor that was refused. Tooling: a route exported as a timed track for replays and the battery script, the update check's fixtures follow the build under test, a two-phone both-units part in the sweep.

### 2026-10-24

- T138: The memory follow-ups of T127, and the stalled online tile
- T137: Navigation card follow-ups, and a timed track from the route
- T139: Tooling leftover sweep 12
- T136: Offline areas and trip corridor follow-ups

## v0.1.11-alpha (2026-10-24)

Spoken turn directions, off by default: with the setting on, the phone says each turn once as the navigation card shows it, in your units, through the phone's own voice. Every saved or planned trip keeps a 1 km corridor offline around it by default (a setting from off to 10 km), counted against the pinned budget. The navigation card's header shrinks to its icon's height, the scale bar stays below the card and is measured on its own row under tilt, and the debug UI routes answer 503 in the background. The phone's own rendering with no server is admitted by the free heap and its caches are trimmed on memory pressure, so a cold view after a long session no longer runs out of memory (slower at zooms 8 to 11; a larger heap is a follow-up). A battery script measures tracking, navigation and an idle map per scenario on a real phone. Decided: stops and reroute (ADR 0040), polygon areas in the manifest (ADR 0041), uploading tracks to OpenStreetMap (ADR 0042, proposed). Tooling: the update check's tag follows the build, a two-emulator both-units check, the carry-back push policy, and the device tour's direct step honest with a server.

### 2026-10-23

- T135: Wave 18 sweep fix: the direct step with a server (#22)
- T133: Proposal 0041: polygon areas in the manifest (#15)
- T126: Spoken turn directions (#19)
- T127: The direct view out of memory after a full tour (#21)
- T134: Proposal 0042: OpenStreetMap trace upload (#14)
- T128: Android layout follow-ups of waves 14 to 17, and the background UI routes (#18)
- T130: An automatic corridor around every saved and planned route (#17)
- T131: Battery measurement on a real phone (#16)
- T129: Tooling leftover sweep 11, and the pending carry-backs (#20)
- T132: Proposal 0040: stops and reroute (#13)

## v0.1.10-alpha (2026-10-21)

Help pop-ups place themselves beside their icon and shrink their text a step at a time rather than cover it, and the navigation card gains help icons and texts (the card, no GPS fix, off route); at large font scales the card keeps the turn text whole (smaller steps, up to three lines, whole-word wrapping) and follows the font scale up to 1.5; the route fit and the trip editor's fit clear the chips at the top. The base-map fetch gives up on a stalled host within seconds instead of 30 s a try, so a view whose base map is unreachable still shows hillshade and contours; debug routes that need the map answer 503 with a reason while the app is in the background; the phone keeps a request log (`GET /stats/requests`) that feeds `cache-replay`; trip routes answer only after the main thread has applied the change (a race that could name the wrong trip or lose an offline offer); the routes tour records the body of every failed answer. Process: every pushed commit carries the owner's identity and the push scan accepts attribution trailers and GitHub's web committer; the base updated to Hatchery f7f41af (the guard reads `git rebase --exec`, the scan's range is bounded by the remote, the base's records arrive under `docs/hatchery/`); the server manual notes how older browser trips gain directions on the phone; the docs' hike example matches the tour.

### 2026-10-21

- T122: Android layout: font scale 2.0, the nav bar's help icons, the fit paddings (#12)
- T124: Android leftover sweep 10 (#10)
- T123: Android: the online fetch stall guard, the background debug routes, the phone request log (#11)
- T125: Desktop, server and tooling leftover sweep 10 (#8)
- T121: Update from Hatchery to its current main, and the ownership map (#9)
- T120: The push scan: attribution trailers and GitHub's merge committer (#7)

## v0.1.9-alpha (2026-10-18)

Route to here from where the phone is: the long-press card offers Route to here (one tap, planned from the phone's fix in the last mode), Waypoint here and Route from here (the two-tap flow), and the route card names both ends with Go first, a Change menu for the start (Your location, Choose on map, Swap), the fix's age when it is old, a wait when there is no fix yet, and an offer to turn location on; turn directions gain "then" cues ("Head northeast, then bear left") derived at display time, so saved trips and older phones are unaffected; the browser's Trips box saves directions with a trip and the sync simulator round-trips a browser bundle; help pop-ups close what they opened, the Storage rows and the Data and storage subtitle get icons, placements read "Settings › ... › ..."; the scale bar hides when the chips leave no room; the pinned cache goes to 1 TB with a free-space warning; a route is in place before the planner answers, so the directions request right after planning no longer answers 404 (the sweep's one red line). Process: the ideas folder in the base's grammar and the `triage` skill, the guard lets a subagent integrate `main`, the path-anchor check covers the board and the planning record.

### 2026-10-18

- T119: The routing tour's directions line fails in the post-wave sweep (#6)
- T115: Android leftover sweep 9 (#5)
- T114: Route to here, from where the phone is (#4)
- T116: Desktop, server and tooling leftover sweep 9 (#3)
- T117: The guard lets a subagent integrate `main`, and the Hatchery carry-back (#2)
- T118: The ideas folder in the base's grammar, and the `triage` skill (#1)

## v0.1.8-alpha (2026-10-16)

Help pop-ups behind a question-mark icon on every Map options row, the Offline areas sheet, the Settings rows, the track bar and the long-press card; turn directions saved with a trip, so a trip synced to a phone with no map pack still navigates, and a crow mode (a straight line with bearing and elevation profile) in the devtool, the server, the browser and the phone; one cache budget over online tiles, contours and shading, and source data, with presets, floors and derived-first eviction, the rule chosen by replaying a server log; the drive no longer cuts the Sunrise Point hairpin, an unsaved route survives a font scale restart, the credit line keeps up with the view, the update offer asks during navigation and installs when it ends, the share gate counts wrong codes per phone, the recentre button hides when the chips leave no room, a hidden scale bar reads back nothing; the server answers a stalled request body with 408 and the soak counts unanswered requests; the browser's Trips box lists directions and offers crow; the sweep sheet fits one capture; the release ships no stale tour sheet. Process: a critic pass on every wave plan, the git guard ignores shell redirections, ADRs 0033 and 0034.

### 2026-10-16

- T109: Help pop-ups behind a question-mark icon (#114)
- T111: Desktop, server and tooling leftover sweep 8 (#115)
- T110: Android leftover sweep 8 (#112)
- T113: The git guard and a force push from a detached verifier worktree (#113)
- T112: One cache budget over the per-kind caps (#111)
- T108: Directions stored with a trip, a crow mode, and the routing follow-ups of T102 (#110)

## v0.1.7-alpha (2026-10-15)

Turn directions in hike, bike and drive, text first; the recentre button's north-up, free-rotate and facing modes with one orientation state for the app; a scale bar and miles with one decimal everywhere; avalanche and steepness slope shadings with a legend, on the phone, the server and the devtool; settings grouped by theme; offline areas from another source update, pinned tiles survive a cache clear, the cards report their edges and wrap at font scale 2.0; the share gate hardened; the demo page's Trips box, the browser's slope select and scale bar, joined curved Arabic labels; the shade tiles' hole over a pack edge with a dead server closed. Process: the poisoned build-cache entry understood and the app's main compiles uncached, the sweep sets the app's server and waits for a quiet emulator, the status line in exec form, no product names in user-visible text with a check in `check`, ADRs 0031 and 0032.

### 2026-10-15

- T106: Android leftover sweep 7 (#109)
- T105: Sweep, build and tooling follow-ups (#108)
- T100: The recentre button's three orientation modes (#104)
- T107: Desktop and server leftover sweep 7 (#105)
- T103: Settings in tabs (#107)
- T102: Turn directions in hike, bike and drive (#106)
- T104: Two slope shadings, the shade tiles' hole over a pack edge, no product names in user text (#103)
- T101: A scale bar, and miles above feet (#102)

## v0.1.6-alpha (2026-10-14)

A cold view from the phone's own sources within 15 s to contours, with every stage of it measured; pinned areas and route corridors keep their map across a source version change, and the Offline button shows the size first; waypoints anywhere and a recentre button; kept peers restored across restarts, whole replies over a slow path, and a sharer's code that kept peers cannot rotate; one drive time, a wide overlay level and robustness on long drives; font scale 2.0 without overlaps; contour lines over a pack when the set server is unreachable; a freshly installed build checks for updates at once. Process: an owner override for the usage gate, the hooks run from the project directory, the sweep's update part on the sheet, ADRs 0029 and 0030.

### 2026-10-14

- T98: The z8 contour miss in the device tour, again (#101)
- T99: The sweep's update part, its sheet, `steps.tsv`, and the hook paths (#100)
- T88: The 15 s cold view and the direct-source leftovers (#96)
- T95: Pinned tiles survive a source version change; the Offline button shows the size first (#99)
- T96: Kept peers never rotate the sharer's code; a bounded linger (#98)
- T97: Server and devtool doc follow-ups of T89 and T92 (#97)
- T91: Android leftover sweep 6 (#95)
- T92: Desktop and server leftover sweep 6 (#93)
- T90: Peer sources: kept across restarts, whole replies over a slow path (#94)
- T89: Routing leftovers: one drive time, the z8 overlay, robustness (#92)

### 2026-10-13

- T93: A recentre button (the trivial version) (#91)
- T94: Usage gate: an owner override file, here and in Hatchery (#90)

## v0.1.5-alpha (2026-10-13)

Drive mode to and between trailheads on packs built with road data; long drives across packs planned through the server and navigated offline after a corridor download; areas of interest (download a trip's corridor, pin it, prefetch ahead on the route); bounded caches with a Storage screen and content versions on online tiles; one phone serving its packs to others over the sealed share link; a cold view from the phone's own sources at a third of the bytes; the first-launch crash in the navigation overlay fixed; the z8 contour miss fixed. Process: CLAUDE.md is rules plus pointers, with references, findings, ADRs and skills.

### 2026-10-13

- T87: Wave 12 sweep leftovers (#87)
- T84: Hatchery alignment (#86)
- T78: Peer sources on a trip (#84)
- T86: First-launch crash in the navigation overlay (#85)
- T85: Contours from the hillshade zoom on the phone (T80 follow-ups) (#82)
- T77: Long-distance routing (#83)

### 2026-10-12

- T75: Areas of interest: manifests, corridors, pinning, prefetch (#81)
- T74: Cache coherence and storage budgets (#80)
- T73: Drive mode: to the trailhead and between trailheads (#79)

## v0.1.4-alpha (2026-10-11)

Built-in updates from the releases page (checksum and certificate verified, one tap); mobile data allowed by default, no Wi-Fi gates; alpha releases ship the universal APK only.

- T83: In-app updates from GitHub Releases (#78)

## v0.1.3-alpha (2026-10-11)

The phone reads the elevation sources itself (no server needed), the Android 8 to 10 location crash and the missing notification prompt fixed, tiled routing across pack borders, bundled fonts and icons, server metrics and limits, the release-realism sweep.

- T81: Release realism sweep (#77)
- T80: The phone reads the sources itself (no Dystopo server required) (#76)
- T82: Server soak and operations (#75)
- T76: Tiled routing graph and cross-pack routes (#74)

## v0.1.2-alpha (2026-10-10), the first published alpha

Everything from the start of the project to the first-run fix (T79), plus the app version now coming from the release tag (versionName is the tag, versionCode the commit count). `v0.1.0-alpha` and `v0.1.1-alpha` were tagged earlier the same day and never published: the first dead-ended on a fresh phone, the second still reported version 0.1.0.

### 2026-10-10

- T79: First run on a real phone (#73)
- T71: One-command sweeps (#72)
- T66: Desktop and server leftover sweep 5 (#70)
- T72: Sync over the internet: TLS, discovery and a public peer (#71)
- T65: Android leftover sweep 5 (#69)
- T70: Map quality sweep (#68)
- T69: Canada and New Zealand elevation (#67)
- T67: Users on the server and trip sharing (#66)
- T68: Route planning in the browser (#65)
- T63: Cold start and tile scheduling (#64)
- T57: Android leftover sweep 4 (#63)
- T61: Navigation mode (#61)
- T58: Desktop and server leftover sweep 4 (#62)
- T60: Trips: routes as first-class items (#60)
- T64: Outdoors profile for phone-built regions and larger server packs (#59)
- T59: Norway and Austria bare-earth elevation (#57)
- T62: Public release preparation (#58)
- Wave 9 sweeps on main (coordinator, no PR)
- T49: Android leftover sweep 3 (#54)
- T51: Sync hardening (#56)
- T53: Pack catalog on the phone (#53)
- T50: Desktop and server leftover sweep 3 (#55)
- T54: Phone-to-phone sync on a local network (#52)
- T55: Release build (#51)
- T52: Standalone server module (#49)
- T56: swissALTI3D elevation source (#50)
- Wave 8 sweeps on main (coordinator, no PR)
- T42: DEM decode speed (#48)
- T41: Android leftover sweep 2 (#45)
- T45: Offline route planning over the trail network (#46)
- T44: Sync core and simulator (#44)
- T46: Outdoors packs from the server via Overpass (#42)
- T47: Map options on the phone (#43)
- T48: Continuous integration (#47)
- T43: Desktop leftover sweep 2 (#41)
- Wave 7 sweeps on main (coordinator, no PR)
- T33: Android leftover sweep (#40)
- T38: Online mode on the phone (#39)
- T36: Sprite sheet, POI collision and contour label density (#38)
- T40: Tracks polish (#37)
- T28: Android tour and multi-emulator scripts (#31)
- T34: Desktop leftover sweep (#36)
- T35: Contour tracing speed and warm-up (#35)
- T39: Offline place search (#34)
- T37: Server pack build from live sources (#33)
- T32: Live world mode for the viewer (#32)
- T29: Region packs on the device (#26)
- T27: Debug screenshots, location puck, loopback server hardening (#25)
- T25: Labels on the device and text scale (#27)
- T31: Tracks on the phone (#28)
- T30: Attribution and sources on the device (#30)
- T26: Contour tile performance and cache identity (#29)

### 2026-10-09

- T10: Android shell (#24)
- T23: Leftover sweep (#22)
- T24: Tracks, waypoints and GPX (#23)
- T20a: Renderer style for the outdoors schema (#21)
- T19: Sources and formats sweep (#20)
- T20b: The browser demo reads the outdoors schema (#18)
- T21: Datums beyond CONUS (#19)
- T22: The lidar → 10 m seam on real data (#17)
- T16: Outdoors profile (#16)
- T13: OSM over terrain (#15)
- T11a: Rendering and DEM follow-ups (#14)
- T17: World tour (#13)
- T18: Browser demo tour (#12)
- T12: DEM region packs (#11)
- T11b: Hardening sweep (#10)
- T9: Data source registry (#9)
- T8: Golden-image tests (#7)
- T7: Dependency license check (#8)
- T2: Slope-angle shading and multidirectional hillshade (#6)
- T6: OSM → PMTiles with Planetiler (#5)
- T4: DEM tile export (#4)
- T1: Contour readability (#3)
- T3: PMTiles v3 (#2)
- T5: MVT decoder (#1)

### 2026-10-09, before the task board

- Global coverage + common vertical datum
- 1 m lidar + first compositing
- USGS 3DEP 1/3″ source + `devtool compare`
- First pipeline working
