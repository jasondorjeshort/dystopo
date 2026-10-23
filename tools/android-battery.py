"""Battery measurement of the Dystopo debug app on a connected phone (T131): one JSON report per run.

    python -I tools/android-battery.py --serial <serial> [--port 8760] [--minutes 30]
                                       [--scenarios tracking,nav,idle] [--out out/battery.json]

Runs each scenario for --minutes (default 30) and reads `dumpsys batterystats` for the app's uid after it; the stats
are reset at the start of every scenario, so each reading covers that scenario only:
  - tracking: a track recorded from the phone's GPS (`POST /tracks/record?source=gps`) with the screen off
              (`input keyevent KEYCODE_SLEEP`); the track is deleted afterwards unless --keep-tracks.
  - nav:      navigation with the screen on along the Paradise to Sunrise drive in the Rainier pack (the
              `paradise-sunrise-drive` line of tools/routing-tour.txt): `POST /route` in drive mode, `GET /route/gpx`,
              `POST /nav/start?route=current`, then `POST /location/replay` with that route retimed to one fix a
              second at --drive-kmh (the route's GPX has no times; replayed bare it would jump a whole segment a second).
              Replayed again from the start if it ends before the scenario does.
  - idle:     the map on screen at Paradise (z13, the Rainier pack), nothing else running.
Screen-on scenarios raise the screen-off timeout for their duration and restore it afterwards.

The app's numbers (per scenario, under `app`): `mAh` (batterystats' estimate of the app's consumption) and `sharePct`
(of the device's computed drain over the same interval), `cpuMs` (user and system), `wakelockMs` (partial wakelocks
held, overlaps counted once; the longest listed), `screenWakelockMs` (the screen kept on), `gpsMs` (time the app held
the GPS on), `network` (bytes over Wi-Fi and mobile), `topMs` and `foregroundServiceMs` (process state). `battery` is
the level before and after (whole percent: only meaningful over long runs), `device` the whole-device estimate. The
field list with its meanings is docs/reference/android-battery.md; `meaningful` is false on an emulator, whose battery
is simulated and whose power profile is a placeholder (the mechanics are proven there, the numbers are not real).

The device must count as unplugged for batterystats to accumulate: the script runs `dumpsys battery unplug` before
each scenario and `dumpsys battery reset` at the end (also on failure), unless --no-unplug. On a phone connected by
USB the cable still charges it, so the level change means nothing there; connect over Wi-Fi (`adb tcpip`, then
`adb connect`) and pull the cable for a real drain. The app must be the debug build (its debug routes) with the
Rainier pack pushed as a named pack (tools/android-push-pack.ps1 -Name rainier -Serial <serial>) for the nav and idle
scenarios. The reference: docs/reference/android-battery.md.

Exit 0 when every scenario ran, 1 when one failed (its `error` in the report), 2 when the device or app could not be
reached. Needs only the standard library.
"""
import argparse
import datetime
import importlib.util
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

_spec = importlib.util.spec_from_file_location("android_debug", os.path.join(os.path.dirname(os.path.abspath(__file__)), "android_debug.py"))
android_debug = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(android_debug)
adb = android_debug.adb

PACKAGE = android_debug.PACKAGE
ACTIVITY = PACKAGE + "/io.github.jasondorjeshort.dystopo.app.MainActivity"
SCENARIOS = ("tracking", "nav", "idle")
DRIVE_LABEL = "paradise-sunrise-drive"
IDLE_VIEW = (46.7864, -121.7366, 13.0)  # Paradise, inside the Rainier pack
GPS_SENSOR = "-10000"  # batterystats' sensor handle for the GPS


def log(*a):
    print(*a, file=sys.stderr, flush=True)


class App:
    """The app's debug server through a forwarded host port; the port and token come from logcat (android_debug)."""

    def __init__(self, exe, serial, port):
        self.exe, self.serial, self.port = exe, serial, port
        self.base = f"http://127.0.0.1:{port}"
        self.token = None

    def call(self, path, method="GET", body=None, timeout=60.0, raw=False):
        for attempt in (0, 1):
            if self.token is None:
                _, self.token = android_debug.bootstrap(self.exe, self.serial, self.port, timeout=30.0)
            data = body if body is not None else (b"" if method in ("POST", "DELETE") else None)
            req = urllib.request.Request(self.base + path, method=method, data=data, headers={"X-Dystopo-Token": self.token})
            try:
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    out = r.read()
                    return out if raw else json.loads(out.decode("utf-8"))
            except urllib.error.HTTPError as e:
                if e.code == 401 and not attempt:
                    self.token = None
                    continue
                text = e.read().decode("utf-8", "replace")[:400]
                raise RuntimeError(f"{method} {path.split('?')[0]}: HTTP {e.code} {text}")
            except (urllib.error.URLError, ConnectionError, OSError):
                if attempt:
                    raise
                self.token = None  # relaunched on a new port: read logcat again and re-forward

    def try_call(self, path, method="GET", **kw):
        try:
            return self.call(path, method, **kw)
        except (RuntimeError, OSError, ValueError, urllib.error.URLError) as e:
            return {"error": str(e)}


