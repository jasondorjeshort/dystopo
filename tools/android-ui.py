"""Release realism checks (T81): what a user of the release build sees, beyond the map.

    python -I tools/android-ui.py <part> [options]      # one JSON object on stdout, exit 1 when a check failed

Every device part except `routes` and `search` drives the RELEASE build the way a person would: taps and typing
through adb (`input tap|swipe|text`), on-screen text from `uiautomator dump` (Compose text is in the accessibility
tree), the screen from `adb exec-out screencap`. No debug route is used. Screenshots go under --out: look at them.
The tap helpers are tools/android-firstrun.py's (`Device`), loaded by path.

Parts:
  strings      (no device) every string in the APK (dex string tables, plus printable runs of every other entry:
               resources, assets, native libraries) through tools/PrivacyCheck.java scan. Fails on a private term
               or a coordinate pair inside a local box (devtool.local.json, read by PrivacyCheck itself; this script
               never opens it); the lenient single-value rule is reported, not failed (see `valueRule` in the report).
  features     the release feature pass (R8 has never seen these paths in a test): with the Rainier pack pushed,
               search (type, pick a hit), routing (long-press start and end, the card, Keep as a trip, the Routes
               list; T91: the long-press card's Route from here), a waypoint with no recording (long-press, Waypoint here,
               the sheet), tracks (Record, fixes through `adb emu geo fix`, Stop, the list, GPX export), the sync sheet
               (sync with --server when given), the Share screen (a 16-character code), the Settings sheet; no crash.
  upgrade      install --prev-apk (the last published release, signed with the same key), push the pack, record a
               track, keep a trip, set units; then `adb install -r` --apk over it and check the track, the trip, the
               pack (no first-run card, contours drawn) and the setting survived. The new APK must carry a higher
               versionCode: the sweep builds it with -PdystopoVersionCode=<commit count> as release.yml does.
  update       (T83) the in-app update by taps: --prev-apk (the published release) with the pack, a track and metric
               units; then --apk (this build) over it, pointed at a mock releases API (update-api.txt in the app's
               external files dir; the mock on 127.0.0.1:--mock-port is 10.0.2.2 to the emulator). A lower release
               stays quiet (the start-up check asks the mock, no offer, Settings says "Up to date"); a wrong
               SHA256SUMS.txt and a debug-signed APK are refused with the reason; a good one (--new-apk, a higher
               versionCode, the release key) is offered on the map ("Update to <version>"), Install goes through the
               system installer (allowing installs from Dystopo the first time), and the new build runs with the
               track, the units and the pack; finally Check for updates against the real GitHub API (read only) is quiet.
  permissions  fresh installs with no permission granted in advance: location allowed (the dialog's "While using
               the app") and denied ("Don't allow": the app keeps working, "Download the map around me" says why it
               cannot), then notifications at the first recording (API 33+): allowed (the recording notification is
               posted) and denied (recording still works).
  background   Record, press Home, force doze (`dumpsys deviceidle force-idle`), feed fixes for --bg-seconds,
               leave doze, come back and Stop: the foreground service must have kept recording through doze.
  network      the emulator's network throttled (`emu network speed/delay`) and the server killed mid-download
               (Packs > From server, a pack from --server's catalog) and mid-sync (the sync sheet): the app says the
               transfer failed, stays alive, and the retry succeeds once the server is back. Needs --server-cmd
               (how to start the server; the part starts and kills it by PID) and --server.
  matrix       one device configuration (the sweep's -Matrix boots each: minimum API, tablet; and on one boot
               system font scale 2.0, dark mode, a non-English locale): the map renders with contours, the map chips
               (Tracks, Routes, Settings, search box) are on screen, inside it and not overlapping, and the Settings
               sheet's Sources section is reachable. --font-scale/--dark/--locale apply the setting first and undo it.
  routes       (debug build, debug port) the routing tour: tools/routing-tour.txt, each public route planned with
               POST /route; distance and ascent within tolerance and the named trails present. T91: a line with
               `pack=<name>` needs that pack on the phone: <name>-outdoors.pmtiles (and <name>-dem.pmtiles when it
               exists) from --pack-dir (default out/) are pushed to the app's external packs dir, the app reloads
               them, and the ones this run pushed are deleted again at the end; without the outdoors file the line is
               reported `skipped` with the reason and does not fail the part (routesSkipped).
  search       (debug build, debug port) the search tour: tools/search-tour.txt, each query's expected top hit.

Privacy: only areas.json places are used (the Rainier pack and the views around it); reports never echo file
contents beyond the counts PrivacyCheck prints.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import math
import os
import re
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
PACKAGE = "io.github.jasondorjeshort.dystopo"
ACTIVITY = PACKAGE + "/io.github.jasondorjeshort.dystopo.app.MainActivity"
EXTERNAL = f"/sdcard/Android/data/{PACKAGE}/files"


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


debug = _load("android_debug", "android_debug.py")
firstrun = _load("android_firstrun", "android-firstrun.py")  # Device, settle_map, contour_share, fresh_install
check = firstrun.check  # decode_png, changed_share


# --- report helpers -------------------------------------------------------------------------------------------

class Run:
    """Ordered steps with ok, seconds and a screenshot each; a failed step ends the part unless keep_going."""

    def __init__(self, dev=None):
        self.dev = dev
        self.steps = []

    def step(self, name, fn, shot=True):
        t0 = time.time()
        if self.dev is not None:
            dismiss_foreign(self.dev)
        try:
            r = fn() or {}
            r.setdefault("ok", True)
        except Exception as e:  # reported, never raised: the report says which step and why
            r = {"ok": False, "error": f"{type(e).__name__}: {e}"}
        if self.dev is not None and shot and "png" not in r:
            try:
                r["png"] = self.dev.screencap(name + ("" if r["ok"] else "-failed"))[0]
            except Exception:
                pass
        r = {"step": name, "s": round(time.time() - t0, 1), **r}
        self.steps.append(r)
        print(f"[ui] {name}: {'ok' if r['ok'] else 'FAILED ' + str(r.get('error'))}", file=sys.stderr)
        return r["ok"]

    def report(self, **extra):
        ok = bool(self.steps) and all(s["ok"] for s in self.steps)
        return {"ok": ok, **extra, "steps": self.steps,
                "screenshots": [s["png"] for s in self.steps if s.get("png")]}


def fail(msg, **kw):
    return {"ok": False, "error": msg, **kw}


# --- device helpers -------------------------------------------------------------------------------------------

def device(args, sub):
    out = os.path.abspath(os.path.join(args.out, sub) if sub else args.out)
    os.makedirs(out, exist_ok=True)
    return firstrun.Device(args.serial, out)


def default_apk(dev):
    return firstrun.default_apk(dev)


def sdk_int(dev):
    try:
        return int(dev.shell("getprop", "ro.build.version.sdk").strip())
    except ValueError:
        return 0


def install(dev, apk, grant=True, fresh=True):
    if fresh:
        dev.adb("uninstall", PACKAGE, check_ok=False)
    args = ["install", "-r"] + (["-g"] if grant else []) + [apk]
    for attempt in range(3):
        # Right after a boot (a -change-locale boot restarts once more) the package manager can throw; wait and retry.
        out = dev.adb(*args, timeout=300, check_ok=False)
        if "Success" in out and PACKAGE in dev.shell("pm", "list", "packages", PACKAGE, check_ok=False):
            return out
        time.sleep(15)
    raise RuntimeError(f"install failed: {out.strip()[-300:]}")


def cache_dir():
    """DYSTOPO_CACHE, else this checkout's cache\\, else (a worktree) the main checkout's."""
    if os.environ.get("DYSTOPO_CACHE"):
        return os.environ["DYSTOPO_CACHE"]
    own = os.path.join(REPO, "cache")
    if os.path.isdir(os.path.join(own, "glyphs")):
        return own
    p = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"], cwd=REPO, capture_output=True, text=True)
    main = os.path.dirname(p.stdout.strip()) if p.returncode == 0 and p.stdout.strip() else REPO
    return os.path.join(main, "cache")


def push_pack(dev, dem, osm, name="rainier"):
    """The Rainier pack, glyphs and sprites into the app's external files dir, as tools/android-release.ps1 does
    (release builds are not debuggable, so no run-as). The app must have been installed (and started once on
    old images, which create the directory only then)."""
    dev.shell("mkdir", "-p", f"{EXTERNAL}/packs", f"{EXTERNAL}/sprites")
    for src, dst in ((dem, f"{name}-dem.pmtiles"), (osm, f"{name}-outdoors.pmtiles")):
        if src:
            if not os.path.isfile(src):
                raise RuntimeError(f"pack not found: {src}")
            dev.adb("push", src, f"{EXTERNAL}/packs/{dst}", timeout=600)
    glyphs = os.path.join(cache_dir(), "glyphs")
    if not os.path.isfile(os.path.join(glyphs, "Noto Sans Regular", "0-255.pbf")):
        raise RuntimeError(f"no glyphs in {glyphs}; run tools/android-push-pack.ps1 once")
    dev.shell("rm", "-rf", f"{EXTERNAL}/glyphs")
    dev.adb("push", glyphs, f"{EXTERNAL}/", timeout=600)
    for f in ("sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png"):
        p = os.path.join(REPO, "out", f)
        if not os.path.isfile(p):
            raise RuntimeError(f"{p} missing; run ./gradlew :devtool:run --args=sprite")
        dev.adb("push", p, f"{EXTERNAL}/sprites/{f}")
    # Directories adb creates on API 30+ belong to shell (mode 2770); open them up. Older sdcardfs refuses chmod and needs none.
    dev.shell(f"chmod a+rwx {EXTERNAL}/packs && chmod -R a+rwX {EXTERNAL}/glyphs {EXTERNAL}/sprites", check_ok=False)


def open_map(dev, lat, lon, z, cold=False):
    """The map at lat/lon/z through the dystopo://map link (every build has it)."""
    if cold:
        dev.shell("am", "force-stop", PACKAGE)
    uri = f"'dystopo://map?lat={lat}&lon={lon}&z={z}'"
    dev.shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", uri, "-n", ACTIVITY,
              *([] if cold else ["--activity-single-top"]), timeout=60)


def answer_dialogs(dev, allow=True, timeout=4):
    """Answers a runtime permission dialog when one shows; returns the button text tapped or None."""
    pat = r"^(While using the app|Only this time|Allow|ALLOW)$" if allow else r"^(Don.t allow|Don’t allow|DENY|Deny)$"
    n, _ = dev.wait_for(pat, timeout)
    if n:
        dev.tap(n)
        return n["text"] or n["desc"]
    return None


def dismiss_foreign(dev):
    """Refuses another app's permission dialog (Gboard on old images asks for the contacts after the first typing)."""
    try:
        nodes = dev.nodes()
        if any(n["pkg"] != PACKAGE and re.match(r"^Allow (?!Dystopo)", n["text"] or "") for n in nodes):
            deny = dev.find(r"^(DENY|Deny|Don.t allow)$", nodes)
            if deny:
                dev.tap(deny)
                return True
    except Exception:
        pass
    return False


