---
verified: 2026-10-14
source: moved verbatim from CLAUDE.md in T84 (text as of c875bc9); observed in the tasks named in each paragraph
---

# The Android app: build, emulator scripts, device tour, loopback token

How the app serves the map and how agents drive it. Emulator setup and several emulators side by side: `android-emulator.md`. Every debug route: `android-debug-routes.md`.

## What it is and the scripts

`androidApp/` (Compose + MapLibre Native + `shared`) shows a region pack: hillshade from the DEM PMTiles (MapLibre `raster-dem`), OSM from the outdoors PMTiles, and **contour tiles generated on the device** (`shared/.../tiles/ContourTiles.kt`, cached on disk). MapLibre can't read PMTiles, so the app runs a loopback-only HTTP server (port 8686) serving `/dem`, `/osm`, `/contours`, `/glyphs` and `/style.json`; in debug builds the debug routes are on a second loopback server on a random port (T57, below) (the MapLibre style is translated from the devtool's `DefaultOsmStyle` by `shared/.../maplibre/MapLibreStyle.kt`). The module and `shared`'s android target exist only when an SDK is installed (`settings.gradle.kts` looks at `local.properties` / `ANDROID_HOME`), so SDK-less machines still build.
```
powershell -ExecutionPolicy Bypass -File tools\android-sdk.ps1     # SDK under %USERPROFILE%\Android\sdk + local.properties (no admin)
./gradlew :androidApp:assembleDebug
powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Build   # headless emulator, install, push out/rainier-*.pmtiles, launch, forward -Port to the debug server
powershell -ExecutionPolicy Bypass -File tools\android-push-pack.ps1 -Dem out\rainier-dem.pmtiles -Osm out\rainier-outdoors.pmtiles   # packs + glyphs only (-NoOsm: DEM only)
powershell -ExecutionPolicy Bypass -File tools\android-emu.ps1 -Avd <avd> -EmuPort <n> -Port <p> -Forward   # after an app restart: re-read the debug port and token, re-forward
python -I tools/android-check.py --port 8686 --serial emulator-5554 --views-file tools/android-tour.txt   # device tour: JSON summary, out/android-tour-sheet.png; exit 1 on failure
```

## Map options (the Map section of the settings sheet)

`androidApp/.../options/MapOptionsSection.kt`; each choice is the `POST /settings` key of the same name (`android-debug-routes.md`), except the Scale bar row (T101, `POST /scalebar?both=on|off`). Rows: Hillshade (Off, Single, Multi) and its Intensity, **Slope shading** (T104: Off, Avalanche, Steepness, with "Slope shading is not an avalanche forecast." under it and, when on, a legend: a caption and one swatch per band, the band colour at the map's tint opacity over the paper tone; bands and wording in `GET /shade` `slopeLegend`, sources in docs/PLANNING.md › Slope shading), Contours, Contour labels, Layers, Night, Scale bar. Each row has its label above its choices, which wrap (a `FlowRow`), so nothing is squeezed at font scale 2.0. To screenshot it: `POST /settings/ui?open=1`, scroll the sheet to its end, `GET /screenshot`.

## The device tour (`android-check.py`)

`android-check.py` relaunches the app and clears the contour cache first, then per view sets the camera, waits for idle, screenshots, diffs `/stats` and reads `/features`; it fails a view on: style not loaded, not idle, tile server errors, an expected source (pack covers the view) serving no tiles, a blank screenshot, no contour lines rendered, or labels placed but not drawn (`/features/text`); and the tour when no glyphs were served for a font the style uses. Contour tiles over `--contour-budget-ms` (1000) are warnings, failures with `--strict` (timings swing with host load). It also reports the app's logcat warnings. Look at the contact sheet (`out/android-tour-sheet.png`). T98: the summary records the server the app reads and its state at the start and the end (`setup.online`, `online`, and an `online:` line on stderr): a debug build on an emulator defaults to `http://10.0.2.2:8765`, the host's live viewer, so a tour runs online when that server is up and with an unreachable server when it is not (`POST /settings?server=` for no server: the phone reads the sources). A view whose contour tiles were sampled with no elevation over a DEM pack (`contourGeneration.noDemOverPack`) gets a warning naming them.

## Loopback token and the debug port

**Loopback token** (T49, `LoopbackToken.kt`): any app on the device can open 127.0.0.1:8686, so every request, tiles and the style included, must carry the app's per-launch random token, as `?t=<token>` or an `X-Dystopo-Token: <token>` header; anything else gets 401 (`/health` `server.tokenRefused` counts them). The style MapLibre loads puts `t=` in every tile, glyph and shade URL. **Debug port** (T57): debug builds serve the debug routes on a second loopback server on a random port per launch and announce it, with the token, only in logcat: `I/Dystopo: debug server on port N token T` (other apps cannot read another app's logcat, so the token stays with the app's process). Port 8686 then answers like a release build: map routes with the token, 404 for every debug route, no `/health`. `android-emu.ps1`, `android-check.py` and `android-probe.py` read the line through `adb logcat` (helper `tools/android_debug.py`) and forward the host port to it, again after a relaunch; by hand: `adb -s <serial> logcat -d -s Dystopo:I`, then `adb -s <serial> forward tcp:8686 tcp:<N>` and `curl -H "X-Dystopo-Token: <T>" localhost:8686/stats`. The setting `POST /settings?debugFixedPort=1` (from the next launch) puts the debug routes back on 8686 with the T49 bootstrap, where `GET /health` without the token answers only `{"token": "..."}` to a loopback caller; the logcat line then names 8686. Release builds have no debug routes and no bootstrap, so there the token never leaves the process.