# ---------------------------------------------------------------------------------------------------
# Device state


def shell(exe, serial, cmd, timeout=120):
    code, out = adb(exe, serial, "shell", cmd, timeout=timeout)
    return out


def app_uid(exe, serial):
    out = shell(exe, serial, f"pm list packages -U {PACKAGE}")
    for line in out.splitlines():
        m = re.match(r"package:(\S+)\s+uid:(\d+)", line.strip())
        if m and m.group(1) == PACKAGE:
            return int(m.group(2))
    out = shell(exe, serial, f"dumpsys package {PACKAGE}")
    m = re.search(r"userId=(\d+)", out)
    return int(m.group(1)) if m else None


def device_info(exe, serial):
    props = {k: shell(exe, serial, f"getprop {k}").strip() for k in
             ("ro.product.manufacturer", "ro.product.model", "ro.build.version.release", "ro.kernel.qemu", "ro.boot.qemu")}
    emulator = props["ro.kernel.qemu"] == "1" or props["ro.boot.qemu"] == "1" or serial.startswith("emulator-")
    return {"serial": serial, "model": f"{props['ro.product.manufacturer']} {props['ro.product.model']}".strip(),
            "android": props["ro.build.version.release"], "emulator": emulator}


def battery_state(exe, serial):
    out = shell(exe, serial, "dumpsys battery")
    def num(key):
        m = re.search(rf"^\s*{key}:\s*(-?\d+)", out, re.M)
        return int(m.group(1)) if m else None
    plugged = [k for k in ("AC", "USB", "Wireless", "Dock") if re.search(rf"^\s*{k} powered:\s*true", out, re.M)]
    return {"level": num("level"), "scale": num("scale"), "plugged": plugged, "temperatureC": (num("temperature") or 0) / 10}


def screen_state(exe, serial):
    out = shell(exe, serial, "dumpsys power")
    m = re.search(r"mWakefulness=(\w+)", out)
    return m.group(1) if m else None


