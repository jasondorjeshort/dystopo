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
(T71; `-NoDevice` without an emulator, `-Skip`/`-Only <parts>`; `-Out` a folder relative to the checkout,
`out\sweep-<date>` by default, T154: an absolute path or one outside the checkout is refused).

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
load), the routing and search tours (T81, debug build), `android-probe.py`, `bothunits` (T139: the
scale bar's Both units carried between the debug build on the sweep's emulator and a second one on
`-MatrixEmuPort` through a standalone peer on `-BasePort`+3, about two minutes; a skip with its reason
when the second emulator does not start; T147: in every sweep with a device, `-NoBothUnits` leaves it
out: the default lives in the script, so every caller of it gets the same sweep), the release build (once per
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
APKs in `apk\`): `features`, `recenter` (T105), `diagnostics` (T154: the Diagnostics row by taps,
T143's checks), `permissions`, `upgrade` (from `-PrevRelease`,
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

T268: `startup` (debug build, after `bothunits`, before the release phase) runs `tools/android-startup.py --launches 5` (cold) over a seeded store (52 synthetic Rainier tracks through `POST /tracks/import` and the Rainier packs, seeded after the part's reboot check) and compares each `GET /debug/launch` stage's median, the first frame's and the map drawn's with `tools/launch-budget.json`: red on a median over its ceiling or a StrictMode kind a stage's declared work does not allow; the table is `summary.json` `parts.startup.table`. `android-startup.py --set-budget <two sweeps' startup.json> --budget tools/launch-budget.json` sets the ceilings; lower one by hand when an improvement lands, never raise one without a line in the log. <!-- end T268 -->

T191 (finding `docs/findings/2026-10-31-sweep-hung-when-the-paging-file-ran-out.md`, a 9.5 h hang): every part
has a ceiling, 900 s by default (`-PartTimeout`), with `device-tour` 1200 s a run, `device-boot` 1500, `update` 1500,
`check` 2400 and `matrix` 2400 built in (the release build has 1800 s of its own) and `-PartTimeouts part=seconds`
overriding any; at the ceiling the running program's process
tree is killed by its PID, the part's `steps.tsv` line reads `timeout` and names the PIDs, and the sweep goes on
(`-InjectHang <part>` replaces a part with a sleeping process tree to test this). Before `device-boot` and the
`bothunits` boot it waits up to 50 s for the console ports to be free and fails the part naming the holder. The
Python device tools bound every subprocess wait (`android_debug.run_bounded`), and a step that cannot start a
process (WinError 1455, the paging file full) fails at once with `hostOutOfMemory`.

T233, under T231's emulator guard: the sweep's boots pass `-EmuLimitGB` (12) to the guard, and before each device part,
each device tour run and first-run's `--direct` run its own emulator is stopped, left 60 s and booted again when the
qemu private bytes read from its lock pass `-EmuRebootGB` (6) or it is gone; each part's `steps.tsv` line gives its
private bytes at the start, peak and end and any reboot (`device\emu-private.tsv`, `device\emu-reboots.log`), and a part
that loses its emulator is killed 20 s later and reads `guard-stop` (with the guard's line) or `emulator-gone`; an
empty report or an error outside the parts no longer keeps the summary and the sheet from being written.

T273: a reboot's line in `device\emu-reboots.log` ends with `android-emu.ps1`'s wait for the guest's storage (the package manager and a writable `/sdcard`, so the next part's install finds it ready); the sweep reboots rather than loading a snapshot (a load leaves the emulator 0.3 to 0.8 GB over a reboot's level and undoes the guest's disk; finding `docs/findings/2026-11-05-emulator-private-bytes-grow-per-cold-launch.md`).

T270: `a11y` (debug build, after `startup`) runs `tools/android-a11y.py` at font scales 1.0 and 2.0 over about 21 screens (the map with each card up, the pickers, the recording panel, Tracks and routes, Offline maps, Settings and its pages, share, the first-run card) and lists each screen's clickable nodes under 48 dp of touch area (Compose's own, from `GET /semantics`), unlabelled, duplicate or overlapping, text outside its bounds, contrast under 4.5:1 and the map's controls left under a screen; red only on a finding `tools/a11y-baseline.json` does not list (`parts.a11y.new`); view `a11y11y-sheet.png` (on the sheet too). A fix strikes its baseline line; a new line needs its reason in the log. <!-- end T270 -->

From a task worktree the release-key parts (`diagnostics`, `upgrade`, `update`) need the key: copy
`tools\release.keystore` and `tools\release.properties` from the main checkout into the worktree's
`tools\` (gitignored; confirm with `git status --ignored`) and never commit them (`release-realism.md`).

## Output

`out\sweep-<date>\summary.json` (per part ok, seconds and the numbers the log entry quotes; paste it
into the log; T154: each device tour run lists under `servedBeforeBaseline` the views whose `tiles`
check counted a tile a pack answered before the view's baseline) and `sheet.png` (every contact sheet and screenshot on one page, the `update` part's
included since T99: view it; T111: the page is fitted into one browser capture, more thumbnail columns
first and a lower scale past that, and `summary.json` `sheetSize` gives the PNG's width and height, the
columns and scale used and `cut`, true when the page still reached the capture limit and its end may
be missing: then view each part's own sheets and screenshots and say so in the log entry; a
`sheetError` instead means no sheet was made), plus each part's log; exit 1 on any failure. `steps.tsv` gets one line
per part as it ends; since T99 it is written with read sharing and retried, so watching it
(`tail -F`, `Get-Content -Wait`) no longer stops the sweep. A sweep agent waits for `steps.tsv` with bounded foreground calls (a loop of a few minutes at most, then look again) and never ends its turn while the sweep or its emulator runs: the stop hook leaves an emulator alone only while the agent has background work registered (finding `docs/findings/2026-11-01-stop-hook-kills-the-emulator-of-an-agent-that-yields-its-turn.md`), and an agent killed after yielding leaves its emulator until the cap refusal names it. The `update` part also keeps the app's
update log, `ui\update\updater-log.txt`. T259: before a release pass `-Prev <the last release's sweep folder>` (and `-First <screen names>` from the merge bodies' "for the owner" lines): `changes.png` and `changes.json` land beside `summary.json`, whose `changes` has the count; view `changes.png`; `-SummaryOnly -Out <folder> -Prev <folder>` redoes only the summary and the diff (`docs/reference/release-realism.md`, The before-and-after sheet). `bash tools/sweep.sh` runs the same parts without the device, and
`.github/workflows/nightly.yml` runs it every night on main (artifact `sweep-nightly`).

## After it

1. An agent exercises by hand what the wave added (new routes and screens on the device) and reports
   with screenshots.
2. Findings become the next wave's sweep task (T11a, T19, T23 were these); record each with the
   `finding` skill.
3. Green: publish (`release` skill).