def type_text(dev, text):
    """Types like a user, then refuses a keyboard's own permission dialog if one comes up."""
    dev.shell("input", "text", text.replace("&", "\\&"))
    time.sleep(2)
    dismiss_foreign(dev)


def app_pid(dev):
    return debug.app_pid(dev.exe, dev.serial)


def crash_lines(dev):
    """The crash buffer when it holds a crash of this app (Java: FATAL EXCEPTION with our process; native: our name)."""
    lines = dev.adb("logcat", "-d", "-b", "crash", check_ok=False).splitlines()
    return lines if any(PACKAGE in l for l in lines) else []


def crash_reason(lines):
    return next((l.split(": ", 1)[-1].strip() for l in lines if re.search(r"(Error|Exception|signal \d+)", l) and "FATAL EXCEPTION" not in l), None)


def screen(dev):
    m = re.search(r"(\d+)x(\d+)", dev.shell("wm", "size").splitlines()[-1])
    d = re.search(r"(\d+)", dev.shell("wm", "density").splitlines()[-1])
    return int(m.group(1)), int(m.group(2)), int(d.group(1)) / 160.0


def to_screen(lat, lon, clat, clon, z, w, h, density):
    """Screen pixel of lat/lon on a map centred at clat/clon, zoom z (MapLibre: 512 dp tiles)."""
    def merc(la, lo):
        s = math.sin(math.radians(la))
        return (lo + 180) / 360, 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)
    world = 512 * 2 ** z * density
    x0, y0 = merc(clat, clon)
    x1, y1 = merc(lat, lon)
    return int(round(w / 2 + (x1 - x0) * world)), int(round(h / 2 + (y1 - y0) * world))


def long_press(dev, x, y, ms=1200):
    dev.shell("input", "swipe", str(x), str(y), str(x), str(y), str(ms))
    time.sleep(1.0)


def geo_fixes(dev, points, interval=1.0):
    for lat, lon, *ele in points:
        dev.adb("emu", "geo", "fix", str(lon), str(lat), *( [str(ele[0])] if ele else []), check_ok=False)
        time.sleep(interval)


def walk(lat0, lon0, lat1, lon1, n, ele0=1650.0, ele1=1700.0):
    return [(lat0 + (lat1 - lat0) * i / (n - 1), lon0 + (lon1 - lon0) * i / (n - 1), ele0 + (ele1 - ele0) * i / (n - 1)) for i in range(n)]


def close_overlays(dev, times=2):
    for _ in range(times):
        dev.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.6)


def texts_now(dev):
    return dev.texts()


def find_any(dev, patterns, timeout=10):
    """The first node matching any of the patterns within timeout."""
    deadline = time.time() + timeout
    while True:
        nodes = dev.nodes()
        for p in patterns:
            n = dev.find(p, nodes)
            if n:
                return n, nodes
        if time.time() > deadline:
            return None, nodes
        time.sleep(1)


# Paradise (Mount Rainier, areas.json "rainier"): the views and walks below stay inside the Rainier pack.
PARADISE = (46.7864, -121.7366)
PANORAMA = (46.8024, -121.7289)


# --- strings (e) ------------------------------------------------------------------------------------------------

def _uleb(b, i):
    v = s = 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        if c < 0x80:
            return v, i
        s += 7


def dex_strings(b):
    """Every entry of a dex file's string table (MUTF-8 decoded leniently)."""
    if len(b) < 0x70 or not b.startswith(b"dex\n"):
        return []
    n, off = struct.unpack_from("<II", b, 0x38)
    out = []
    for k in range(n):
        (so,) = struct.unpack_from("<I", b, off + 4 * k)
        _, i = _uleb(b, so)
        j = b.find(b"\0", i)
        out.append(b[i:j].decode("utf-8", errors="replace"))
    return out


PRINTABLE = re.compile(rb"[\x20-\x7e]{4,}")
PRINTABLE16 = re.compile(rb"(?:[\x20-\x7e]\x00){4,}")


def blob_strings(b):
    out = [m.group().decode("ascii") for m in PRINTABLE.finditer(b)]
    out += [m.group().decode("utf-16-le") for m in PRINTABLE16.finditer(b)]
    return out


def apk_strings(apk):
    """Every string in the APK, one per line, and the count per top-level entry (classes*.dex, lib, res, assets...)."""
    lines, per = [], {}
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            data = z.read(info)
            s = dex_strings(data) if info.filename.endswith(".dex") else blob_strings(data)
            lines += [" ".join(x.splitlines()) for x in s]  # one string per line
            root = info.filename.split("/")[0]
            per[root] = per.get(root, 0) + len(s)
    return lines, per