def set_screen(exe, serial, on):
    adb(exe, serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP" if on else "KEYCODE_SLEEP", timeout=30)
    if on:
        adb(exe, serial, "shell", "wm", "dismiss-keyguard", timeout=30)  # no effect behind a secure lock screen
    deadline = time.time() + 10
    want = "Awake" if on else ("Asleep", "Dozing")
    while time.time() < deadline:
        s = screen_state(exe, serial)
        if (s == want) if on else (s in want):
            return s
        time.sleep(0.5)
    return screen_state(exe, serial)


def foreground(exe, serial, app, timeout=60.0):
    """Brings the app's task to the front and waits for /health to report the activity started and a loaded style."""
    adb(exe, serial, "shell", "am", "start", "-n", ACTIVITY, timeout=30)
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        h = app.try_call("/health", timeout=10)
        m = h.get("map", {}) if isinstance(h, dict) else {}
        last = m.get("activity")
        if last == "started" and m.get("styleLoaded"):
            return last
        time.sleep(1)
    return last


def in_front(ctx):
    """The app on screen for a screen-on scenario; behind a secure lock screen, asks on stderr for the phone to be
    unlocked and waits up to --unlock-wait s. Raises when the map is still not in front."""
    state = foreground(ctx.exe, ctx.serial, ctx.app)
    deadline = time.time() + ctx.args.unlock_wait
    asked = False
    while state != "started" and time.time() < deadline:
        if not asked:
            log(f"the map is not in front ({state}): unlock the phone; waiting up to {ctx.args.unlock_wait:.0f} s")
            asked = True
        set_screen(ctx.exe, ctx.serial, True)
        state = foreground(ctx.exe, ctx.serial, ctx.app, timeout=15)
    if state != "started":
        raise RuntimeError(f"the map did not come to the front (activity {state}); is the phone locked?")
    return state


# ---------------------------------------------------------------------------------------------------
# batterystats (checkin format: `dumpsys batterystats -c`; lines `<version>,<uid>,<category>,<type>,...`, category `l`
# is "since charged", which after `--reset` means since the reset)


def parse_checkin(text, uid):
    """The app's and the device's numbers from `dumpsys batterystats -c` output. Unknown or missing lines leave their
    fields at zero or None; times are milliseconds, power milliamp-hours."""
    app = {"mAh": None, "cpuMs": {"user": 0, "system": 0}, "wakelockMs": None, "wakelockSumMs": 0, "wakelockCount": 0,
           "wakelocks": [], "screenWakelockMs": 0, "gpsMs": 0,
           "network": {"wifiRxBytes": 0, "wifiTxBytes": 0, "mobileRxBytes": 0, "mobileTxBytes": 0},
           "topMs": None, "foregroundServiceMs": None}
    device = {"capacityMah": None, "computedDrainMah": None, "screenOnMs": None, "realtimeMs": None, "version": None}
    wl = {}
    for line in text.splitlines():
        f = line.strip().split(",")
        if len(f) < 4 or f[2] != "l":
            continue
        try:
            row_uid = int(f[1])
        except ValueError:
            continue
        kind, rest = f[3], f[4:]
        if device["version"] is None and f[0].isdigit():
            device["version"] = int(f[0])
        try:
            if row_uid == 0 and kind == "pws" and len(rest) >= 2:
                device["capacityMah"], device["computedDrainMah"] = float(rest[0]), float(rest[1])
            elif row_uid == 0 and kind == "bt" and len(rest) >= 2:
                device["realtimeMs"] = int(rest[1])  # starts, battery realtime, battery uptime, ...
            elif row_uid == 0 and kind == "m" and rest:
                device["screenOnMs"] = int(rest[0])
            elif kind == "pwi" and row_uid == uid and rest and rest[0] == "uid":
                app["mAh"] = (app["mAh"] or 0.0) + float(rest[1])
            elif row_uid != uid:
                continue
            elif kind == "cpu" and len(rest) >= 2:
                app["cpuMs"]["user"] += int(rest[0])
                app["cpuMs"]["system"] += int(rest[1])
            elif kind == "wl" and rest:
                # name, then per kind (f full, p partial, bp background partial, w window): time,kind,count[,...]
                name = rest[0]
                if len(rest) > 2 and rest[2] == "f" and rest[1].isdigit():
                    app["screenWakelockMs"] += int(rest[1])  # full wakelocks: the screen kept on (navigation)
                for i, tok in enumerate(rest):
                    if tok == "p" and i >= 2 and rest[i - 1].lstrip("-").isdigit():
                        ms, n = int(rest[i - 1]), int(rest[i + 1]) if i + 1 < len(rest) and rest[i + 1].isdigit() else 0
                        e = wl.setdefault(name, [0, 0])
                        e[0] += ms
                        e[1] += n
            elif kind == "sr" and len(rest) >= 2 and rest[0] == GPS_SENSOR:
                app["gpsMs"] += int(rest[1])
            elif kind == "nt" and len(rest) >= 4:
                app["network"]["mobileRxBytes"] += int(rest[0])
                app["network"]["mobileTxBytes"] += int(rest[1])
                app["network"]["wifiRxBytes"] += int(rest[2])
                app["network"]["wifiTxBytes"] += int(rest[3])
            elif kind == "st" and len(rest) >= 4:
                # process state times: top, foreground service, foreground, background, ... (ms)
                app["topMs"], app["foregroundServiceMs"] = int(rest[0]), int(rest[1])
            elif kind == "awl" and rest:
                app["wakelockMs"] = int(rest[0])  # partial wakelock time with overlaps counted once
        except (ValueError, IndexError):
            continue
    app["wakelockSumMs"] = sum(v[0] for v in wl.values())
    if app["wakelockMs"] is None:
        app["wakelockMs"] = 0
    app["wakelockCount"] = sum(v[1] for v in wl.values())
    app["wakelocks"] = [{"name": k, "ms": v[0], "count": v[1]} for k, v in sorted(wl.items(), key=lambda kv: -kv[1][0]) if v[0] or v[1]][:5]
    if app["mAh"] is not None and device["computedDrainMah"]:
        app["sharePct"] = round(100.0 * app["mAh"] / device["computedDrainMah"], 1)
    else:
        app["sharePct"] = None
    if app["mAh"] is not None:
        app["mAh"] = round(app["mAh"], 3)
    return app, device


# ---------------------------------------------------------------------------------------------------
# The drive


def drive_line(path):
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            head, _, opts = line.partition("|")
            ends, _, label = head.partition(":")
            if label == DRIVE_LABEL:
                a, b = ends.split(";")
                o = dict(kv.split("=", 1) for kv in opts.split("|") if "=" in kv)
                return a, b, o.get("mode", "drive")
    raise RuntimeError(f"no {DRIVE_LABEL} line in {path}")


def haversine(a, b):
    r = 6371008.8
    la1, lo1, la2, lo2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def retime(gpx_bytes, kmh):
    """The route's points resampled to one fix a second at `kmh`, as a timed GPX track; (gpx text, points, seconds)."""
    root = ET.fromstring(gpx_bytes)
    pts = []
    for el in root.iter():
        tag = el.tag.rsplit("}", 1)[-1]
        if tag in ("trkpt", "rtept"):
            ele = next((c.text for c in el if c.tag.rsplit("}", 1)[-1] == "ele"), None)
            pts.append((float(el.get("lat")), float(el.get("lon")), float(ele) if ele else None))
    if len(pts) < 2:
        raise RuntimeError("the route's GPX has fewer than two points")
    step = kmh / 3.6
    out = [pts[0]]
    carry = 0.0  # metres along the current segment where the next fix falls
    for a, b in zip(pts, pts[1:]):
        d = haversine(a, b)
        pos = step - carry if out else 0.0
        while pos <= d and d > 0:
            t = pos / d
            ele = a[2] + (b[2] - a[2]) * t if a[2] is not None and b[2] is not None else None
            out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, ele))
            pos += step
        carry = d - (pos - step)
    t0 = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<gpx version="1.1" creator="android-battery" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>battery drive</name><trkseg>']
    for i, (lat, lon, ele) in enumerate(out):
        ts = (t0 + datetime.timedelta(seconds=i)).strftime("%Y-%m-%dT%H:%M:%SZ")
        e = f"<ele>{ele:.1f}</ele>" if ele is not None else ""
        lines.append(f'<trkpt lat="{lat:.7f}" lon="{lon:.7f}">{e}<time>{ts}</time></trkpt>')
    lines.append("</trkseg></trk></gpx>")
    return "\n".join(lines), len(out), len(out) - 1


