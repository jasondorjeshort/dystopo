"""First run of a release install, driven like a user (T79): taps and typing through adb, nothing else.

    python -I tools/android-firstrun.py --serial emulator-5652 --server http://192.168.1.20:9021 [--apk <apk>]
                                        [--lat 46.786 --lon -121.736] [--import out/rainier-dem.pmtiles,out/rainier-outdoors.pmtiles]
                                        [--out out/firstrun]

The release build has no debug routes, so this script only uses what a person on the phone has: it finds
on-screen text with `uiautomator dump` (Compose text is in the accessibility tree), taps with `adb shell
input tap`, types with `adb shell input text`, and judges the map from `adb exec-out screencap` PNGs.
Every screenshot is saved under --out: look at them.

Flow (each step is one entry in the JSON report, with the screenshot it took):
  install     uninstall the app, install --apk (default: the release split for the device's ABI), no pack,
              no glyphs, no sprites, no permissions granted in advance
  location    `adb emu geo fix` at --lat/--lon (the emulator's GPS; default Paradise, Mount Rainier)
  launch      start the app; answer the location dialog with "While using the app"
  card        the first-run card shows ("No map on this phone yet")
  settings    tap Settings, type --server into Settings > Sources, Save; the sheet must say "Connected: N packs"
  test        tap Test; again "Connected: N packs on <server>" (N is reported)
  download    close Settings, tap "Download the map around me"; wait until the card is gone and the region pack
              is on the map (the server builds it first: up to --timeout seconds)
  map         screencaps until two in a row agree and contour brown covers at least --contour-min of the map area
              (the rule tools/android-release.ps1 uses: contours #8c5523 over paper or hillshade)
With --import (files on the host): uninstall and install again, push the files to the phone's Download folder,
tap "Import pack files" on the card, pick them in the system file picker, and wait for the map the same way
(`import` and `import-map` steps). Exit 0 when every step passed; one JSON object on stdout.

T80, --direct (no --server): the release install with no server and no pack reads the public sources itself.
  install          fresh install (network on)
  show-rainier     geo fix at Rainier, launch, the card, "Show the map around me": seconds to the first contours and
                   to a settled screen (`firstContoursS`, `settledS`); over --direct-budget (90 s) fails the step
  show-mont-blanc  the same at Mont Blanc (Copernicus and swissALTI3D) after a restart (the card is back)
  download         "Download the map around me" at --download-at: the phone builds the pack itself
  offline          wifi and mobile data off, restart: the downloaded place renders with contours; network back on
"""
import argparse
import importlib.util
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
PACKAGE = "io.github.jasondorjeshort.dystopo"
ACTIVITY = PACKAGE + "/io.github.jasondorjeshort.dystopo.app.MainActivity"


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


debug = _load("android_debug", "android_debug.py")
check = _load("android_check", "android-check.py")  # decode_png, changed_share