def part_strings(args):
    apk = args.apk or os.path.join(REPO, "androidApp", "build", "outputs", "apk", "release", "androidApp-universal-release.apk")
    if not os.path.isfile(apk):
        return fail(f"APK not found: {apk}")
    t0 = time.time()
    lines, per = apk_strings(apk)
    java = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"
    p = subprocess.run([java, os.path.join("tools", "PrivacyCheck.java"), "scan"], cwd=REPO, input="\n".join(lines),
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
    try:
        scan = json.loads(p.stdout.strip().splitlines()[-1])
    except (ValueError, IndexError):
        return fail(f"PrivacyCheck gave no JSON (exit {p.returncode}): {p.stderr.strip()[:300]}")
    hard = scan.get("termHits", 0) + scan.get("coordinatePairHits", 0)
    return {"ok": hard == 0, "apk": os.path.basename(apk), "apkBytes": os.path.getsize(apk), "strings": len(lines),
            "stringsByRoot": per, "scan": scan, "seconds": round(time.time() - t0, 1),
            "privateFile": scan.get("localFile", scan.get("privateTerms", 0) > 0 or scan.get("privateBoxes", 0) > 0),
            # T87: with no devtool.local.json the scan is clean by construction; say so instead of failing on no output.
            "privateTermsConfigured": scan.get("privateTerms", 0), "note": scan.get("note", ""),
            "valueRule": "coordinateValueHits counts single decimals inside a local box's lat or lon range (the commit "
                         "hook's lenient rule); in compiled code and native libraries those are mostly constants, so "
                         "they are reported, not failed",
            **({"error": f"{hard} private hits in the APK's strings"} if hard else {})}


# --- routes and search tours (h): debug build, debug port ------------------------------------------------------

class DebugServer:
    def __init__(self, port, serial):
        self.base = f"http://127.0.0.1:{port}"
        self.exe = debug.adb_path()
        self.serial = serial
        self.port = port
        _, self.token = debug.bootstrap(self.exe, serial, port)

    def call(self, method, path, timeout=180):
        req = urllib.request.Request(self.base + path, method=method, headers={"X-Dystopo-Token": self.token})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, json.loads(r.read().decode("utf-8") or "null")
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            try:
                return e.code, json.loads(body)
            except ValueError:
                return e.code, {"raw": body[:300]}


def read_tour(path):
    """Lines `fields...:label|key=value|...`; `#` comments."""
    out = []
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            head, *opts = line.split("|")
            spec, sep, label = head.rpartition(":")
            if not sep:
                spec, label = head, ""
            kv = {}
            for o in opts:
                k, _, v = o.partition("=")
                kv[k.strip()] = v.strip()
            out.append({"spec": spec.strip(), "label": label.strip(), "opts": kv})
    return out


def within(actual, expected, tol):
    """tol '5%' (relative) or '30' (absolute)."""
    if actual is None:
        return False
    if tol.endswith("%"):
        return abs(actual - expected) <= abs(expected) * float(tol[:-1]) / 100
    return abs(actual - expected) <= float(tol)


def pack_state(srv, name):
    """The pack `name` as the app's GET /packs lists it, or None."""
    code, r = srv.call("GET", "/packs")
    for p in (r or {}).get("packs") or []:
        if p.get("name") == name:
            return p
    return None


def ensure_route_pack(srv, args, name):
    """T91: makes the pack a routing line asks for (`pack=<name>`) usable on the phone. Returns
    {"ready": bool, "skipped": bool, "pushed": bool, "reason": str, ...}: skipped when its outdoors file is not in
    --pack-dir (the line is then reported, not failed), not ready when it was pushed and the app still does not
    use it (`inUse` in GET /packs; `usable` also needs a DEM, which the Grand Canyon pack does not have). A pack already on the phone is used as it is."""
    src = {role: os.path.join(args.pack_dir, f"{name}-{role}.pmtiles") for role in ("outdoors", "dem")}
    p = pack_state(srv, name)
    if p and (p.get("inUse") or p.get("usable")):
        return {"ready": True, "skipped": False, "pushed": False, "note": "already on the phone"}
    if not os.path.isfile(src["outdoors"]):
        return {"ready": False, "skipped": True, "pushed": False,
                "reason": f"pack {name} not on the phone and {os.path.relpath(src['outdoors'], REPO)} not found "
                          f"(build it with tilebuild, see tools/routing-tour.txt)"}
    exe, ser = srv.exe, srv.serial
    debug.adb(exe, ser, "shell", "mkdir", "-p", f"{EXTERNAL}/packs")
    for role, path in src.items():
        if os.path.isfile(path):
            code, out = debug.adb(exe, ser, "push", path, f"{EXTERNAL}/packs/{name}-{role}.pmtiles", timeout=600)
            if code != 0:
                return {"ready": False, "skipped": False, "pushed": False, "reason": f"adb push {path}: {out.strip()[:200]}"}
    debug.adb(exe, ser, "shell", f"chmod a+rwx {EXTERNAL}/packs", timeout=30)
    srv.call("POST", "/reload")
    deadline = time.time() + 120
    while time.time() < deadline:
        p = pack_state(srv, name)
        if p and (p.get("inUse") or p.get("usable")):  # a vector-only pack (no DEM) is never `usable`, but is `inUse`
            return {"ready": True, "skipped": False, "pushed": True, "bytes": p.get("bytes")}
        time.sleep(2)
    return {"ready": False, "skipped": False, "pushed": True, "reason": f"pack {name} pushed but not usable: {(p or {}).get('errors')}"}


def part_routes(args):
    srv = DebugServer(args.port, args.serial)
    tour = read_tour(args.routes_file)
    res = []
    packs = {}  # pack= lines: name -> ensure_route_pack's answer
    try:
        for v in tour:
            res.append(route_line(srv, args, v, packs))
    finally:
        srv.call("POST", "/route/clear")
        for name, st in packs.items():  # what this run pushed goes again, so later parts see the phone as they left it
            if st.get("pushed"):
                code, _ = srv.call("DELETE", f"/packs/{urllib.parse.quote(name)}")
                st["removed"] = code == 200
    ran = [x for x in res if not x.get("skipped")]
    return {"ok": bool(ran) and all(x["ok"] for x in ran), "routes": res, "routesOk": sum(1 for x in ran if x["ok"]),
            "routesTotal": len(ran), "routesSkipped": len(res) - len(ran), **({"packs": packs} if packs else {})}


def route_line(srv, args, v, packs):
    """One line of the routing tour: planned and checked; a `pack=<name>` line first makes sure the pack is there."""
    a, b = v["spec"].split(";")[:2]
    mode = v["opts"].get("mode", "hike")
    o = v["opts"]
    if "pack" in o:  # T91: the line needs that pack on the phone
        if o["pack"] not in packs:
            packs[o["pack"]] = ensure_route_pack(srv, args, o["pack"])
        st = packs[o["pack"]]
        if st.get("skipped"):
            print(f"[ui] route {v['label']}: skipped ({st['reason']})", file=sys.stderr)
            return {"label": v["label"], "mode": mode, "pack": o["pack"], "ok": True, "skipped": True, "reason": st["reason"]}
        if not st.get("ready"):
            print(f"[ui] route {v['label']}: FAILED ({st['reason']})", file=sys.stderr)
            return {"label": v["label"], "mode": mode, "pack": o["pack"], "ok": False, "error": st["reason"]}
    t0 = time.time()
    code, r = srv.call("POST", f"/route?from={a}&to={b}&mode={mode}&wait=180000", timeout=240)
    route = (r or {}).get("route") or {}
    dist = route.get("distanceM")
    asc = route.get("ascentM")
    via = [x.get("name") if isinstance(x, dict) else x for x in route.get("via") or (r or {}).get("via") or []]
    if "expect" in o:  # a refusal on purpose: the status and a message naming the nearest trail
        msg = (r or {}).get("message") or ""
        checks = {"status": str(code) == o["expect"], **({"message": o["msg"].lower() in msg.lower()} if "msg" in o else {})}
        out = {"label": v["label"], "mode": mode, "ok": all(checks.values()), "checks": checks, "status": code, "message": msg}
        print(f"[ui] route {v['label']}: {'ok' if out['ok'] else 'FAILED'} {code} {msg}", file=sys.stderr)
        return out
    checks = {"planned": code == 200 and dist is not None}
    if "dist" in o:
        checks["distance"] = within(dist, float(o["dist"]), o.get("distTol", "0.1%"))
    if "ascent" in o:
        checks["ascent"] = within(asc, float(o["ascent"]), o.get("ascentTol", "2%"))
    if "via" in o:
        want = [w.strip() for w in o["via"].split("+") if w.strip()]
        checks["via"] = all(any(w.lower() in (n or "").lower() for n in via) for w in want)
    out = {"label": v["label"], "mode": mode, "ok": all(checks.values()), "checks": checks, "status": code,
           "distanceM": dist, "ascentM": asc, "descentM": route.get("descentM"), "timeS": route.get("timeS"),
           "via": via, "ms": round((time.time() - t0) * 1000),
           **({"error": (r or {}).get("message") or (r or {}).get("error")} if code != 200 else {})}
    print(f"[ui] route {v['label']}: {'ok' if out['ok'] else 'FAILED'} {dist} m, +{asc} m, via {via}", file=sys.stderr)
    return out


def part_search(args):
    srv = DebugServer(args.port, args.serial)
    tour = read_tour(args.search_file)
    res = []
    for v in tour:
        q, _, at = v["spec"].partition("@")
        lat, lon = (at.split(",") + [None])[:2] if at else (None, None)
        path = f"/search?q={urllib.parse.quote(q)}&limit=5" + (f"&lat={lat}&lon={lon}" if lat else "")
        code, r = None, None
        for _ in range(60):  # the index may still be building after a launch (503)
            code, r = srv.call("GET", path)
            if code != 503:
                break
            time.sleep(2)
        hits = (r or {}).get("hits") or []
        names = [h.get("name") for h in hits]
        kinds = [h.get("kind") for h in hits]
        o = v["opts"]
        checks = {"answered": code == 200 and bool(hits)}
        if "top" in o:
            checks["top"] = bool(names) and (names[0] or "").lower() == o["top"].lower()
        if "kind" in o:
            checks["kind"] = bool(kinds) and (kinds[0] or "").lower() == o["kind"].lower()
        if "withinKm" in o and hits:
            checks["withinKm"] = (hits[0].get("distanceKm") or 0) <= float(o["withinKm"])
        res.append({"label": v["label"], "query": q, "ok": all(checks.values()), "checks": checks, "top": names[:3],
                    "kinds": kinds[:3], "queryMs": (r or {}).get("queryMs")})
        print(f"[ui] search {q!r}: {'ok' if res[-1]['ok'] else 'FAILED'} {names[:3]}", file=sys.stderr)
    return {"ok": bool(res) and all(x["ok"] for x in res), "queries": res, "queriesOk": sum(1 for x in res if x["ok"]), "queriesTotal": len(res)}


# --- UI flows shared by the release parts -----------------------------------------------------------------------

PTS = re.compile(r"(\d+) pts")
CODE = re.compile(r"^[0-9A-Z]{4}(-[0-9A-Z]{4}){3}$")
CARD = r"^[\d.,]+ (mi|km) · \+"
FIRST_RUN = "No map on this phone yet"


def release_with_pack(dev, args, apk=None, grant=True, fresh=True):
    """Installs the release APK (fresh, permissions granted unless grant=False), starts it once so its external
    files dir exists, pushes the Rainier pack, glyphs and sprites, and opens the map at Paradise."""
    install(dev, apk or args.apk or default_apk(dev), grant=grant, fresh=fresh)
    dev.shell("am", "start", "-W", "-n", ACTIVITY, timeout=60)
    time.sleep(3)
    dev.shell("am", "force-stop", PACKAGE)
    push_pack(dev, args.dem, args.osm)
    dev.adb("logcat", "-c", check_ok=False)
    dev.adb("logcat", "-c", "-b", "crash", check_ok=False)
    dev.adb("emu", "geo", "fix", str(PARADISE[1]), str(PARADISE[0]), check_ok=False)  # the GPS has a fix before the app starts
    open_map(dev, *PARADISE, 13, cold=True)
    if not grant:
        answer_dialogs(dev, allow=True)


def map_settled(dev, name, timeout=90):
    return firstrun.settle_map(dev, name, 0.002, timeout)


def tracks_bar(dev):
    """The recording bar (Record or Stop on screen)."""
    for _ in range(4):
        nodes = dev.nodes()
        if dev.find("^(Record|Stop)$", nodes):
            return nodes
        n = dev.find("^Tracks$", nodes)
        if not n:
            close_overlays(dev, 1)
            continue
        dev.tap(n)
    raise RuntimeError(f"no recording bar (texts: {dev.texts()[:20]})")


def tracks_list(dev):
    """The tracks list under the bar (Sync now, Sync status, Share, the tracks)."""
    for _ in range(4):
        nodes = tracks_bar(dev)
        if dev.find("^Sync status$", nodes):
            return nodes
        dev.tap(dev.find("^Tracks$", nodes))
    raise RuntimeError(f"no tracks list (texts: {dev.texts()[:20]})")


def close_tracks(dev):
    n = dev.find("^×$")
    if n and dev.find("^(Record|Stop)$"):
        dev.tap(n)


def record_track(dev, points, interval=1.0):
    """Record, feed the fixes, Stop; returns the points count the bar showed just before Stop."""
    lat, lon, *_ = points[0]
    dev.adb("emu", "geo", "fix", str(lon), str(lat), check_ok=False)  # the first fix is already here, not the image's default place
    time.sleep(2)
    nodes = tracks_bar(dev)
    if dev.find("^Stop$", nodes):
        dev.tap_text("^Stop$")
    dev.tap_text("^Record$")
    answer_dialogs(dev, allow=True, timeout=3)  # a notification prompt, if the app asks for one
    geo_fixes(dev, points, interval)
    n = dev.find(r"\d+ pts")
    pts = int(PTS.search(n["text"]).group(1)) if n else None
    dev.tap_text("^Stop$")
    return pts


def track_rows(dev):
    """(name, points) of every track in the list."""
    rows = []
    for t in dev.texts(tracks_list(dev)):
        m = re.match(r"^\W*(Track [^\n]+)\n(\d+) pts", t)
        if m:
            rows.append((m.group(1).strip(), int(m.group(2))))
    return rows


def plan_and_keep(dev, a=PARADISE, b=PANORAMA, z=13):
    """Long-press start and end on the map, read the route card, tap Keep; returns the card's numbers line."""
    c = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)  # centred between the two, away from the chips and the card
    open_map(dev, *c, z)
    time.sleep(5)  # the link's camera move ends before the presses
    w, h, d = screen(dev)
    long_press(dev, *to_screen(*a, *c, z, w, h, d))
    # T91: a long-press opens a card (Waypoint here, Route from here, Cancel); the end press goes to the route.
    # Builds before T91 (the upgrade part's previous release) set the start at once: wait for whichever shows.
    first, _ = dev.wait_for("^(Route from here|Long-press the end point)$", 15)
    if first and first["text"] == "Route from here":
        dev.tap(first)
        dev.wait_for("^Long-press the end point$", 10)
    end = to_screen(*b, *c, z, w, h, d)
    long_press(dev, *end)
    card, nodes = dev.wait_for(CARD, 20)
    if not card and (dev.find("^Long-press the end point$", nodes) or dismiss_foreign(dev)):
        # The press went to a dialog that came up meanwhile (or was too short under load): once more.
        dismiss_foreign(dev)
        long_press(dev, *end)
        card, nodes = dev.wait_for(CARD, 60)
    if not card:
        raise RuntimeError(f"no route card after two long-presses (texts: {dev.texts(nodes)[:25]})")
    line = card["text"]
    via = next((t for t in dev.texts(nodes) if t.startswith("via ")), None)
    dev.tap_text("^Keep$")
    done, _ = dev.wait_for("^Done$", 8)  # Keep opens the kept route's editor
    if done:
        dev.tap(done)
        time.sleep(1)
    return line, via


def routes_list(dev):
    """Opens the Routes list; returns the rows (name and numbers lines) and closes it."""
    dev.tap_text("^Routes$")
    head, nodes = dev.wait_for(r"^(No routes yet\.|.* · \+.*)$", 10)
    texts = dev.texts(nodes)
    rows = [t for t in texts if re.match(r"^[\d.,]+ (mi|km) · \+", t)]
    png = dev.screencap("routes-list")[0]
    n = dev.find("^Close$", nodes)
    if n:
        dev.tap(n)
    return rows, png


def find_edit_below(dev, heading, nodes=None):
    nodes = nodes if nodes is not None else dev.nodes()
    h = dev.find(heading, nodes)
    edits = [n for n in nodes if n["cls"] == "android.widget.EditText" and n["bounds"]]
    below = [n for n in edits if h and n["bounds"][1] >= h["bounds"][3]]
    return below[0] if below else None


def open_settings(dev):
    dev.tap_text("^Settings$")
    n, nodes = dev.wait_for("^Sources$", 10)
    if not n:
        raise RuntimeError(f"Settings has no Sources section (texts: {dev.texts(nodes)[:25]})")
    return nodes


def close_sheet(dev):
    n = dev.find("^Close$")
    if n:
        dev.tap(n)