# ---------------------------------------------------------------------------------------------------
# Scenarios: prepare() before the stats reset, run(deadline) during it, finish() after the reading


class Tracking:
    name, screen_on = "tracking", False

    def __init__(self, ctx):
        self.ctx, self.track_id, self.extra = ctx, None, {}

    def prepare(self):
        c = self.ctx
        if c.info["emulator"]:  # the emulator's GPS has no position until one is set; harmless on a phone (no emu console)
            adb(c.exe, c.serial, "emu", "geo", "fix", str(IDLE_VIEW[1]), str(IDLE_VIEW[0]), timeout=30)
        foreground(c.exe, c.serial, c.app)

    def start(self):
        c = self.ctx
        r = c.app.call("/tracks/record?source=gps&name=" + urllib.parse.quote(f"battery {c.stamp}"), "POST")
        self.track_id = (r.get("started") or {}).get("id")
        self.extra["service"] = r.get("service")
        self.extra["screenAfterSleep"] = set_screen(c.exe, c.serial, False)

    def tick(self):
        pass

    def stop(self):
        c = self.ctx
        set_screen(c.exe, c.serial, True)
        r = c.app.try_call("/tracks/stop", "POST")
        stats = r.get("stats") or (r.get("track") or {}).get("stats") or {}
        self.extra["stopped"] = {k: r.get(k) for k in ("id", "points", "fixes") if k in r} or {"keys": sorted(r)[:12]}
        self.extra["stats"] = {k: stats.get(k) for k in ("points", "distanceM", "durationS") if k in stats}
        tid = r.get("id") or self.track_id
        if tid and not c.args.keep_tracks:
            self.extra["deleted"] = "error" not in c.app.try_call(f"/tracks/{tid}", "DELETE")


