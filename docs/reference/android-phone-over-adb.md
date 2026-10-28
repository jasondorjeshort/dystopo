---
verified: 2026-10-28
source: observed on the second PC with the owner's phone (wireless adb, debug build signed with the release key)
---

# The owner's phone over wireless adb, and phone tests on request

The owner's phone is the only real device here. Agents never run adb against it. A coder who needs a run on the phone
asks the coordinator, who runs it through the path below and returns the readings. The phone's address, serial and
pairing code are not written in this repo: take them from the phone's Wireless debugging screen each time.

## Connect (once per session; the connection drops when the phone sleeps or changes network)

1. Pair, with the code and the pairing port from the phone's Wireless debugging › Pair device screen:
   `adb pair <ip>:<pairing port> <code>`
2. Connect, with the port on the main Wireless debugging screen (not the pairing port):
   `adb connect <ip>:<port>`
3. Check: `adb devices` lists `<ip>:<port>  device`. Use that serial with `-s` on every command.

Without Wi-Fi, plug in USB, accept the prompt, then `adb tcpip 5555` and unplug; `adb connect <ip>:5555` after that
(`android-battery.md`).

## The debug build on the phone

- The phone runs the release build from the draft (no debug routes) unless the debug build is installed. The debug build
  is signed with the local debug key, so `install -r` over the release build fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  (signatures differ). The working install is a debug APK re-signed with the release key (`apksigner sign` with the
  release keystore, the owner runs it; the passwords stay with the owner), then `adb install -r -d <apk>` (the debug
  version code is lower than the release one).
- A release-key-signed debug APK is for the owner's phone only; it is never a release and never goes to anyone else.
- Uninstalling the app deletes its data on the phone (settings, tracks, packs). Never do it without the owner's yes.

## Reading the debug server

1. Start the app (`adb shell monkey -p io.github.jasondorjeshort.dystopo -c android.intent.category.LAUNCHER 1`) or
   force-stop and start it. The debug port and token are on one logcat line:
   `adb -s <serial> logcat -d -s Dystopo:I | grep "debug server"` (`debug server on port N token T`). The port changes on
   every launch.
2. Forward it: `adb -s <serial> forward tcp:8686 tcp:N`. Re-forward after every launch.
3. Call the routes with the token: `curl -H "X-Dystopo-Token: T" http://localhost:8686/camera` (see
   `android-debug-routes.md` and each `*Routes.kt` KDoc).

Crashes: `adb -s <serial> logcat -d -b crash` shows the `FATAL EXCEPTION` with its stack; a process that died is in
`logcat -d` as `has died`.

## Phone tests on request

1. A coder who needs a phone run writes the request in its report: the build (branch and commit), the steps, the
   routes to read, and what result would settle it.
2. The coordinator runs it through this page's path, on the debug build it has installed, and records what it saw: the
   route readings, `logcat` lines, and the `Log.i` lines the steps name. It does not change code.
3. The findings come back to the coder's report as readings, and into `docs/findings/` when they settle a question.
   A test that changes the phone's state (`POST /location`, a relaunch, a pack change) says so in the report and puts the
   state back.
4. A phone run the coder reports as passed is checked by the coordinator's own reading, the same as an emulator run.

Routes that change state (`POST /location` injects a fix, `POST /camera*` moves the camera, `POST /packs/*` changes packs,
`/reload` restarts the map) are for the run that needs them; read-only routes (`GET /camera`, `/location`, `/stats`,
`/online`, `/areas`, `/health`) are the default.
