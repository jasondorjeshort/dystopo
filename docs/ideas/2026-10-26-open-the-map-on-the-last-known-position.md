---
date: 2026-10-26
kind: bug
---

# Open the map on the last known position, not the middle of the DEM pack

The owner (2026-10-26): on launch the map zooms in on the opposite side of the country. The position that was last known with a fix should be where the app opens, not the first thing it finds. Priority for wave 21: high.

`androidApp/src/main/kotlin/io/github/jasondorjeshort/dystopo/app/MainActivity.kt` (`onCreate`, the `start` camera) picks a deep link (T55), else the centre of the DEM pack's bounding box at z12, else a world view. Nothing saves the camera when the app stops and nothing reads the device's last-known location before the first frame. With a wide pack (the Rainier DEM pack is the wide one, `docs/reference/machines.md`) the box centre can be far from the user. A later fix does not correct it: `MapController.onFix` moves the puck, and `MapOrientation.onFix` recentres only in FOLLOW mode, which a cold launch is not in. `firstrun/FirstRun.kt` (`lastKnown`) already reads the last-known location, but only for the first-run flow.

To reproduce: install a build with a wide DEM pack, grant location, put the phone anywhere outside the pack's centre, open the app; the camera starts on the pack centre.

## Wanted

Start camera: the deep link if there is one (an explicit request); otherwise the most recent of the stamped sources, each stored with the time it was taken: the device's last-known location (`Location.time`), the app's own last fix (kept so it survives the OS dropping it), and the saved camera (lat, lon, zoom, bearing, written when the activity stops, with that time). Neither the saved camera nor the app's own fix exists in the code yet; both are new. With no stamped source: the pack centre, then the world view. The saved camera is usually newer than the app's own fix, since it is written at stop, so it wins after an ordinary close (zoom and bearing kept); a newer device fix, for instance one another app produced while this one was closed, wins after travelling. When the first real fix arrives after a start from an older source, move to it once and then leave the camera alone, so a user who has already panned is not pulled back.

Agent-testable (CLAUDE.md, Rules): a debug route that reports which source chose the start camera (`deepLink`, `lastFix`, `savedCamera`, `pack`, `world`) with the lat, lon and zoom, and a unit test of the choice as a pure function of its inputs. Docs: the route in `android-debug-routes.md` and its `*Routes.kt` KDoc; a line in `docs/reference/android-app.md` on where the last fix is stored.

Left to the implementer (owner, 2026-10-26): anything beats today's start, so build the simplest version that works and try it on the phone. Recentring immediately on the first new fix is acceptable in place of "move once, then leave the camera alone"; the implementer picks what feels right and says which in the report. If the choice is a close call, a setting (ADR 0044).

## Open

- How old may the stored fix be before it is not used (and with no location permission, is the stored one used at all)?
- Where the stored fix lives (the app settings, synced or not) and whether it is private data that stays off backup.