class Nav:
    name, screen_on = "nav", True

    def __init__(self, ctx):
        self.ctx, self.extra, self.gpx = ctx, {}, None

    def prepare(self):
        c = self.ctx
        in_front(c)
        a, b, mode = drive_line(c.args.routes_file)
        r = c.app.call(f"/route?from={a}&to={b}&mode={mode}", "POST", timeout=300)
        route = r.get("route") or {}
        if r.get("error") or not route:
            raise RuntimeError(f"POST /route failed: {json.dumps(r)[:300]}")
        self.extra["route"] = {k: route.get(k) for k in ("distanceM", "distance", "timeS") if k in route}
        gpx = c.app.call("/route/gpx", raw=True)
        self.gpx, n, secs = retime(gpx, c.args.drive_kmh)
        self.extra["replay"] = {"fixes": n, "driveMinutes": round(secs / 60, 1), "kmh": c.args.drive_kmh, "starts": 0}

    def start(self):
        c = self.ctx
        r = c.app.call("/nav/start?route=current", "POST")
        self.extra["navStarted"] = bool(r.get("active", r.get("navigating", True))) and "error" not in r
        self._replay()

    def _replay(self):
        c = self.ctx
        c.app.call("/location/replay?rate=1", "POST", body=self.gpx.encode("utf-8"))
        self.extra["replay"]["starts"] += 1

    def tick(self):
        r = self.ctx.app.try_call("/location/replay", timeout=15)
        if isinstance(r, dict) and r.get("finished") is True:
            self._replay()

    def stop(self):
        c = self.ctx
        rp = c.app.try_call("/location/replay")
        self.extra["replayAtEnd"] = {k: rp.get(k) for k in ("sent", "total", "finished") if k in rp}
        nav = c.app.try_call("/nav")
        self.extra["navAtEnd"] = {k: nav.get(k) for k in ("active", "state", "keepScreenOn", "offRoute") if k in nav}
        c.app.try_call("/location/replay", "DELETE")
        c.app.try_call("/nav/stop", "POST")
        c.app.try_call("/route/clear", "POST")


class Idle:
    name, screen_on = "idle", True

    def __init__(self, ctx):
        self.ctx, self.extra = ctx, {}

    def prepare(self):
        c = self.ctx
        in_front(c)
        lat, lon, z = IDLE_VIEW
        c.app.call(f"/camera?lat={lat}&lon={lon}&zoom={z}&bearing=0", "POST")
        h = c.app.try_call("/screenshot?wait=30000", raw=True, timeout=90)  # let the view load before the reset
        self.extra["settled"] = isinstance(h, bytes)

    def start(self):
        pass

    def tick(self):
        pass

    def stop(self):
        h = self.ctx.app.try_call("/health")
        self.extra["activityAtEnd"] = (h.get("map") or {}).get("activity") if isinstance(h, dict) else None


KINDS = {"tracking": Tracking, "nav": Nav, "idle": Idle}


class Ctx:
    pass


