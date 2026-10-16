---
name: sweep
description: Run the post-wave sweep (tools/sweep.ps1) through an agent and read its summary. Use after the last merge of a wave, before publishing a release, when asked for a sweep, or when the nightly sweep failed.
---

# Post-wave sweep

Moved from `CLAUDE.md` (Coordinating a wave, Post-wave sweeps) in T84. The coordinator launches the
sweep; it never runs it itself (ADR 0026): a Haiku-class agent runs it when only the JSON summary is
needed, a Sonnet-class agent when a part fails or a sheet must be viewed.

## The command

After the last merge of a wave: one command, `powershell -ExecutionPolicy Bypass -File tools\sweep.ps1`
(T71; `-NoDevice` without an emulator, `-Skip`/`-Only <parts>`).

It runs `./gradlew check`, `:server:installDist`, `python -I tools/notices.py --check` (regenerate
`NOTICES.md` when a wave added dependencies or data sources), `tour --zooms 9,12` over the public
areas, the demo tour (`tools/demo-tour.txt`) against `demo-server.py` and against `devtool serve` (the
Android stand-in), one live view per elevation source through `serve --live` (`|src=pack`,
`live-3dep`, `live-swissalti`, `live-kartverket`, `live-bev`, `live-linz`, `live-copernicus`, plus a
British Columbia view where 3DEP outranks HRDEM), `sync-sim` with skew, crash-restarts and paging,
then on a fresh emulator (`-Avd`, default `dystopo35-sweep-<date>`, deleted afterwards unless
`-KeepEmulator`; `-EmuPort` 5640, `-Port` 8990, `-AvdHome`) `android-emu.ps1 -Build`, the device tour
twice (T105: the debug build's server is the sweep's own `serve --live` on `-BasePort`+2, or none with
`-DeviceServer direct`, never the build's default 8765; the first view waits for a quiet device by its
load), the routing and search tours (T81, debug build), `android-probe.py`, the release build (once per
sweep, with `-PdystopoVersion=<describe>-sweep -PdystopoVersionCode=<commit count>` so it installs over
the published release), `android-release.ps1 -Port`, `apk-strings` (the release APK's strings through
`PrivacyCheck`; also with `-NoDevice`), and `first-run` (T79: the release build from a fresh install
with no pack and no assets, driven only by taps through `tools/android-firstrun.py` against the sweep's
own standalone server at the host's LAN IPv4: Settings › Sources, Test shows the catalog count,
"Download the map around me" at a mock fix at Rainier renders contours, then a second fresh install
imports the Rainier pair through the file picker; per-step ok and the screenshots under `first-run` in
`summary.json` and on the sheet; T105: the `--direct` run's steps, timings per place and screenshots too,
under `first-run.direct` and its own section of the sheet), then T81's release realism parts through `tools/android-ui.py`
(`docs/reference/release-realism.md`; T105: every one installs the sweep's own copies of the release
APKs in `apk\`): `features`, `recenter` (T105), `permissions`, `upgrade` (from `-PrevRelease`,
default the latest published release, its APK for the emulator's ABI downloaded with `gh`),
`background`, `network` and `update` (T83: the in-app update by taps against a mock releases API on
`-BasePort`+7, from the previous release's universal APK to this build to a second build of the same
commit with versionCode +1 made in the part); with `-Matrix` also `matrix` (font scale 2.0 and dark mode
on the sweep's emulator, then a fresh emulator per `-MatrixDevices` entry, default the minimum API 26
phone and an API 35 tablet in German, on `-MatrixEmuPort` (EmuPort+2) upwards, each deleted afterwards;
slow, so not in every sweep).

`-Known` (empty by default since T91 fixed `overlap:search/Settings`) lists filed defects those parts
report without failing; drop an entry when its fix lands. Its servers use `-BasePort` (8980) to +7 and are
stopped by PID; it refuses 8765, 8686, 8687 and console 5554. The `check` part retries once after
`:androidApp:clean` with `--no-build-cache` when a unit-test compile reports "Unresolved reference" (the
stale build state of finding `docs/findings/2026-10-14-debug-test-compile-loses-internal-after-cache-hit.md`)
and says so on its `steps.tsv` line.

## Output

`out\sweep-<date>\summary.json` (per part ok, seconds and the numbers the log entry quotes; paste it
into the log) and `sheet.png` (every contact sheet and screenshot on one page, the `update` part's
included since T99: view it; T111: the page is fitted into one browser capture, more thumbnail columns
first and a lower scale past that, and `summary.json` `sheetSize` says the size, the scale and whether
anything was cut), plus each part's log; exit 1 on any failure. `steps.tsv` gets one line
per part as it ends; since T99 it is written with read sharing and retried, so watching it
(`tail -F`, `Get-Content -Wait`) no longer stops the sweep. The `update` part also keeps the app's
update log, `ui\update\updater-log.txt`. `bash tools/sweep.sh` runs the same parts without the device, and
`.github/workflows/nightly.yml` runs it every night on main (artifact `sweep-nightly`).

## After it

1. An agent exercises by hand what the wave added (new routes and screens on the device) and reports
   with screenshots.
2. Findings become the next wave's sweep task (T11a, T19, T23 were these); record each with the
   `finding` skill.
3. Green: publish (`release` skill).