def set_server(dev, url):
    """Settings > Sources: type the server, Save; returns the result line ("Connected: N packs on ..." or the error)."""
    nodes = open_settings(dev)
    edit = find_edit_below(dev, "^Sources$", nodes)
    if not edit:
        raise RuntimeError("no server field in Settings > Sources")
    dev.tap(edit)
    dev.shell("input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(4):  # clear what is there (40 characters a press)
        dev.shell("input", "keyevent", *(["KEYCODE_DEL"] * 40))
    type_text(dev, url)
    dev.tap_text("^Save$")
    n, nodes = dev.wait_for(r"^(Connected: \d+ packs? on|Test failed|.*(refused|not allowed|failed).*)", 60)
    line = n["text"] if n else None
    close_sheet(dev)
    return line


def set_units(dev, label="Metres"):
    open_settings(dev)
    dev.tap_text(f"^{label}$")
    time.sleep(1)
    close_sheet(dev)


def clear_search(dev):
    """The search box's x (left of the Settings chip, in its row): clears the query, the results and the marker."""
    s = dev.find("^Settings$")
    nodes = dev.nodes()
    for n in nodes:
        if (n["text"] or n["desc"]) == "×" and s and n["bounds"][2] <= s["bounds"][0] and n["bounds"][1] < s["bounds"][3]:
            dev.tap(n)
            time.sleep(1)
            hide_keyboard(dev)
            return True
    hide_keyboard(dev)
    return False


def hide_keyboard(dev):
    """Back closes the soft keyboard when it is up (it would take the long-presses meant for the map)."""
    if "mInputShown=true" in dev.shell("dumpsys", "input_method", check_ok=False):
        dev.shell("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1)


def search_box(dev):
    """The search field: Compose draws its hint without an accessibility node, so tap left of the Settings chip."""
    s = dev.find("^Settings$")
    if not s:
        raise RuntimeError("no Settings chip on the map")
    x0, y0, x1, y1 = s["bounds"]
    return x0 // 2, (y0 + y1) // 2


def alive_and_clean(dev):
    crashes = crash_lines(dev)
    pid = app_pid(dev)
    why = crash_reason(crashes)
    return {"ok": bool(pid) and not crashes, "pid": pid, "crashLines": len(crashes), **({"crash": why} if crashes else {}),
            **({"error": "the app is not running"} if not pid else {"error": f"the app crashed: {why}"} if crashes else {})}


def known(args, key):
    return key in (args.known or "").split(",")


# --- features (a) -----------------------------------------------------------------------------------------------

def part_features(args):
    dev = device(args, "")
    run = Run(dev)
    st = {}

    def setup():
        release_with_pack(dev, args)
        r = map_settled(dev, "map")
        return {"ok": r["ok"], "contourShare": r.get("contourShare"), "png": r.get("png"), **({"error": r["error"]} if not r["ok"] else {})}

    def search():
        dev.shell("input", "tap", *map(str, search_box(dev)))
        time.sleep(1)
        type_text(dev, "Panorama")
        hit, nodes = dev.wait_for("^Panorama Point$", 20)
        if not hit:
            return fail("no 'Panorama Point' in the results", texts=dev.texts(nodes)[:20])
        results = [t for t in dev.texts(nodes) if "·" in t and "OpenStreetMap" not in t][:4]
        dev.tap(hit)
        time.sleep(3)
        return {"results": results}

    def route():
        clear_search(dev)
        line, via = plan_and_keep(dev)
        st["route"] = line
        return {"card": line, "via": via}

    def trips():
        rows, png = routes_list(dev)
        ok = any(r.split(" · ")[0] == st.get("route", "").split(" · ")[0] for r in rows)
        return {"ok": ok, "rows": rows, "png": png, **({} if ok else {"error": "the kept route is not in the Routes list"})}

    def waypoint():
        # T91: a waypoint with no recording: long-press the map, "Waypoint here", the sheet opens to name it.
        hide_keyboard(dev)
        w, h, _ = screen(dev)
        long_press(dev, w // 2, h * 11 // 20)
        here, nodes = dev.wait_for("^Waypoint here$", 10)
        if not here:
            return fail("no long-press card", texts=dev.texts(nodes)[:20])
        dev.tap(here)
        sheet, nodes = dev.wait_for("^Rename$", 10)
        png = dev.screencap("waypoint")[0]
        name = next((t for t in dev.texts(nodes) if re.match(r"^Waypoint \d+$", t)), None)
        if sheet:
            dev.tap_text("^Close$")
        return {"ok": bool(sheet), "name": name, "png": png, **({} if sheet else {"error": "no waypoint sheet after Waypoint here"})}

    def track():
        pts = record_track(dev, walk(*PARADISE, 46.7950, -121.7330, 12))
        rows = track_rows(dev)
        ok = bool(rows) and rows[0][1] >= 8
        st["track"] = rows[0] if rows else None
        return {"ok": ok, "pointsWhileRecording": pts, "tracks": rows[:3], **({} if ok else {"error": "no track with at least 8 points"})}

    def gpx():
        nodes = tracks_list(dev)
        g = dev.find("^GPX$", nodes)
        dev.tap(g)
        n, _ = dev.wait_for(r"^Saved .*\.gpx$", 15)
        return {"ok": bool(n), "message": n and n["text"], **({} if n else {"error": "no 'Saved ....gpx' line"})}

    def sync():
        if not args.server:
            return {"ok": True, "skipped": "no --server"}
        close_tracks(dev)
        line = set_server(dev, args.server)
        if not line or not line.startswith("Connected"):
            return fail(f"Settings > Sources: {line}")
        nodes = tracks_list(dev)
        dev.tap(dev.find("^Sync now$", nodes))
        n, nodes = dev.wait_for(r"^(Synced with|Sync failed|.*(failed|error|refused).*)", 60)
        ok = bool(n) and n["text"].startswith("Synced with")
        return {"ok": ok, "settings": line, "result": n and n["text"], **({} if ok else {"error": n["text"] if n else "no sync result"})}

    def sync_sheet():
        nodes = tracks_list(dev)
        dev.tap(dev.find("^Sync status$", nodes))
        n, nodes = dev.wait_for("^This device$", 10)
        texts = dev.texts(nodes)
        line = next((t for t in texts if re.search(r"\d+ operations", t) and "tracks" in t), None)
        close_sheet(dev)
        return {"ok": bool(line), "thisDevice": line, **({} if line else {"error": "no 'This device' line in the sync sheet"})}

    def share():
        nodes = tracks_list(dev)
        dev.tap(dev.find("^Share$", nodes))
        n, nodes = dev.wait_for(CODE.pattern, 20)
        png = dev.screencap("share")[0]
        addr = next((t for t in dev.texts(nodes) if t.startswith("Address:")), None)
        dev.tap_text("^Close$")
        return {"ok": bool(n), "codeShape": bool(n), "address": bool(addr), "png": png,
                **({} if n else {"error": "no 16-character code on the Share screen"})}

    def settings():
        close_tracks(dev)
        nodes = open_settings(dev)
        texts = dev.texts(nodes)
        # The first screen of the sheet; the map options (Hillshade) sit below the fold since T83 added Updates.
        want = ["Sources", "Updates", "Units", "Text size"]
        missing = [w for w in want if w not in texts]
        png = dev.screencap("settings")[0]
        close_sheet(dev)
        return {"ok": not missing, "png": png, **({"error": f"missing {missing}"} if missing else {})}

    for name, fn in (("install-map", setup), ("search", search), ("route", route), ("routes-list", trips),
                     ("waypoint", waypoint), ("track", track), ("gpx", gpx), ("sync", sync), ("sync-sheet", sync_sheet), ("share", share),
                     ("settings", settings)):
        run.step(name, fn)
    run.step("alive", lambda: alive_and_clean(dev))
    return run.report(apk=os.path.basename(args.apk or default_apk(dev)))


# --- upgrade in place (b) ----------------------------------------------------------------------------------------

def version_of(dev):
    out = dev.shell("dumpsys", "package", PACKAGE)
    vc = re.search(r"versionCode=(\d+)", out)
    vn = re.search(r"versionName=(\S+)", out)
    return (int(vc.group(1)) if vc else None), (vn.group(1) if vn else None)


def part_upgrade(args):
    if not args.prev_apk or not os.path.isfile(args.prev_apk):
        return fail(f"--prev-apk not found: {args.prev_apk!r} (gh release download <previous tag> -p '*x86_64.apk')")
    dev = device(args, "")
    run = Run(dev)
    st = {}
    new_apk = args.apk or default_apk(dev)

    def old():
        release_with_pack(dev, args, apk=args.prev_apk)
        st["old"] = version_of(dev)
        r = map_settled(dev, "old-map")
        return {"ok": r["ok"], "version": st["old"], "png": r.get("png"), **({"error": r.get("error")} if not r["ok"] else {})}

    def data():
        set_units(dev, "Metres")
        line, _ = plan_and_keep(dev)
        rows, _ = routes_list(dev)
        pts = record_track(dev, walk(*PARADISE, 46.7950, -121.7330, 10))
        tracks = track_rows(dev)
        close_tracks(dev)
        st.update(route=line, routes=rows, tracks=tracks)
        ok = bool(tracks) and bool(rows) and "km" in line
        return {"ok": ok, "route": line, "routes": rows, "tracks": tracks, "pointsWhileRecording": pts,
                **({} if ok else {"error": "could not create the track, the trip and the metric setting"})}

    def upgrade():
        out = dev.adb("install", "-r", new_apk, timeout=300, check_ok=False)
        if "Success" not in out:
            hint = (" (a local build has versionCode 1: build with -PdystopoVersionCode=<git rev-list --count HEAD>)"
                    if "VERSION_DOWNGRADE" in out else " (signed with another key?)" if "UPDATE_INCOMPATIBLE" in out else "")
            return fail(f"install -r: {out.strip()[-200:]}{hint}")
        st["new"] = version_of(dev)
        dev.adb("logcat", "-c", check_ok=False)
        open_map(dev, *PARADISE, 13, cold=True)
        return {"from": st["old"], "to": st["new"], "ok": bool(st["new"][0] and st["old"][0] and st["new"][0] > st["old"][0])}

    def survived():
        card, _ = dev.wait_for(FIRST_RUN, 6)
        r = map_settled(dev, "new-map")
        tracks = track_rows(dev)
        close_tracks(dev)
        rows, png = routes_list(dev)
        checks = {"noFirstRunCard": card is None, "contours": r["ok"], "track": tracks[:1] == st["tracks"][:1],
                  "trip": len(rows) == len(st["routes"]) and all("km" in x for x in rows), "metric": all("km" in x for x in rows)}
        return {"ok": all(checks.values()), "checks": checks, "tracks": tracks, "routes": rows, "png": r.get("png"),
                **({} if all(checks.values()) else {"error": "lost in the upgrade: " + ", ".join(k for k, v in checks.items() if not v)})}

    for name, fn in (("install-previous", old), ("create-data", data), ("install-new", upgrade), ("survived", survived)):
        if not run.step(name, fn):
            break
    run.step("alive", lambda: alive_and_clean(dev))
    return run.report(previous=os.path.basename(args.prev_apk), apk=os.path.basename(new_apk))


# --- permissions (c) -----------------------------------------------------------------------------------------------

def notification_posted(dev):
    out = dev.shell("dumpsys", "notification", "--noredact")
    return f"pkg={PACKAGE}" in out and "FOREGROUND_SERVICE" in out


def granted(dev, perm):
    out = dev.shell("dumpsys", "package", PACKAGE)
    m = re.search(re.escape(perm) + r": granted=(\w+)", out)
    return m and m.group(1) == "true"


def part_permissions(args):
    dev = device(args, "")
    run = Run(dev)
    api = sdk_int(dev)
    apk = args.apk or default_apk(dev)
    known_defects = []

    def deny_location():
        install(dev, apk, grant=False)
        dev.shell("am", "start", "-W", "-n", ACTIVITY, timeout=60)
        tapped = answer_dialogs(dev, allow=False, timeout=12)
        if not tapped:
            return fail("no location dialog on the first launch")
        card, nodes = dev.wait_for(FIRST_RUN, 15)
        return {"ok": bool(card) and not granted(dev, "android.permission.ACCESS_FINE_LOCATION"), "tapped": tapped,
                **({} if card else {"error": "no first-run card after denying location"})}

    def denied_download():
        if not args.server:
            return {"ok": True, "skipped": "no --server (with none set the button opens Settings first)"}
        dev.tap_text("^Not now$")
        line = set_server(dev, args.server)
        if not line or not line.startswith("Connected"):
            return fail(f"Settings > Sources: {line}")
        # The card is back on the next launch ("Not now" lasts a session).
        dev.shell("am", "force-stop", PACKAGE)
        dev.shell("am", "start", "-W", "-n", ACTIVITY, timeout=60)
        answer_dialogs(dev, allow=False, timeout=6)
        dev.tap_text("^Download the map around me$", timeout=20)
        n, nodes = dev.wait_for(r"(?i)location permission is off|permission", 20)
        return {"ok": bool(n), "message": n and n["text"], **({} if n else {"error": "no message saying why", "texts": dev.texts(nodes)[:20]})}

    def allow_location():
        install(dev, apk, grant=False)
        dev.shell("am", "start", "-W", "-n", ACTIVITY, timeout=60)
        tapped = answer_dialogs(dev, allow=True, timeout=12)
        ok = bool(tapped) and granted(dev, "android.permission.ACCESS_FINE_LOCATION")
        dev.tap_text("^Not now$", timeout=15)
        return {"ok": ok, "tapped": tapped, **({} if ok else {"error": "location not granted after 'While using the app'"})}

    def notifications(allow):
        def fn():
            dev.adb("emu", "geo", "fix", str(PARADISE[1]), str(PARADISE[0]), check_ok=False)
            nodes = tracks_bar(dev)
            if dev.find("^Stop$", nodes):
                dev.tap_text("^Stop$")
            dev.tap_text("^Record$")
            asked = answer_dialogs(dev, allow=allow, timeout=6) if api >= 33 else "not needed below API 33"
            geo_fixes(dev, walk(*PARADISE, 46.7900, -121.7350, 6), 1.0)
            n = dev.find(r"\d+ pts")
            pts = int(PTS.search(n["text"]).group(1)) if n else 0
            posted = notification_posted(dev)
            dev.tap_text("^Stop$")
            r = {"asked": asked, "notificationPosted": posted, "points": pts,
                 "permission": granted(dev, "android.permission.POST_NOTIFICATIONS") if api >= 33 else None}
            checks = {"recorded": pts >= 4}
            if api >= 33:
                checks["prompted"] = bool(asked)  # asked once, at the first recording of an install
            if allow:
                checks["posted"] = posted
            else:
                checks["denied"] = r["permission"] is not True
            r["checks"] = checks
            bad = [k for k, v in checks.items() if not v]
            if api >= 33 and not asked and known(args, "notification-prompt"):
                known_defects.append("notification-prompt: the app never asks for POST_NOTIFICATIONS, so on API 33+ the "
                                     "recording notification is not shown (recording itself works)")
                r["knownDefect"] = "notification-prompt"
                bad = [k for k in bad if k not in ("prompted", "posted")]
            r["ok"] = not bad
            if bad:
                r["error"] = "failed: " + ", ".join(bad)
            return r
        return fn

    run.step("location-denied", deny_location)
    run.step("location-denied-download", denied_download)
    if run.step("location-allowed", allow_location):
        run.step("notifications-allowed", notifications(True))
        # Denied: reinstall so the prompt (when the app has one) can be refused.
        run.step("notifications-denied-install", allow_location)
        run.step("notifications-denied", notifications(False))
    run.step("alive", lambda: alive_and_clean(dev))
    return run.report(api=api, knownDefects=known_defects)


# --- background recording under doze (d) ---------------------------------------------------------------------------

def part_background(args):
    dev = device(args, "")
    run = Run(dev)
    st = {}

    def setup():
        release_with_pack(dev, args)
        return {"ok": True}

    def record():
        dev.adb("emu", "geo", "fix", str(PARADISE[1]), str(PARADISE[0]), check_ok=False)
        time.sleep(2)
        tracks_bar(dev)
        dev.tap_text("^Record$")
        answer_dialogs(dev, allow=True, timeout=4)
        geo_fixes(dev, [PARADISE] * 3, 1.0)
        n = dev.find(r"\d+ pts")
        st["before"] = int(PTS.search(n["text"]).group(1)) if n else 0
        return {"points": st["before"]}

    def doze():
        dev.shell("input", "keyevent", "KEYCODE_HOME")
        time.sleep(2)
        dev.shell("dumpsys", "battery", "unplug")
        forced = dev.shell("dumpsys", "deviceidle", "force-idle").strip()
        n = max(4, int(args.bg_seconds / 1.5))
        t0 = time.time()
        states = []
        for i, p in enumerate(walk(*PARADISE, 46.7950, -121.7330, n)):
            dev.adb("emu", "geo", "fix", str(p[1]), str(p[0]), str(p[2]), check_ok=False)
            if i % 10 == 0:
                states.append(dev.shell("dumpsys", "deviceidle", "get", "deep").strip())
            time.sleep(1.5)
        dev.shell("dumpsys", "deviceidle", "unforce")
        dev.shell("dumpsys", "battery", "reset")
        st["fed"] = n
        return {"forced": forced, "deepStates": states, "fixesFed": n, "seconds": round(time.time() - t0),
                "ok": any(s == "IDLE" for s in states), **({} if any(s == "IDLE" for s in states) else {"error": "the device never reported deep IDLE"})}

    def back():
        open_map(dev, *PARADISE, 13)
        time.sleep(3)
        n, nodes = dev.wait_for(r"\d+ pts", 10)
        after = int(PTS.search(n["text"]).group(1)) if n else 0
        dev.tap_text("^Stop$")
        tracks = track_rows(dev)
        close_tracks(dev)
        gained = after - st.get("before", 0)
        ok = gained >= 0.8 * st.get("fed", 0)
        return {"ok": ok, "before": st.get("before"), "after": after, "gained": gained, "fixesFed": st.get("fed"), "tracks": tracks[:1],
                **({} if ok else {"error": f"only {gained} points for {st.get('fed')} fixes fed under doze"})}

    for name, fn in (("install-map", setup), ("record", record), ("home-doze-fixes", doze), ("return-stop", back)):
        if not run.step(name, fn):
            break
    run.step("alive", lambda: alive_and_clean(dev))
    return run.report()


# --- network (g) -----------------------------------------------------------------------------------------------------

class Server:
    """The server the part kills and restarts: started from --server-cmd, stopped by PID (taskkill /T), never by name."""

    def __init__(self, cmd, url, log_dir):
        self.cmd = json.loads(cmd)
        self.url = url.rstrip("/")
        self.log_dir = log_dir
        self.proc = None
        self.starts = 0

    def start(self, timeout=180):
        self.starts += 1
        log = open(os.path.join(self.log_dir, f"server-{self.starts}.log"), "wb")
        self.proc = subprocess.Popen(self.cmd, cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise RuntimeError(f"the server exited (code {self.proc.returncode}); see server-{self.starts}.log")
            try:
                with urllib.request.urlopen(self.url + "/healthz", timeout=5) as r:
                    if r.status == 200:
                        return
            except Exception:
                pass
            time.sleep(1)
        raise RuntimeError(f"the server did not answer {self.url}/healthz within {timeout} s")

    def kill(self):
        if self.proc and self.proc.poll() is None:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(self.proc.pid), "/T", "/F"], capture_output=True)
            else:
                self.proc.kill()
            self.proc.wait(30)
        self.proc = None


class StallProxy:
    """A TCP relay in front of the server (the phone talks to it). Armed with a path prefix, it relays the first
    response to a request for that path only up to its first body byte and then holds it, so the part can kill the
    server while that response is in flight: a sync cut in the middle, every time, whatever the network speed."""

    def __init__(self, listen_port, target_port):
        import socket
        import threading
        self.socket, self.threading = socket, threading
        self.target = ("127.0.0.1", target_port)
        self.srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("0.0.0.0", listen_port))
        self.srv.listen(16)
        self.armed = None
        self.stalled = threading.Event()
        self.release = threading.Event()
        self.conns = []
        self.closed = False
        threading.Thread(target=self._accept, daemon=True).start()

    def arm(self, prefix):
        self.stalled.clear()
        self.release.clear()
        self.armed = prefix

    def _accept(self):
        while not self.closed:
            try:
                c, _ = self.srv.accept()
            except OSError:
                return
            self.threading.Thread(target=self._serve, args=(c,), daemon=True).start()

    def _serve(self, c):
        try:
            u = self.socket.create_connection(self.target, timeout=30)
        except OSError:
            c.close()
            return
        self.conns += [c, u]
        stall = {"on": False}

        def up():
            try:
                while True:
                    b = c.recv(65536)
                    if not b:
                        break
                    line = b.split(b"\r\n", 1)[0].decode("latin-1", "replace")
                    parts = line.split(" ")
                    if self.armed and len(parts) >= 2 and parts[1].startswith(self.armed):
                        stall["on"], self.armed = True, None
                    u.sendall(b)
            except OSError:
                pass

        def down():
            try:
                while True:
                    b = u.recv(65536)
                    if not b:
                        break
                    if stall["on"]:
                        head, sep, body = b.partition(b"\r\n\r\n")
                        c.sendall(head + sep + body[:1])  # the status line, headers and one body byte, then nothing
                        self.stalled.set()
                        self.release.wait()
                        return
                    c.sendall(b)
            except OSError:
                pass
            finally:
                for s in (c, u):
                    try:
                        s.close()
                    except OSError:
                        pass

        self.threading.Thread(target=up, daemon=True).start()
        down()

    def drop(self):
        """Closes every relayed connection (what the phone sees when the server dies)."""
        self.release.set()
        for s in self.conns:
            try:
                s.close()
            except OSError:
                pass
        self.conns = []

    def close(self):
        self.closed = True
        self.drop()
        self.srv.close()


def throttle(dev, speed, delay):
    a = dev.adb("emu", "network", "speed", speed, check_ok=False).strip()
    b = dev.adb("emu", "network", "delay", delay, check_ok=False).strip()
    return {"speed": speed, "delay": delay, "accepted": a.startswith("OK") and b.startswith("OK")}


FAILED = r"(?i)(fail|error|could not|couldn.t|interrupted|refused|unreachable|timed out|timeout|reset)"


def part_network(args):
    if args.server_config and not args.server_cmd:
        exe = os.path.join(REPO, "server", "build", "install", "server", "bin", "server.bat" if os.name == "nt" else "server")
        args.server_cmd = json.dumps([exe, "--config", os.path.abspath(args.server_config)])
    if not args.server or not args.server_cmd:
        return fail("network needs --server (the URL the emulator reaches) and --server-config (the standalone server's "
                    "config; or --server-cmd, a JSON list that starts it)")
    dev = device(args, "")
    run = Run(dev)
    srv = Server(args.server_cmd, args.server, dev.out)
    proxy = None
    if args.proxy_port:
        # The phone talks to the proxy (--server names it); the proxy relays to the server's own port.
        target = json.load(open(args.server_config, encoding="utf-8-sig"))["port"] if args.server_config else args.target_port
        proxy = StallProxy(args.proxy_port, int(target))
    st = {}

    def setup():
        srv.start()
        install(dev, args.apk or default_apk(dev), grant=True)
        dev.shell("am", "start", "-W", "-n", ACTIVITY, timeout=60)
        n, _ = dev.wait_for(FIRST_RUN, 20)
        if n:
            dev.tap_text("^Not now$")
        line = set_server(dev, args.server)
        m = re.search(r"Connected: (\d+) pack", line or "")
        ok = bool(m) and int(m.group(1)) >= 1
        return {"ok": ok, "settings": line, **({} if ok else {"error": f"the server's catalog needs a pack: {line}"})}

    def packs_screen():
        dev.tap_text("^Packs$")
        n, nodes = dev.wait_for("From server", 10)
        if n and n["text"].startswith("▸"):
            dev.tap(n)
        d, nodes = dev.wait_for("^(Download|Resume|Retry)$", 30)
        return d, nodes

    def kill_mid_download():
        st["throttle"] = throttle(dev, args.net_speed, args.net_delay)
        d, nodes = packs_screen()
        if not d:
            return fail("no Download button in Packs > From server", texts=dev.texts(nodes)[:25])
        dev.tap(d)
        t0 = time.time()
        seen = None
        while time.time() - t0 < 120:
            n = dev.find(r"^Downloading: [\d.]+ MB")
            if n:
                mb = float(re.search(r"([\d.]+) MB", n["text"]).group(1))
                seen = n["text"]
                if mb >= args.kill_after_mb:
                    break
            time.sleep(0.5)
        else:
            return fail(f"the download did not reach {args.kill_after_mb} MB in 120 s (last: {seen})")
        srv.kill()
        killed_at = seen
        n, nodes = dev.wait_for(FAILED, args.fail_timeout)
        png = dev.screencap("download-killed")[0]
        alive = app_pid(dev)
        ok = bool(n) and bool(alive)
        return {"ok": ok, "throttle": st["throttle"], "killedAt": killed_at, "message": n and n["text"], "png": png,
                **({} if ok else {"error": "no failure shown after the server died" if alive else "the app died",
                                   "texts": dev.texts(nodes)[:25]})}

    def retry_download():
        srv.start()
        throttle(dev, "full", "none")
        d, nodes = dev.wait_for("^(Download|Resume|Retry)$", 20)
        if not d:
            return fail("no Download/Retry button after the failure", texts=dev.texts(nodes)[:25])
        label = d["text"]
        dev.tap(d)
        t0 = time.time()
        while time.time() - t0 < 600:
            texts = dev.texts()
            if any(t in ("INSTALLED", "ACTIVE", "ON MAP", "IN USE") for t in texts) or any(re.match(r"^(Installed|Saved as pack)", t) for t in texts):
                break
            if not any(t.startswith("Downloading") for t in texts) and any(re.search(FAILED, t) for t in texts if "rainier" not in t):
                return fail("the retry failed too", texts=texts[:25])
            time.sleep(2)
        else:
            return fail("the retry did not finish in 600 s")
        close_sheet(dev)
        open_map(dev, *PARADISE, 13, cold=True)
        r = map_settled(dev, "after-retry")
        return {"ok": r["ok"], "button": label, "seconds": round(time.time() - t0), "contourShare": r.get("contourShare"),
                "png": r.get("png"), **({} if r["ok"] else {"error": "no map after the retried download"})}

    def kill_mid_sync():
        record_track(dev, walk(*PARADISE, 46.7900, -121.7350, 6))
        throttle(dev, args.net_speed, args.net_delay)
        nodes = tracks_list(dev)
        if proxy:
            proxy.arm("/sync")
        dev.tap(dev.find("^Sync now$", nodes))
        if proxy:
            if not proxy.stalled.wait(60):
                return fail("no /sync request reached the server within 60 s")
        else:
            time.sleep(args.sync_kill_ms / 1000)
        srv.kill()
        if proxy:
            proxy.drop()
        n, nodes = dev.wait_for(r"^(Synced with|Sync failed|.*" + FAILED[4:] + ")", args.fail_timeout)
        png = dev.screencap("sync-killed")[0]
        alive = app_pid(dev)
        text = n and n["text"]
        # Either the sync finished before the kill (then the kill was not "mid") or it failed visibly.
        ok = bool(alive) and bool(text) and not text.startswith("Synced with")
        return {"ok": ok, "message": text, "png": png,
                **({} if ok else {"error": ("the sync finished before the kill: raise --net-delay or lower --sync-kill-ms"
                                            if text and text.startswith("Synced") else "no failure shown" if alive else "the app died")})}

    def retry_sync():
        srv.start()
        throttle(dev, "full", "none")
        nodes = tracks_list(dev)
        dev.tap(dev.find("^Sync now$", nodes))
        n, nodes = dev.wait_for(r"^(Synced with|Sync failed)", 60)
        ok = bool(n) and n["text"].startswith("Synced with")
        close_tracks(dev)
        return {"ok": ok, "message": n and n["text"], **({} if ok else {"error": "the sync after the restart failed"})}

    try:
        for name, fn in (("setup", setup), ("download-killed", kill_mid_download), ("download-retry", retry_download),
                         ("sync-killed", kill_mid_sync), ("sync-retry", retry_sync)):
            if not run.step(name, fn):
                break
        run.step("alive", lambda: alive_and_clean(dev))
    finally:
        throttle(dev, "full", "none")
        srv.kill()
        if proxy:
            proxy.close()
    return run.report(proxy=bool(proxy), throttle={"speed": args.net_speed, "delay": args.net_delay})


# --- device matrix (f) -------------------------------------------------------------------------------------------

def overlaps(a, b, slack=4):
    return a[0] + slack < b[2] and b[0] + slack < a[2] and a[1] + slack < b[3] and b[1] + slack < a[3]


def part_matrix(args):
    dev = device(args, args.label or "")
    run = Run(dev)
    api = sdk_int(dev)
    w, h, density = screen(dev)
    config = {"api": api, "screen": f"{w}x{h}", "density": density, "model": dev.shell("getprop", "ro.product.model").strip(),
              "locale": dev.shell("getprop", "persist.sys.locale").strip() or dev.shell("getprop", "ro.product.locale").strip(),
              "fontScale": args.font_scale or None, "dark": args.dark or None}
    undo = []
    known_defects = []

    def apply():
        if args.font_scale:
            dev.shell("settings", "put", "system", "font_scale", str(args.font_scale))
            undo.append(("settings", "put", "system", "font_scale", "1.0"))
        if args.dark:
            dev.shell("cmd", "uimode", "night", "yes")
            undo.append(("cmd", "uimode", "night", "no"))
        release_with_pack(dev, args)
        r = map_settled(dev, "map")
        extra = {}
        why = crash_reason(crash_lines(dev))
        tries = 0
        while not r["ok"] and why and "LocationListener" in why and known(args, "location-listener") and tries < 3:
            # The same known defect, hit at launch (GPS status events below API 30): dismiss the dialog, relaunch.
            tries += 1
            if tries == 1:
                known_defects.append(f"location-listener at launch: {why}")
            extra.update(knownDefect="location-listener", relaunches=tries)
            dev.adb("logcat", "-c", "-b", "crash", check_ok=False)
            dev.shell("am", "broadcast", "-a", "android.intent.action.CLOSE_SYSTEM_DIALOGS", check_ok=False)
            dev.shell("input", "keyevent", "KEYCODE_BACK")
            open_map(dev, *PARADISE, 13, cold=True)
            r = map_settled(dev, "map-relaunched")
            why = crash_reason(crash_lines(dev))
        return {"ok": r["ok"], "contourShare": r.get("contourShare"), "png": r.get("png"), **extra,
                **({"error": r.get("error") + (f" (crash: {why})" if why else "")} if not r["ok"] else {})}

    def chips():
        if not app_pid(dev):
            return fail(f"the app is not running (crash: {crash_reason(crash_lines(dev))})")
        nodes = dev.nodes()
        names = ["search", "Tracks", "Routes", "Settings", "Packs"]
        found = {k: dev.find(f"^{k}$", nodes) for k in names[1:]}
        found["search"] = next((n for n in nodes if n["cls"] == "android.widget.EditText" and n["bounds"] and n["bounds"][3] < h // 4), None)
        missing = [k for k, n in found.items() if not n]
        boxes = {k: n["bounds"] for k, n in found.items() if n}
        outside = [k for k, b in boxes.items() if b[0] < 0 or b[1] < 0 or b[2] > w or b[3] > h]
        clash = [f"{a}/{b}" for i, a in enumerate(names) for b in names[i + 1:] if a in boxes and b in boxes and overlaps(boxes[a], boxes[b])]
        # Chip text must fit: the node is at least as tall as the text needs (an ellipsis or a clipped label shows as a short box).
        short = [k for k, b in boxes.items() if (b[3] - b[1]) < 14 * density * max(1.0, args.font_scale or 1.0) * 0.9]
        bad = missing + outside + clash + short
        knowns = [b for b in bad if known(args, "overlap:" + b) or known(args, b)]
        bad = [b for b in bad if b not in knowns]
        return {"ok": not bad, **({"knownDefects": knowns} if knowns else {}), "chips": boxes, **({"missing": missing} if missing else {}), **({"outside": outside} if outside else {}),
                **({"overlap": clash} if clash else {}), **({"clipped": short} if short else {}),
                **({"error": "map chips: " + ", ".join(missing + outside + clash + short)} if bad else {})}

    def route():
        line, via = plan_and_keep(dev)
        ok = bool(re.match(r"^\d+([.,]\d+)? (mi|km) · \+\d", line))
        return {"ok": ok, "card": line, **({} if ok else {"error": f"route card numbers look wrong: {line!r}"})}

    def settings():
        nodes = open_settings(dev)
        save = dev.find("^Save$", nodes)
        test = dev.find("^Test$", nodes)
        edit = find_edit_below(dev, "^Sources$", nodes)
        on = lambda n: n and n["bounds"][3] <= h and n["bounds"][1] >= 0
        ok = bool(on(save) and on(test) and edit)
        png = dev.screencap("settings")[0]
        close_sheet(dev)
        return {"ok": ok, "png": png, **({} if ok else {"error": "Settings > Sources: field, Save or Test not on screen"})}

    def location_toggle():
        """Location switched off and on with the map open, as a user does from the quick settings: the app's location
        listeners get onProviderDisabled/onProviderEnabled, which are abstract before API 30."""
        pid0 = app_pid(dev)
        if not pid0:
            return fail(f"the app was not running before the toggle (crash: {crash_reason(crash_lines(dev))})")
        dev.adb("logcat", "-c", "-b", "crash", check_ok=False)
        if api >= 30:
            dev.shell("cmd", "location", "set-location-enabled", "false")
            time.sleep(3)
            dev.shell("cmd", "location", "set-location-enabled", "true")
        else:
            dev.shell("settings", "put", "secure", "location_providers_allowed", "-gps")
            time.sleep(3)
            dev.shell("settings", "put", "secure", "location_providers_allowed", "+gps")
        dev.adb("emu", "geo", "fix", str(PARADISE[1]), str(PARADISE[0]), check_ok=False)
        time.sleep(6)
        crashes = crash_lines(dev)
        pid1 = app_pid(dev)
        died = bool(crashes) or pid1 != pid0
        why = crash_reason(crashes)
        r = {"ok": not died, "pidBefore": pid0, "pidAfter": pid1, **({"crash": why} if why else {})}
        if died and known(args, "location-listener"):
            known_defects.append("location-listener: switching location off with the app open crashes it below API 30 "
                                 f"({why}); the listeners implement only onLocationChanged")
            r.update(ok=True, knownDefect="location-listener")
            open_map(dev, *PARADISE, 13, cold=True)  # relaunch for the steps after this one
            time.sleep(4)
        elif died:
            r["error"] = f"the app died when location was switched off and on: {why}"
        return r

    try:
        if run.step("install-map", apply):
            run.step("chips", chips)
            run.step("route", route)
            run.step("settings", settings)
            run.step("location-toggle", location_toggle)
        def alive():
            if known_defects:  # the known crash is in the buffer; only a running app counts now
                dev.adb("logcat", "-c", "-b", "crash", check_ok=False)
            return alive_and_clean(dev)
        run.step("alive", alive)
    finally:
        for u in undo:
            dev.shell(*u, check_ok=False)
    return run.report(config=config, knownDefects=known_defects)


# --- in-app update (T83) -----------------------------------------------------------------------------------------

RELEASES_PATH = "/repos/jasondorjeshort/dystopo/releases"
UPDATE_API_FILE = f"{EXTERNAL}/update-api.txt"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def apk_badging(apk):
    """(versionCode, versionName) through aapt from the newest build-tools."""
    tools = build_tools()
    aapt = os.path.join(tools, "aapt.exe" if os.name == "nt" else "aapt")
    out = subprocess.run([aapt, "dump", "badging", apk], capture_output=True, text=True, timeout=120).stdout
    m = re.search(r"versionCode='(\d+)' versionName='([^']*)'", out)
    return (int(m.group(1)), m.group(2)) if m else (None, None)


def build_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.join(os.path.expanduser("~"), "Android", "sdk")
    root = os.path.join(sdk, "build-tools")
    versions = sorted((v for v in os.listdir(root) if re.match(r"^\d+\.\d+\.\d+$", v)), key=lambda v: [int(x) for x in v.split(".")])
    if not versions:
        raise RuntimeError(f"no build-tools under {root}")
    return os.path.join(root, versions[-1])


def debug_signed_copy(apk, out_dir):
    """The same APK re-signed with the debug key (~/.android/debug.keystore): a wrong certificate for the release app."""
    ks = os.path.join(os.path.expanduser("~"), ".android", "debug.keystore")
    if not os.path.isfile(ks):
        raise RuntimeError(f"no debug keystore at {ks} (build any debug APK once)")
    dst = os.path.join(out_dir, "debug-signed.apk")
    signer = os.path.join(build_tools(), "apksigner.bat" if os.name == "nt" else "apksigner")
    p = subprocess.run([signer, "sign", "--ks", ks, "--ks-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
                        "--key-pass", "pass:android", "--out", dst, apk], capture_output=True, text=True, timeout=300)
    if p.returncode != 0:
        raise RuntimeError(f"apksigner: {(p.stderr or p.stdout).strip()[-300:]}")
    return dst


class MockReleases:
    """A stand-in for the GitHub releases API on 127.0.0.1:<port> (the emulator reaches it as 10.0.2.2): one release
    at a time, in the API's shape, with its assets (the APK, SHA256SUMS.txt, optionally version.json) served from
    here. Requests are counted per path so a part can tell whether the app asked and what it downloaded."""

    def __init__(self, port):
        import http.server
        import threading
        self.port = port
        self.release = None
        self.hits = {}
        mock = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                path = urllib.parse.urlsplit(self.path).path
                mock.hits[path] = mock.hits.get(path, 0) + 1
                body, ctype = mock.answer(path)
                if body is None:
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body) if isinstance(body, bytes) else os.path.getsize(body)))
                self.end_headers()
                if isinstance(body, bytes):
                    self.wfile.write(body)
                else:
                    with open(body, "rb") as f:
                        while True:
                            chunk = f.read(1 << 20)
                            if not chunk:
                                break
                            self.wfile.write(chunk)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def api(self):
        return f"http://10.0.2.2:{self.port}{RELEASES_PATH}"

    def announce(self, tag, apk, code=None, sums_sha=None, version_json=True):
        """One published pre-release `tag` whose universal APK is [apk]; SHA256SUMS.txt says [sums_sha] (default: the
        file's real hash); version.json (when [version_json]) says [code] and the real hash."""
        real = sha256_file(apk)
        name = f"dystopo-{tag}-universal.apk"
        sums = f"{sums_sha or real} *{name}\n{'0' * 64} *NOTICES.md\n".encode()
        files = {name: (apk, "application/vnd.android.package-archive"), "SHA256SUMS.txt": (sums, "text/plain")}
        if version_json:
            files["version.json"] = (json.dumps({"tag": tag, "versionCode": code, "versionName": tag[1:], "apk": name, "sha256": real}).encode(), "application/json")
        self.release = {"tag": tag, "files": files, "apk": name}
        self.hits = {}
        return {"tag": tag, "apk": os.path.basename(apk), "sha256": real[:12], "sumsSha": (sums_sha or real)[:12], "versionJson": version_json, "code": code}

    def answer(self, path):
        r = self.release
        if path == RELEASES_PATH:
            if not r:
                return b"[]", "application/json"
            assets = [{"name": n, "size": len(b) if isinstance(b, bytes) else os.path.getsize(b),
                       "browser_download_url": f"http://10.0.2.2:{self.port}/dl/{r['tag']}/{n}"} for n, (b, _) in r["files"].items()]
            return json.dumps([{"tag_name": r["tag"], "draft": False, "prerelease": True, "assets": assets}]).encode(), "application/json"
        if r and path.startswith(f"/dl/{r['tag']}/"):
            f = r["files"].get(path.rsplit("/", 1)[-1])
            if f:
                return f
        return None, None

    def downloaded_apk(self):
        r = self.release
        return bool(r) and self.hits.get(f"/dl/{r['tag']}/{r['apk']}", 0) > 0

    def asked(self):
        return self.hits.get(RELEASES_PATH, 0)

    def close(self):
        self.server.shutdown()
        self.server.server_close()