class Device:
    def __init__(self, serial, out):
        self.exe = debug.adb_path()
        if not self.exe:
            raise SystemExit("adb not found (ANDROID_HOME)")
        self.serial = serial
        self.out = out
        self.shots = 0

    def adb(self, *args, timeout=120, check_ok=True):
        code, text = debug.adb(self.exe, self.serial, *args, timeout=timeout)
        if check_ok and code != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {text.strip()[:300]}")
        return text

    def shell(self, *args, **kw):
        return self.adb("shell", *args, **kw)

    def screencap(self, name):
        p = subprocess.run([self.exe, "-s", self.serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
        raw = p.stdout
        self.shots += 1
        path = os.path.join(self.out, f"{self.shots:02d}-{name}.png")
        with open(path, "wb") as f:
            f.write(raw)
        return path, raw

    def nodes(self):
        """Every node of the current screen: text, desc, class, clickable, bounds (x0, y0, x1, y1)."""
        for _ in range(3):
            self.shell("uiautomator", "dump", "/sdcard/firstrun-ui.xml", check_ok=False, timeout=60)
            xml = self.shell("cat", "/sdcard/firstrun-ui.xml", check_ok=False)
            start = xml.find("<?xml")
            if start < 0:
                time.sleep(1)
                continue
            try:
                root = ET.fromstring(xml[start:].strip())
            except ET.ParseError:
                time.sleep(1)
                continue
            out = []
            for n in root.iter("node"):
                b = [int(v) for v in re.findall(r"-?\d+", n.get("bounds", ""))]
                out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "cls": n.get("class", ""),
                            "res": n.get("resource-id", ""), "clickable": n.get("clickable") == "true",
                            "pkg": n.get("package", ""), "bounds": b if len(b) == 4 else None})
            return out
        return []

    def texts(self, nodes=None):
        return [n["text"] or n["desc"] for n in (nodes if nodes is not None else self.nodes()) if n["text"] or n["desc"]]

    def find(self, pattern, nodes=None, exact=False):
        rx = re.compile(pattern)
        for n in nodes if nodes is not None else self.nodes():
            for t in (n["text"], n["desc"]):
                if t and ((exact and t == pattern) or (not exact and rx.search(t))):
                    if n["bounds"]:
                        return n
        return None

    def wait_for(self, pattern, timeout, exact=False):
        deadline = time.time() + timeout
        while True:
            nodes = self.nodes()
            n = self.find(pattern, nodes, exact)
            if n or time.time() > deadline:
                return n, nodes
            time.sleep(1)

    def tap(self, node):
        x0, y0, x1, y1 = node["bounds"]
        self.shell("input", "tap", str((x0 + x1) // 2), str((y0 + y1) // 2))
        time.sleep(0.8)

    def tap_text(self, pattern, timeout=15, exact=False):
        n, nodes = self.wait_for(pattern, timeout, exact)
        if not n:
            raise RuntimeError(f"no '{pattern}' on screen (texts: {self.texts(nodes)[:25]})")
        self.tap(n)
        return n


def contour_share(r):
    """Share of sampled map pixels (top 12% and bottom 8% skipped) in contour brown, as tools/android-release.ps1 counts it."""

    w, h, px = r.w, r.h, r.data
    n = hits = 0
    for y in range(h * 12 // 100, h * 92 // 100, 6):
        for x in range(0, w, 6):
            i = (y * w + x) * 3
            rr, gg, bb = px[i], px[i + 1], px[i + 2]
            if rr - bb >= 60 and rr - gg >= 25 and gg > bb:
                hits += 1
            n += 1
    return hits / max(1, n)


def settle_map(dev, name, contour_min, timeout):
    """Screencaps until two in a row agree (changed share < 0.002) with contours shown; returns the step."""
    deadline = time.time() + timeout
    prev = None
    last = None
    while time.time() < deadline:
        path, raw = dev.screencap(name)
        raster = check.decode_png(raw)
        share = contour_share(raster)
        changed = check.changed_share(prev, raster) if prev is not None else 1.0
        last = {"png": path, "contourShare": round(share, 4), "changed": round(changed, 4)}
        if share >= contour_min and changed < 0.002:
            return dict(ok=True, **last)
        prev = raster
        time.sleep(2)
    return dict(ok=False, error=f"no settled map with contours within {timeout} s", **(last or {}))


def default_apk(dev):
    abi = dev.shell("getprop", "ro.product.cpu.abi").strip()
    return os.path.join(REPO, "androidApp", "build", "outputs", "apk", "release", f"androidApp-{abi}-release.apk")


def fresh_install(dev, apk):
    dev.adb("uninstall", PACKAGE, check_ok=False)
    out = dev.adb("install", apk, timeout=300)
    if "Success" not in out:
        raise RuntimeError(f"install failed: {out.strip()[:300]}")


def launch(dev):
    dev.shell("am", "start", "-n", ACTIVITY)
    # The location dialog (no -g at install: a user sees it too).
    n, _ = dev.wait_for(r"^(While using the app|Only this time|Allow)$", 12)
    if n:
        dev.tap(n)
    return bool(n)


def run(args):
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    dev = Device(args.serial, out)
    steps = []
    report = {"serial": args.serial, "server": args.server, "steps": steps, "out": out}

    def step(name, fn):
        t0 = time.time()
        try:
            r = fn() or {}
            r.setdefault("ok", True)
        except Exception as e:  # one failed step ends the run; the report says which
            r = {"ok": False, "error": f"{type(e).__name__}: {e}"}
            try:
                r["png"] = dev.screencap(name + "-failed")[0]
            except Exception:
                pass
        r = {"step": name, "s": round(time.time() - t0, 1), **r}
        steps.append(r)
        print(f"[firstrun] {name}: {'ok' if r['ok'] else 'FAILED ' + str(r.get('error'))}", file=sys.stderr)
        return r["ok"]

    apk = args.apk or default_apk(dev)
    report["apk"] = apk

    def install():
        fresh_install(dev, apk)
        return {"apk": os.path.basename(apk)}

    def location():
        dev.adb("emu", "geo", "fix", str(args.lon), str(args.lat))
        return {"lat": args.lat, "lon": args.lon}

    def start():
        answered = launch(dev)
        return {"permissionDialog": answered}

    def card():
        n, nodes = dev.wait_for("No map on this phone yet", 20)
        path, _ = dev.screencap("card")
        if not n:
            return {"ok": False, "error": "no first-run card", "texts": dev.texts(nodes)[:30], "png": path}
        return {"png": path, "texts": [t for t in dev.texts(nodes) if "server" in t.lower() or "map" in t.lower()][:6]}

    def connected(timeout):
        n, nodes = dev.wait_for(r"^(Connected: \d+ packs? on|Test failed)", timeout)
        if not n or n["text"].startswith("Test failed"):
            return None, (n["text"] if n else f"no result (texts: {dev.texts(nodes)[:30]})")
        return n["text"], None

    def settings():
        dev.tap_text("^Settings$", exact=False)
        field, nodes = dev.wait_for(r"^(http://host:port|https?://.*)$", 10)
        # The Sources field, not the search box: the text field below the "Sources" heading.
        heading = dev.find("^Sources$", nodes)
        edits = [n for n in nodes if n["cls"] == "android.widget.EditText" and n["bounds"]]
        below = [n for n in edits if heading and n["bounds"][1] >= heading["bounds"][3]]
        edit = (below[0] if below else None) or field
        if not edit:
            return {"ok": False, "error": "no server field in Settings", "texts": dev.texts(nodes)[:30]}
        dev.tap(edit)
        dev.shell("input", "text", args.server.replace("&", "\\&"))
        time.sleep(0.5)
        path_typed, _ = dev.screencap("server-typed")
        dev.tap_text("^Save$")
        text, err = connected(60)
        path, _ = dev.screencap("saved")
        if err:
            return {"ok": False, "error": err, "png": path, "typedPng": path_typed}
        return {"result": text, "png": path, "typedPng": path_typed}

    def test():
        dev.tap_text("^Test$")
        time.sleep(1)
        text, err = connected(60)
        path, _ = dev.screencap("tested")
        m = re.search(r"Connected: (\d+) pack", text or "")
        return {"ok": err is None, "result": text, "error": err, "packs": int(m.group(1)) if m else None, "png": path}

    def download():
        dev.tap_text("^Close$")
        dev.tap_text("^Download the map around me$")
        t0 = time.time()
        seen = []
        while time.time() - t0 < args.timeout:
            nodes = dev.nodes()
            texts = dev.texts(nodes)
            prog = [t for t in texts if re.match(r"^(Building on the server|Downloading|Finding your location|Download failed|Saved as pack)", t)]
            for p in prog:
                if not seen or seen[-1] != p:
                    seen.append(p)
            fail = next((t for t in texts if t.startswith("Download failed") or "permission is off" in t or t.startswith("No location yet")), None)
            if fail:
                return {"ok": False, "error": fail, "progress": seen[-12:], "png": dev.screencap("download-failed")[0]}
            if "No map on this phone yet" not in texts and not any(t.startswith(("Building", "Downloading")) for t in prog):
                return {"progress": seen[-12:], "waitS": round(time.time() - t0, 1), "png": dev.screencap("downloaded")[0],
                        "packLine": next((t for t in texts if t.startswith("Saved as pack")), None)}
            time.sleep(2)
        return {"ok": False, "error": f"the card was still up after {args.timeout} s", "progress": seen[-12:], "png": dev.screencap("download-timeout")[0]}

    def map_step(name):
        return lambda: settle_map(dev, name, args.contour_min, args.settle_timeout)

    flow = [("install", install), ("location", location), ("launch", start), ("card", card), ("settings", settings),
            ("test", test), ("download", download), ("map", map_step("map"))]
    if args.until:
        flow = flow[:[n for n, _ in flow].index(args.until) + 1]
    ok = all(step(n, fn) for n, fn in flow)  # all() stops at the first failure

    if args.import_files and (ok or args.import_anyway):
        files = [f for f in args.import_files.split(",") if f]

        def import_install():
            fresh_install(dev, apk)
            dev.shell("mkdir", "-p", "/sdcard/Download")
            names = []
            for f in files:
                name = os.path.basename(f)
                dev.adb("push", f, f"/sdcard/Download/{name}", timeout=300)
                names.append(name)
            # Make them known to the media store (the picker's Downloads root lists media-store downloads).
            for name in names:
                dev.shell("content", "call", "--uri", "content://media", "--method", "scan_file", "--arg", f"/sdcard/Download/{name}", check_ok=False)
            launch(dev)
            n, nodes = dev.wait_for("No map on this phone yet", 20)
            return {"ok": bool(n), "files": names, "png": dev.screencap("import-card")[0]}

        def import_pick():
            names = [os.path.basename(f) for f in files]
            dev.tap_text("^Import pack files$")
            time.sleep(2)
            first, nodes = dev.wait_for(re.escape(names[0]), 8)
            if not first:
                # Not in the recent list: open the roots drawer and go to Downloads.
                menu = dev.find("Show roots", nodes) or dev.find("Navigate up", nodes)
                if menu:
                    dev.tap(menu)
                    dev.tap_text("^Downloads$", timeout=8)
                first, nodes = dev.wait_for(re.escape(names[0]), 10)
            if not first:
                return {"ok": False, "error": "the files are not in the picker", "texts": dev.texts(nodes)[:40], "png": dev.screencap("picker")[0]}
            picker_png = dev.screencap("picker")[0]
            if len(names) > 1:
                x0, y0, x1, y1 = first["bounds"]
                cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
                dev.shell("input", "swipe", str(cx), str(cy), str(cx), str(cy), "900")  # long press: select mode
                time.sleep(1)
                dev.tap_text(re.escape(names[1]), timeout=8)
                dev.tap_text("^(Select|SELECT|Open|OPEN)$", timeout=8)
            else:
                dev.tap(first)
            t0 = time.time()
            while time.time() - t0 < 180:
                texts = dev.texts()
                msg = next((t for t in texts if t.startswith(("Imported pack", "Import failed"))), None)
                if msg and msg.startswith("Import failed"):
                    return {"ok": False, "error": msg, "png": dev.screencap("import-failed")[0]}
                if "No map on this phone yet" not in texts and "Importing" not in " ".join(texts):
                    return {"message": msg, "pickerPng": picker_png, "png": dev.screencap("imported")[0], "waitS": round(time.time() - t0, 1)}
                time.sleep(2)
            return {"ok": False, "error": "the card was still up after the import", "png": dev.screencap("import-timeout")[0]}

        ok = step("import-install", import_install) and step("import", import_pick) and step("import-map", map_step("import-map")) and ok

    report["ok"] = ok and all(s["ok"] for s in steps)
    report["screenshots"] = [s.get("png") for s in steps if s.get("png")]
    return report


def timed_map(dev, name, contour_min, timeout, t0):
    """Like settle_map, timed from [t0] (the tap): seconds to the first contours and to a settled screen with contours."""
    deadline = t0 + timeout
    prev = None
    first = None
    last = None
    while time.time() < deadline:
        path, raw = dev.screencap(name)
        raster = check.decode_png(raw)
        share = contour_share(raster)
        changed = check.changed_share(prev, raster) if prev is not None else 1.0
        now = round(time.time() - t0, 1)
        if share >= contour_min and first is None:
            first = now
        last = {"png": path, "contourShare": round(share, 4), "changed": round(changed, 4)}
        if share >= contour_min and changed < 0.002:
            return dict(ok=True, firstContoursS=first, settledS=now, **last)
        prev = raster
        time.sleep(1)
    return dict(ok=False, error=f"no settled map with contours within {timeout} s", firstContoursS=first, **(last or {}))


# T80: the places the direct-sources flow shows (public areas from areas.json).
DIRECT_PLACES = {"rainier": (46.853, -121.76), "mont-blanc": (45.8326, 6.8652)}


def run_direct(args):
    """T80: a release install with no server and no pack, by taps only: the map read straight from the public
    sources at Rainier (3DEP) and Mont Blanc (Copernicus, swissALTI3D), then "Download the map around me" at
    --download-at and the same place again with the network off (wifi and mobile data disabled)."""
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    dev = Device(args.serial, out)
    steps = []
    report = {"serial": args.serial, "mode": "direct", "steps": steps, "out": out}

    def step(name, fn):
        t0 = time.time()
        try:
            r = fn() or {}
            r.setdefault("ok", True)
        except Exception as e:
            r = {"ok": False, "error": f"{type(e).__name__}: {e}"}
            try:
                r["png"] = dev.screencap(name + "-failed")[0]
            except Exception:
                pass
        r = {"step": name, "s": round(time.time() - t0, 1), **r}
        steps.append(r)
        print(f"[firstrun] {name}: {'ok' if r['ok'] else 'FAILED ' + str(r.get('error'))}", file=sys.stderr)
        return r["ok"]

    apk = args.apk or default_apk(dev)
    report["apk"] = apk

    def network(on):
        for what in ("wifi", "data"):
            dev.shell("svc", what, "enable" if on else "disable", check_ok=False)
        time.sleep(3 if on else 2)

    def install():
        network(True)
        fresh_install(dev, apk)
        return {"apk": os.path.basename(apk)}

    def show(place, first):
        def fn():
            lat, lon = DIRECT_PLACES[place]
            dev.adb("emu", "geo", "fix", str(lon), str(lat))
            if not first:
                dev.shell("am", "force-stop", PACKAGE)
                time.sleep(1)
            launched = launch(dev) if first else (dev.shell("am", "start", "-n", ACTIVITY) and False)
            n, nodes = dev.wait_for("No map on this phone yet", 25)
            if not n:
                return {"ok": False, "error": "no first-run card", "texts": dev.texts(nodes)[:30]}
            card_png = dev.screencap(f"{place}-card")[0]
            texts = dev.texts(nodes)
            if any("server" in t.lower() and "No server set" in t for t in texts):
                return {"ok": False, "error": "the card asks for a server (no direct sources)", "texts": texts[:20]}
            time.sleep(2)  # a fix to arrive after the location dialog
            dev.tap_text("^Show the map around me$")
            t0 = time.time()
            r = timed_map(dev, place, args.contour_min, args.direct_timeout, t0)
            r.update(place=place, cardPng=card_png, permissionDialog=launched)
            if r["ok"] and r.get("settledS") is not None and r["settledS"] > args.direct_budget:
                r.update(ok=False, error=f"settled after {r['settledS']} s, over the {args.direct_budget} s budget")
            return r
        return fn

    def download():
        lat, lon = DIRECT_PLACES[args.download_at]
        dev.adb("emu", "geo", "fix", str(lon), str(lat))
        dev.shell("am", "force-stop", PACKAGE)
        time.sleep(1)
        dev.shell("am", "start", "-n", ACTIVITY)
        n, nodes = dev.wait_for("No map on this phone yet", 25)
        if not n:
            return {"ok": False, "error": "no first-run card", "texts": dev.texts(nodes)[:30]}
        time.sleep(2)
        dev.tap_text("^Download the map around me$")
        t0 = time.time()
        seen = []
        while time.time() - t0 < args.timeout:
            texts = dev.texts()
            prog = [t for t in texts if re.match(r"^(Building on this phone|Building on the server|Downloading|Finding your location|Download failed)", t)]
            for p in prog:
                if not seen or seen[-1] != p:
                    seen.append(p)
            fail = next((t for t in texts if t.startswith("Download failed") or "permission is off" in t or t.startswith("No location yet")), None)
            if fail:
                return {"ok": False, "error": fail, "progress": seen[-12:], "png": dev.screencap("download-failed")[0]}
            if "No map on this phone yet" not in texts and not prog:
                return {"progress": seen[-12:], "waitS": round(time.time() - t0, 1), "png": dev.screencap("downloaded")[0]}
            time.sleep(2)
        return {"ok": False, "error": f"the card was still up after {args.timeout} s", "progress": seen[-12:], "png": dev.screencap("download-timeout")[0]}

    def offline():
        network(False)
        try:
            dev.shell("am", "force-stop", PACKAGE)
            time.sleep(1)
            dev.shell("am", "start", "-n", ACTIVITY)
            t0 = time.time()
            r = timed_map(dev, "offline", args.contour_min, args.settle_timeout, t0)
            r["network"] = "wifi and data disabled"
            return r
        finally:
            network(True)

    flow = [("install", install), ("show-rainier", show("rainier", True)), ("show-mont-blanc", show("mont-blanc", False)),
            ("download", download), ("offline", offline)]
    ok = all(step(n, fn) for n, fn in flow)
    report["ok"] = ok and all(s["ok"] for s in steps)
    report["screenshots"] = [s.get("png") for s in steps if s.get("png")]
    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--serial", required=True)
    ap.add_argument("--server", default="", help="the server URL typed into Settings > Sources (use the host's LAN address, not 10.0.2.2)")
    ap.add_argument("--direct", action="store_true", help="T80: no server: the map straight from the sources, then a download and the network off")
    ap.add_argument("--direct-budget", type=float, default=90, help="seconds from the tap to a settled map with contours (--direct); T80 measured 60 to 70 s cold on an emulator (docs/PLANNING.md)")
    ap.add_argument("--direct-timeout", type=float, default=120)
    ap.add_argument("--download-at", choices=sorted(DIRECT_PLACES), default="rainier")
    ap.add_argument("--apk", default="")
    ap.add_argument("--lat", type=float, default=46.786)
    ap.add_argument("--lon", type=float, default=-121.736)
    ap.add_argument("--import", dest="import_files", default="", help="comma-separated pack files on the host to import through the picker")
    ap.add_argument("--import-anyway", action="store_true", help="run the import flow even when the server flow failed")
    ap.add_argument("--until", choices=["card", "settings", "test", "download"], default="",
                    help="stop after this step (e.g. test: only read a server's catalog, never ask it to build)")
    ap.add_argument("--timeout", type=float, default=300, help="seconds for the server build and download")
    ap.add_argument("--settle-timeout", type=float, default=90)
    ap.add_argument("--contour-min", type=float, default=0.002)
    ap.add_argument("--out", default=os.path.join("out", "firstrun"))
    args = ap.parse_args()
    if not args.direct and not args.server:
        ap.error("--server is required unless --direct")
    report = run_direct(args) if args.direct else run(args)
    print(json.dumps(report, indent=1))
    sys.exit(0 if report["ok"] else 1)


if __name__ == "__main__":
    main()
