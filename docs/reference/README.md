# Reference

Durable facts the project depends on: how a tool, command, route, platform or this machine behaves.
One topic per file, named by topic, never by date. Each file starts with frontmatter
`verified: YYYY-MM-DD` and `source: <url or "observed">`, then facts, commands that work, and
gotchas. Cap 300 lines; split by topic when near it. When a fact turns out to be wrong, fix it in place
and bump `verified:`; do not append history. A fact gets here directly when it is certain, or by
promotion from a finding (`finding` skill). Most of these files were paragraphs of `CLAUDE.md` until
T84 (ADR 0020); the endpoint and command lines in them are kept current by the task that changes
them, on the same branch.

| File | Topic | Verified |
|---|---|---|
| `machines.md` | The two Windows accounts: main checkouts, worktrees, cache, JDK, SDK, AVDs, the coordinator's servers and PIDs, every port in use | 2026-10-12 |
| `windows-and-shells.md` | PowerShell 5.1 and Git Bash quirks, `git -C`, proving a push, deleting worktrees with long paths, processes by PID, shared state | 2026-10-14 |
| `code-layout.md` | The modules (`shared`, `devtool`, `serverlib`/`server`, `tilebuild`, `tools`, `areas.json`) with the detail an editing agent needs | 2026-10-12 |
| `devtool-commands.md` | Every devtool command with its options, fonts in PNGs, working directory and cache | 2026-10-12 |
| `live-server.md` | Browser demo, live world mode and map options, trip editor, `devtool serve` as the phone stand-in, the coordinator's servers | 2026-10-12 |
| `android-app.md` | What the app serves, the build and emulator scripts, the settings sheet's groups, the device tour, the loopback token and the debug port | 2026-10-14 |
| `android-emulator.md` | Several emulators side by side, host virtualization, GPU and API quirks, the server a task emulator uses, first-boot load | 2026-10-14 |
| `android-debug-routes.md` | Every debug route family (health, screenshot, camera, stats, settings, storage, packs, routes, navigation, areas, probe) | 2026-10-12 |
| `android-phone-over-adb.md` | The owner's phone over wireless adb: connecting, the debug build's signing, reading the debug port, and how coders request phone tests | 2026-10-28 |
| `android-tracks-and-sync.md` | Tracks and GPX, phone-to-phone sync and the relay, sync with the server | 2026-10-12 |
| `android-online.md` | The phone reading the sources with no server, the cold view stage by stage, no Wi-Fi gates, pinned areas and content versions, in-app updates, first run and cleartext | 2026-10-14 |
| `android-battery.md` | `tools/android-battery.py`: the app's battery use per scenario (tracking screen off, navigation, idle map) from `dumpsys batterystats`, how to run it on a phone, the report's fields, results | 2026-10-23 |
| `android-release.md` | The release build and signing, deep links, `android-release.ps1` | 2026-10-12 |
| `release-realism.md` | The `tools/android-ui.py` parts that drive the release build by taps, and the sweep's device parts | 2026-10-14 |