UPDATE_LINE = r"^(Up to date.*|Update to .* (refused|is ready|not installed).*|Update check failed.*|No release with an APK.*)$"


def check_for_updates(dev, timeout=240):
    """Settings > Updates > Check for updates; returns the result line once the check has finished."""
    nodes = open_settings(dev)
    btn = dev.find("^Check for updates$", nodes)
    for _ in range(3):
        if btn:
            break
        w, h, _d = screen(dev)
        dev.shell("input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 3), "400")
        time.sleep(1)
        btn = dev.find("^Check for updates$")
    if not btn:
        raise RuntimeError(f"no Check for updates in Settings (texts: {dev.texts()[:30]})")
    dev.tap(btn)
    time.sleep(2)
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = dev.nodes()
        if not dev.find("^Checking\\.\\.\\.$", nodes):
            n = dev.find(UPDATE_LINE, nodes)
            if n:
                return n["text"], dev.screencap("updates-row")[0]
        time.sleep(2)
    raise RuntimeError(f"no update result within {timeout} s (texts: {dev.texts()[:30]})")


def installs_allowed(dev):
    """Whether "install unknown apps" is on for Dystopo (the app op the Settings switch sets)."""
    out = dev.shell("appops", "get", PACKAGE, "REQUEST_INSTALL_PACKAGES", check_ok=False)
    return "allow" in out.lower() and "default" not in out.lower()


def system_install(dev, want_code, timeout=240):
    """Answers the system installer like a user: allow installs from this app the first time (Settings, the switch,
    back), then Update / Install; returns the taps once the package has want_code."""
    taps = []
    deadline = time.time() + timeout
    while time.time() < deadline:
        code, _ = version_of(dev)
        if code == want_code:
            return taps
        nodes = dev.nodes()
        texts = dev.texts(nodes)
        switch = dev.find(r"^Allow from this source$", nodes)
        if switch:
            # The Settings page for this app's "install unknown apps". Switch it on once (never off again), then go back
            # only if the page is still up: Android may return to the installer by itself, and Back there cancels it.
            if not installs_allowed(dev):
                toggle = next((n for n in nodes if n["cls"].endswith("Switch") and n["bounds"]), None)
                dev.tap(toggle or switch)
                taps.append("Allow from this source")
                time.sleep(2.5)
            if installs_allowed(dev) and dev.find(r"^Allow from this source$"):
                dev.shell("input", "keyevent", "KEYCODE_BACK")
                taps.append("back")
                time.sleep(2.5)
            continue
        for pat in (r"^(Update|UPDATE)$", r"^(Install|INSTALL)$", r"^(Settings|SETTINGS)$", r"^(Install anyway|INSTALL ANYWAY)$"):
            n = dev.find(pat, nodes)
            if n and n["pkg"] != PACKAGE:
                dev.tap(n)
                taps.append(n["text"])
                time.sleep(2)
                break
        else:
            time.sleep(2)
    raise RuntimeError(f"the update was not installed within {timeout} s (taps {taps}; texts {dev.texts()[:20]})")


def update_banner(dev, timeout=20):
    n, nodes = dev.wait_for(r"^Update to .*", timeout)
    return n, nodes


def part_update(args):
    """T83: the app's own update path on the release build, by taps. Builds A (--apk) and B (--new-apk, higher
    versionCode, same key) of this branch; the published release (--prev-apk) first, with data on it."""
    for k in ("prev_apk", "new_apk"):
        if not getattr(args, k) or not os.path.isfile(getattr(args, k)):
            return fail(f"--{k.replace('_', '-')} not found: {getattr(args, k)!r}")
    dev = device(args, "")
    run = Run(dev)
    apk = args.apk or default_apk(dev)
    new_code, new_name = apk_badging(args.new_apk)
    cur_code, cur_name = apk_badging(apk)
    prev_code, prev_name = apk_badging(args.prev_apk)
    if not (new_code and cur_code and new_code > cur_code):
        return fail(f"--new-apk must have a higher versionCode than --apk ({new_code} vs {cur_code})")
    bad_apk = debug_signed_copy(args.new_apk, dev.out)
    mock = MockReleases(args.mock_port)
    st = {}

    def previous():
        release_with_pack(dev, args, apk=args.prev_apk)
        st["prev"] = version_of(dev)
        r = map_settled(dev, "prev-map")
        return {"ok": r["ok"], "version": st["prev"], "png": r.get("png"), **({"error": r.get("error")} if not r["ok"] else {})}

    def data():
        set_units(dev, "Metres")
        record_track(dev, walk(*PARADISE, 46.7950, -121.7330, 8))
        tracks = track_rows(dev)
        close_tracks(dev)
        st["tracks"] = tracks
        return {"ok": bool(tracks), "tracks": tracks, **({} if tracks else {"error": "no track recorded"})}

    def current():
        # The mock is quiet first (an older release), so the build's own first check (15 s after start) finds nothing.
        st["lower"] = mock.announce("v0.1.2-alpha", args.prev_apk, code=prev_code)
        local = os.path.join(dev.out, "update-api.txt")
        with open(local, "w", encoding="ascii", newline="\n") as f:
            f.write(mock.api + "\n")
        dev.adb("push", local, UPDATE_API_FILE)
        dev.shell("chmod", "a+r", UPDATE_API_FILE, check_ok=False)
        out = dev.adb("install", "-r", "-g", apk, timeout=300, check_ok=False)
        if "Success" not in out:
            return fail(f"install -r: {out.strip()[-200:]}")
        st["cur"] = version_of(dev)
        open_map(dev, *PARADISE, 13, cold=True)
        return {"version": st["cur"], "ok": st["cur"][0] == cur_code}

    def lower_quiet():
        t0 = time.time()
        while time.time() - t0 < 60 and not mock.asked():
            time.sleep(2)
        auto = mock.asked()
        time.sleep(3)
        banner, _ = dev.wait_for(r"^Update to ", 3)
        png = dev.screencap("lower-map")[0]
        line, row_png = check_for_updates(dev)
        close_sheet(dev)
        ok = auto > 0 and banner is None and line.startswith("Up to date") and not mock.downloaded_apk()
        return {"ok": ok, "announced": st["lower"], "dailyCheckAsked": auto, "banner": bool(banner), "line": line, "png": png, "rowPng": row_png,
                **({} if ok else {"error": "the lower version was not quiet" if banner or not line.startswith("Up to date") else "the start-up check never asked the mock API"})}

    def wrong_checksum():
        # No version.json: the tag decides (v0.1.9 > this build's 0.1.x); SHA256SUMS.txt lies about the APK.
        ann = mock.announce("v0.1.9-alpha", args.new_apk, sums_sha="f" * 64, version_json=False)
        line, png = check_for_updates(dev)
        close_sheet(dev)
        banner, _ = dev.wait_for(r"refused", 6)
        map_png = dev.screencap("checksum-map")[0]
        ok = "refused" in line and "checksum" in line and mock.downloaded_apk()
        if banner:
            dev.tap_text("^OK$", timeout=5)
        return {"ok": ok, "announced": ann, "line": line, "rowPng": png, "png": map_png, "mapMessage": bool(banner),
                **({} if ok else {"error": f"not refused for the checksum: {line}"})}

    def wrong_certificate():
        ann = mock.announce("v0.1.10-alpha", bad_apk, code=new_code)
        line, png = check_for_updates(dev)
        close_sheet(dev)
        banner, _ = dev.wait_for(r"refused", 6)
        map_png = dev.screencap("certificate-map")[0]
        ok = "refused" in line and "certificate" in line and mock.downloaded_apk()
        if banner:
            dev.tap_text("^OK$", timeout=5)
        return {"ok": ok, "announced": ann, "line": line, "rowPng": png, "png": map_png, "mapMessage": bool(banner),
                **({} if ok else {"error": f"not refused for the certificate: {line}"})}

    def offer():
        ann = mock.announce("v0.1.11-alpha", args.new_apk, code=new_code)
        line, png = check_for_updates(dev)
        close_sheet(dev)
        banner, nodes = update_banner(dev)
        map_png = dev.screencap("offer-map")[0]
        notified = "Update to" in dev.shell("dumpsys", "notification", "--noredact", check_ok=False)
        ok = "is ready" in line and banner is not None and dev.find("^Install$", nodes) is not None
        st["offer"] = ann
        return {"ok": ok, "announced": ann, "line": line, "rowPng": png, "png": map_png, "banner": banner["text"] if banner else None,
                "notification": notified, **({} if ok else {"error": f"no update offer on the map ({line})"})}

    def install_step():
        dev.tap_text("^Install$")
        taps = system_install(dev, new_code)
        st["new"] = version_of(dev)
        return {"taps": taps, "version": st["new"], "ok": st["new"][0] == new_code}

    def survived():
        open_map(dev, *PARADISE, 13, cold=True)
        card, _ = dev.wait_for(FIRST_RUN, 6)
        r = map_settled(dev, "new-map")
        tracks = track_rows(dev)
        close_tracks(dev)
        nodes = open_settings(dev)
        metric = dev.find("^Metres$", nodes) is not None
        version_line = dev.find(r"^This build: ", nodes)
        close_sheet(dev)
        checks = {"noFirstRunCard": card is None, "contours": r["ok"], "track": tracks[:1] == st["tracks"][:1],
                  "runningNew": bool(version_line and new_name in version_line["text"])}
        ok = all(checks.values())
        return {"ok": ok, "checks": checks, "tracks": tracks, "png": r.get("png"), "versionLine": version_line["text"] if version_line else None,
                **({} if ok else {"error": "after the update: " + ", ".join(k for k, v in checks.items() if not v)})}

    def real_api():
        # The real GitHub API, read only: nothing newer than this branch's build is published, so it is quiet.
        dev.shell("rm", "-f", UPDATE_API_FILE, check_ok=False)
        line, png = check_for_updates(dev, timeout=120)
        close_sheet(dev)
        banner, _ = dev.wait_for(r"^Update to ", 3)
        ok = line.startswith("Up to date") and banner is None
        return {"ok": ok, "line": line, "rowPng": png, **({} if ok else {"error": f"real API: {line}"})}

    try:
        for name, fn in (("install-previous", previous), ("create-data", data), ("install-current", current),
                         ("lower-quiet", lower_quiet), ("wrong-checksum", wrong_checksum), ("wrong-certificate", wrong_certificate),
                         ("offer", offer), ("install", install_step), ("survived", survived), ("real-api", real_api)):
            if not run.step(name, fn):
                break
        run.step("alive", lambda: alive_and_clean(dev))
    finally:
        mock.close()
        dev.shell("rm", "-f", UPDATE_API_FILE, check_ok=False)
    return run.report(previous=os.path.basename(args.prev_apk), apk=os.path.basename(apk), newApk=os.path.basename(args.new_apk),
                      versions={"previous": [prev_code, prev_name], "current": [cur_code, cur_name], "new": [new_code, new_name]})


PARTS = {"strings": part_strings, "routes": part_routes, "search": part_search, "features": part_features,
         "upgrade": part_upgrade, "update": part_update, "network": part_network, "matrix": part_matrix, "permissions": part_permissions, "background": part_background}


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("part", choices=sorted(PARTS))
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    ap.add_argument("--apk", default="", help="the release APK (default: the build's split for the device's ABI; universal for strings)")
    ap.add_argument("--prev-apk", default="", help="upgrade, update: the previous published release's APK")
    ap.add_argument("--new-apk", default="", help="update: a build of this branch with a higher versionCode than --apk (same key)")
    ap.add_argument("--mock-port", type=int, default=9171, help="update: port of the mock releases API on 127.0.0.1 (the emulator's 10.0.2.2)")
    ap.add_argument("--dem", default=os.path.join(REPO, "out", "rainier-dem.pmtiles"))
    ap.add_argument("--osm", default=os.path.join(REPO, "out", "rainier-outdoors.pmtiles"))
    ap.add_argument("--server", default="", help="a Dystopo server URL the emulator reaches (the host's LAN address)")
    ap.add_argument("--server-cmd", default="", help="network: JSON list, the command that starts that server")
    ap.add_argument("--server-config", default="", help="network: config file of the standalone server (./gradlew :server:installDist) to start, kill and restart")
    ap.add_argument("--proxy-port", type=int, default=0, help="network: listen here (all interfaces) and relay to the server, "
                    "so a sync can be cut mid-response; --server then names this port")
    ap.add_argument("--target-port", type=int, default=0, help="network: the server's own port when --server-config is not given")
    ap.add_argument("--port", type=int, default=0, help="routes/search: host port forwarded to the debug server")
    ap.add_argument("--routes-file", default=os.path.join(HERE, "routing-tour.txt"))
    ap.add_argument("--pack-dir", default=os.path.join(REPO, "out"), help="routes: where a `pack=<name>` line finds <name>-outdoors.pmtiles (and -dem)")
    ap.add_argument("--search-file", default=os.path.join(HERE, "search-tour.txt"))
    ap.add_argument("--net-speed", default="umts", help="network: emulator network speed while a transfer is killed (gsm, edge, umts, hsdpa, lte, full, or up:down kbps)")
    ap.add_argument("--net-delay", default="umts", help="network: emulator network latency (gprs, edge, umts, none, or min:max ms)")
    ap.add_argument("--kill-after-mb", type=float, default=3.0, help="network: kill the server once the download shows this many MB")
    ap.add_argument("--sync-kill-ms", type=int, default=300, help="network: kill the server this long after Sync now")
    ap.add_argument("--fail-timeout", type=float, default=90, help="network: seconds for the app to show a failure")
    ap.add_argument("--bg-seconds", type=float, default=60)
    ap.add_argument("--font-scale", type=float, default=0)
    ap.add_argument("--dark", action="store_true")
    ap.add_argument("--locale", default="")
    ap.add_argument("--label", default="")
    ap.add_argument("--known", default="", help="comma-separated known defects reported but not failed (filed follow-ups)")
    ap.add_argument("--out", default=os.path.join("out", "ui"))
    args = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    if args.part not in ("strings",) and not args.serial:
        ap.error("--serial is required for device parts")
    t0 = time.time()
    since = debug.device_now(debug.adb_path(), args.serial) if args.serial else None
    try:
        report = PARTS[args.part](args)
    except Exception as e:
        report = fail(f"{type(e).__name__}: {e}")
    if args.serial and not report.get("ok"):
        # T86: a launch that died is reported as that, with the exception line, not as "no settled map within N s".
        try:
            crash = debug.launch_crash(debug.adb_path(), args.serial, since)
        except Exception:
            crash = None
        if crash:
            report = {**report, "launchCrash": crash, "error": f"the app crashed: {crash}; " + str(report.get("error", ""))}
    report = {"part": args.part, **({"label": args.label} if args.label else {}), "seconds": round(time.time() - t0, 1), **report}
    print(json.dumps(report, indent=1))
    sys.exit(0 if report.get("ok") else 1)


if __name__ == "__main__":
    main()
