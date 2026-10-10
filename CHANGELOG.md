# Changelog

Dystopo has no tagged release yet. Until the first one, this lists the work merged so far, one line per task
(seeded from the Phase 0 log in [docs/PLANNING.md](docs/PLANNING.md), which has the measurements and findings
behind each line; task goals are in [docs/TASKS.md](docs/TASKS.md)). Newest first. From the first release on,
each release gets a section here.

## Unreleased

(nothing yet)

## v0.1.1-alpha (2026-10-10), the first published alpha

Everything from the start of the project to the first-run fix (T79). `v0.1.0-alpha` was tagged earlier the same day and never published: its APK dead-ended on a fresh phone.

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
