---
verified: 2026-10-25
source: observed (T131, T146, `tools/android-battery.py` on an API 35 emulator); Android's `dumpsys batterystats` checkin format
---

# Battery measurement (`tools/android-battery.py`)

Numbers for the app's battery use on a real phone, scenario by scenario, read from `dumpsys batterystats` for the
app's uid. The emulator proves the mechanics only: its battery is simulated and its power profile a placeholder, so
its power figures are meaningless (`meaningful: false` in the report). The script's docstring is the full reference
for its options.

## Running it on a phone

1. Install the debug build (`./gradlew :androidApp:assembleDebug`, then `adb -s <serial> install -r
   androidApp/build/outputs/apk/debug/androidApp-debug.apk`) and push the Rainier pack as a named pack:
   `powershell -ExecutionPolicy Bypass -File tools\android-push-pack.ps1 -Name rainier -Serial <serial>` (the nav
   and idle scenarios use it; the drive is Paradise to Sunrise). Start the app once and grant location.
2. Connect over Wi-Fi so the cable does not charge the phone during the run: with USB attached,
   `adb -s <usb serial> tcpip 5555`, `adb connect <phone address>:5555`, then pull the cable. The serial is then
   `<phone address>:5555`. Charge the phone well above any power-saver threshold first; turn battery saver off.
3. Run, from the repo root (about 1.5 h with the default 30 minutes per scenario, plus a few minutes of setup):
   ```
   python -I tools/android-battery.py --serial <phone address>:5555 --port 8760 --save-stats --out out/battery-phone.json
   ```
   `--minutes N` sets each scenario's length, `--scenarios tracking,nav,idle` picks some, `--drive-kmh` the replayed
   drive's speed (50), `--keep-tracks` keeps the recorded track, `--no-unplug` skips the simulated unplug.
   `--save-stats` writes each scenario's raw `dumpsys batterystats -c` next to the report.
4. A phone with a secure lock screen: the screen-on scenarios need the map in front, which `wm dismiss-keyguard`
   cannot do past a PIN. After the tracking scenario the script asks on stderr for the phone to be unlocked and
   waits `--unlock-wait` seconds (300) before it fails that scenario; or set the screen lock to none for the run.

Exit 0 when every scenario ran and has a reading, 1 when one failed (its `error` in the report), 2 when the phone or
the app could not be reached.

## What each scenario does

Each scenario prepares the app, then marks the device unplugged (`dumpsys battery unplug`) and resets the stats
(`dumpsys batterystats --reset`), runs for `--minutes`, reads `dumpsys batterystats -c`, and stops what it started.
`dumpsys battery reset` restores the real charging state at the end of each scenario and of the run, also on failure.
Screen-on scenarios raise `screen_off_timeout` for their length and restore it afterwards.

- **tracking**: `POST /tracks/record?source=gps`, then the screen off (`input keyevent KEYCODE_SLEEP`); the phone's
  own GPS feeds the recorder through the foreground service. Afterwards the screen on, `POST /tracks/stop`, and the
  track deleted unless `--keep-tracks`. On an emulator the script sets one `adb emu geo fix` at Paradise first.
- **nav**: `POST /route` with the `paradise-sunrise-drive` line of `tools/routing-tour.txt` (drive mode, 82 km),
  `POST /nav/start?route=current`, then `POST /location/replay?route=current&kmh=<--drive-kmh>&rate=1` (T146: the
  app replays the current route as its timed track, one fix a second at that speed, with no request body, so no
  1 MiB limit; the plain route GPX has no times, and replayed bare it would move a whole route segment a second).
  The replay starts again when `GET /location/replay` says `finished` before the scenario ends. Navigation keeps the screen on.
  The phone's real GPS stays on beside the replayed fixes (`gpsMs` counts it).
- **idle**: the map at Paradise, z13, loaded before the reset; nothing else runs.

The debug server is polled about once a minute during nav (replay progress) and not at all during the others, so the
measurement itself adds little; adb over Wi-Fi costs the phone some radio time, which falls on the system's uid.

## Report fields

Top level: `device` (serial, model, Android version, `emulator`), `uid`, `minutesPerScenario`, `meaningful`, `note`,
`ok`, and `scenarios`, one object per scenario:

- `screen`: `expected` (on or off), `atStart` and `atEnd` (`mWakefulness` from `dumpsys power`: Awake, Asleep or
  Dozing).
- `battery`: `levelStart`, `levelEnd`, `dropPct` (whole percent, so useful only over long runs and on battery),
  `pluggedAtStart` (what `dumpsys battery` reported while unplugged was simulated: empty).
- `app` (the app's uid since the reset):
  - `mAh`: batterystats' estimate of the app's consumption (its `pwi` line), from the phone's power profile.
  - `sharePct`: `mAh` over the device's computed drain in the same interval; null when that drain is 0.
  - `cpuMs`: `user` and `system` CPU time.
  - `wakelockMs`: partial wakelock time with overlaps counted once (`awl`); `wakelockSumMs` and `wakelockCount` the
    sum and count over the named wakelocks; `wakelocks` the five longest (name, ms, count).
  - `screenWakelockMs`: full wakelock time, the screen kept on (navigation's keep-screen-on shows here).
  - `gpsMs`: time the app held the GPS (sensor -10000).
  - `network`: Wi-Fi and mobile bytes received and sent.
  - `topMs` and `foregroundServiceMs`: time in the top (on screen) and foreground service process states.
- `device`: `capacityMah` (the power profile's battery), `computedDrainMah`, `screenOnMs`, `realtimeMs` (time on
  battery since the reset), `version` (the checkin format).
- `extra`: what the scenario saw: tracking's track (`stats.points`, `durationS`, `deleted`); nav's route, replay
  (`fixes`, `driveMinutes` from the replay's answer, `starts`, `replayAtEnd` with `sent`, `fixes`, `running`, `finished`, `source`) and navigation state at the end (`navAtEnd`, with
  `keepScreenOn`); idle's `settled` and `activityAtEnd`.
- `statsFile` (with `--save-stats`), `error` when the scenario failed, `ok`.

## Results

One line per run: date, device, minutes per scenario, then per scenario the app's mAh and share, CPU, wakelock, GPS
and level drop. Emulator lines prove the mechanics; their power numbers are meaningless.

- 2026-10-23, API 35 emulator (pixel_6 profile, no server), 2 min, **power numbers meaningless** (computed drain 0,
  so no share; level 100 throughout): tracking 0.007 mAh, CPU 3.6 s, wakelock 13.8 s, GPS 120 s, screen Dozing then
  Asleep, foreground service 119.8 s; nav 0.007 mAh, CPU 117.7 s (mostly system: the emulator's GL pipe), screen
  wakelock 120.1 s, GPS 120 s beside the replay; idle 0.0 mAh, CPU 3.1 s, GPS 0. Mechanics proven; the phone's line
  goes here when the owner runs it.
- 2026-10-25, API 35 emulator, 1 min, the T146 path (`POST /location/replay?route=current&kmh=50&rate=1`, no body):
  `ok` true, all three scenarios; nav replayed 60 of 5,910 fixes in the minute (98.5 min drive), GPS 60.7 s beside
  it, CPU 49.8 s; power numbers meaningless as above.