def run_scenario(ctx, kind):
    s = KINDS[kind](ctx)
    rep = {"name": kind, "minutes": ctx.args.minutes, "screen": {"expected": "on" if s.screen_on else "off"}}
    try:
        set_screen(ctx.exe, ctx.serial, True)
        s.prepare()
        if not ctx.args.no_unplug:
            shell(ctx.exe, ctx.serial, "dumpsys battery unplug")
        shell(ctx.exe, ctx.serial, "dumpsys batterystats --reset")
        rep["batteryStart"] = battery_state(ctx.exe, ctx.serial)
        t0 = time.time()
        rep["startedAt"] = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
        s.start()
        rep["screen"]["atStart"] = screen_state(ctx.exe, ctx.serial)
        deadline = t0 + ctx.args.minutes * 60
        while time.time() < deadline:
            time.sleep(min(60.0, max(0.0, deadline - time.time())))
            if time.time() < deadline:
                s.tick()
        rep["screen"]["atEnd"] = screen_state(ctx.exe, ctx.serial)
        rep["seconds"] = round(time.time() - t0, 1)
        stats = shell(ctx.exe, ctx.serial, "dumpsys batterystats -c", timeout=300)
        rep["batteryEnd"] = battery_state(ctx.exe, ctx.serial)
        rep["app"], rep["device"] = parse_checkin(stats, ctx.uid)
        lv0, lv1 = rep["batteryStart"]["level"], rep["batteryEnd"]["level"]
        rep["battery"] = {"levelStart": lv0, "levelEnd": lv1, "dropPct": (lv0 - lv1) if lv0 is not None and lv1 is not None else None,
                          "pluggedAtStart": rep["batteryStart"]["plugged"]}
        del rep["batteryStart"], rep["batteryEnd"]
        if ctx.args.save_stats:
            path = os.path.splitext(ctx.args.out)[0] + f"-{kind}.checkin.txt"
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(stats)
            rep["statsFile"] = path
    except Exception as e:  # noqa: BLE001: a failed scenario is reported and the next one still runs
        rep["error"] = f"{type(e).__name__}: {e}"
    finally:
        try:
            s.stop()
        except Exception as e:  # noqa: BLE001
            rep.setdefault("error", f"stop: {type(e).__name__}: {e}")
        rep["extra"] = s.extra
        if not ctx.args.no_unplug:
            shell(ctx.exe, ctx.serial, "dumpsys battery reset")
    rep["ok"] = "error" not in rep and rep.get("app", {}).get("mAh") is not None
    return rep


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"), help="adb serial of the phone (required)")
    ap.add_argument("--port", type=int, default=8760, help="host port forwarded to the app's debug server")
    ap.add_argument("--minutes", type=float, default=30.0, help="duration of each scenario")
    ap.add_argument("--scenarios", default=",".join(SCENARIOS), help="comma-separated, from: " + ", ".join(SCENARIOS))
    ap.add_argument("--drive-kmh", type=float, default=50.0, help="speed of the replayed drive")
    ap.add_argument("--routes-file", default=os.path.join("tools", "routing-tour.txt"))
    ap.add_argument("--out", default=os.path.join("out", "battery.json"))
    ap.add_argument("--unlock-wait", type=float, default=300.0, help="seconds to wait for the phone to be unlocked")
    ap.add_argument("--keep-tracks", action="store_true", help="keep the tracking scenario's track")
    ap.add_argument("--no-unplug", action="store_true", help="do not simulate unplugging (the phone is on battery)")
    ap.add_argument("--save-stats", action="store_true", help="also write each scenario's raw checkin dump next to --out")
    args = ap.parse_args()
    kinds = [k.strip() for k in args.scenarios.split(",") if k.strip()]
    bad = [k for k in kinds if k not in KINDS]
    if bad or not kinds:
        ap.error(f"unknown scenario(s) {bad}; choose from {', '.join(SCENARIOS)}")
    if not args.serial:
        ap.error("--serial is required (or ANDROID_SERIAL)")
    exe = android_debug.adb_path()
    if not exe:
        log("adb not found (ANDROID_HOME or ~/Android/sdk)")
        return 2
    owner = android_debug.forward_owner(exe, args.port)
    if owner and owner != args.serial:
        log(f"host port {args.port} is forwarded to {owner}, not {args.serial}; pick another --port")
        return 2

    ctx = Ctx()
    ctx.args, ctx.exe, ctx.serial = args, exe, args.serial
    ctx.stamp = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    ctx.info = device_info(exe, args.serial)
    ctx.uid = app_uid(exe, args.serial)
    if ctx.uid is None:
        log(f"{PACKAGE} is not installed on {args.serial}")
        return 2
    ctx.app = App(exe, args.serial, args.port)
    timeout_before = shell(exe, args.serial, "settings get system screen_off_timeout").strip()
    report = {"device": ctx.info, "uid": ctx.uid, "minutesPerScenario": args.minutes,
              "meaningful": not ctx.info["emulator"],
              "note": ("emulator: the battery is simulated and the power profile a placeholder, so mAh, share and level "
                       "are meaningless; the run proves the mechanics only") if ctx.info["emulator"] else
                      ("USB connected: the level change is not a drain" if battery_state(exe, args.serial)["plugged"] and args.no_unplug else None),
              "scenarios": []}
    try:
        if foreground(exe, args.serial, ctx.app) != "started":
            log("the app did not come to the front with a loaded style")
            return 2
        for kind in kinds:
            log(f"{kind}: {args.minutes:g} min")
            screen_on = KINDS[kind].screen_on
            if screen_on:
                shell(exe, args.serial, f"settings put system screen_off_timeout {int(args.minutes * 60000 + 600000)}")
            try:
                rep = run_scenario(ctx, kind)
            finally:
                if screen_on and timeout_before.isdigit():
                    shell(exe, args.serial, f"settings put system screen_off_timeout {timeout_before}")
            log(f"{kind}: " + ("ok" if rep["ok"] else rep.get("error", "no reading")) +
                (f", app {rep['app']['mAh']} mAh ({rep['app']['sharePct']}%), gps {rep['app']['gpsMs']} ms" if rep.get("app") else ""))
            report["scenarios"].append(rep)
    finally:
        shell(exe, args.serial, "dumpsys battery reset")
    report["ok"] = all(s["ok"] for s in report["scenarios"])
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
    print(json.dumps(report, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
