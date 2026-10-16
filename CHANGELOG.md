# Changelog

One line per merged task, newest first, grouped by release (the Phase 0 log in [docs/PLANNING.md](docs/PLANNING.md) has the measurements and findings behind each line; task goals are in [docs/TASKS.md](docs/TASKS.md)). Releases are tag-driven drafts published by hand after verification (CLAUDE.md › Publishing); alphas ship on GitHub Releases.

## Unreleased

### 2026-10-16

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
