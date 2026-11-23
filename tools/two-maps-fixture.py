"""T339: the two-maps fixture: two overlapping maps over Rainier (areas.json) for an emulator, and its network switch.

Every report of the "the phone says one thing about its maps and holds another" family reproduces on one setup: a wide
coarse map and a fine one inside it, the network on and off, a place inside both, one inside the wide one only, one outside
both and one near the fine one's edge. This script builds that pair, installs it on one emulator serial the way
tools/android-push-pack.ps1 -Name does (files/packs/<name>-dem.pmtiles and <name>-outdoors.pmtiles through run-as), and
switches the emulator's network; tools/two-maps-part.py reads what the phone says at the four places (the sweep's part
`two-maps`).

    python -I tools/two-maps-fixture.py build                       # out/two-maps/: the four archives and fixture.json
    python -I tools/two-maps-fixture.py install --serial emulator-5572 --port 8696   # build if needed, install, restart
    python -I tools/two-maps-fixture.py network --serial emulator-5572 off           # airplane mode on (on: off again)
    python -I tools/two-maps-fixture.py restore --serial emulator-5572 --port 8696   # the fixture out, the maps put back

The maps (MAPS below; public Rainier only, never a personal place):
- `rainier-wide`: the Rainier outdoors pack's box (machines.md's recipe), elevation z8 to z11 and the vector map z8 to z13:
  a coarse map, as the phone's "Less detail" (vector to z13) with its elevation stopped low.
- `rainier-fine`: a box around the summit inside it, elevation z8 to z14 and the vector map z8 to z14 (full detail but the
  DEM's z15, which the shared cache does not hold).
Both start at z8 as every map the phone downloads does (AreaDetail.MIN_ZOOM), so z6 and z7 are the world overview's.

Built once: `build` writes the archives to out/two-maps/ with fixture.json, the recipe they were built from; a run that
finds every archive and the same recipe builds nothing (`built: []`). `--from <folder>` copies a set built elsewhere
(the sweep's -DataDir) when its fixture.json matches, instead of building. A changed recipe rebuilds every archive.

`install` moves every other map on the phone aside (files/packs/*.pmtiles and the external files dir's packs to
files/two-maps-aside/ and packs-aside/), pushes the pair, restarts the app and reads GET /packs and GET /debug/startup (the
start trace's `head.packs`, the count of maps it found): ok when both maps are open and the count is 2. `restore` deletes the
pair, moves the aside maps back, turns airplane mode off and restarts the app. Glyphs and sprites are the ones the boot
pushed (android-emu.ps1); nothing here needs Gradle on the device side.

The network: `network off` is airplane mode on (`cmd connectivity airplane-mode enable`, read back from
`settings get global airplane_mode_on`), `network on` turns it off and waits for a default route (`ip route get 8.8.8.8`).
The emulator's console has no Wi-Fi switch on API 35 (`network` there sets speed and delay, `gsm data` only the mobile
radio, which the emulator's Wi-Fi bypasses), so the switch is the one android-check.py's offline step and android-ui.py use.

One JSON object on stdout per command; exit 0 when ok, 1 otherwise. Runs with `python -I` (the module next to it is loaded by
path). Needs JAVA_HOME for a build and DYSTOPO_CACHE for the shared cache (the Washington extract in its osm/ folder).
"""
import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
_spec = importlib.util.spec_from_file_location("android_debug", os.path.join(HERE, "android_debug.py"))
ad = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ad)

PACKAGE = ad.DEBUG_PACKAGE
EXTERNAL = f"/sdcard/Android/data/{PACKAGE}/files"
OUT_DIR = os.path.join("out", "two-maps")
EXTRACT = "north-america-us-washington-latest.osm.pbf"
ASIDE = "two-maps-aside"  # under the app's files dir (run-as) and the external files dir

# The recipe. A change here rebuilds every archive (fixture.json's `recipe` no longer matches).
MAPS = [
    {"name": "rainier-wide", "bbox": [-121.95, 46.68, -121.45, 46.98], "dem": [8, 11], "vector": [8, 13]},
    {"name": "rainier-fine", "bbox": [-121.82, 46.80, -121.68, 46.90], "dem": [8, 14], "vector": [8, 14]},
]
# The four places, in the table's order; `inside` names the maps whose box holds the point (checked by the test).
PLACES = [
    {"id": "inside-both", "lat": 46.8529, "lon": -121.7604, "inside": ["rainier-wide", "rainier-fine"],
     "what": "Mount Rainier's summit, inside both maps"},
    {"id": "wide-only", "lat": 46.7300, "lon": -121.5600, "inside": ["rainier-wide"],
     "what": "south-east of the summit, inside the wide map only (13 km from the fine one)"},
    {"id": "outside", "lat": 46.6000, "lon": -121.7000, "inside": [],
     "what": "south of both maps, 9 km past the wide one's edge"},
    {"id": "fine-edge", "lat": 46.8025, "lon": -121.7500, "inside": ["rainier-wide", "rainier-fine"],
     "what": "280 m inside the fine map's south edge, its ring running out into the wide map"},
]


def recipe():
    return {"schema": 1, "extract": EXTRACT, "maps": MAPS}


def recipe_key():
    return hashlib.sha256(json.dumps(recipe(), sort_keys=True).encode("utf-8")).hexdigest()[:16]


def files_of(m):
    return {"dem": f"{m['name']}-dem.pmtiles", "vector": f"{m['name']}-outdoors.pmtiles"}


def bbox_arg(b):
    return ",".join(f"{v:g}" for v in b)


def inside(place, m):
    w, s, e, n = m["bbox"]
    return w <= place["lon"] <= e and s <= place["lat"] <= n


def commands(m, out_dir):
    """The two Gradle command lines that build map `m` into out_dir (relative to the repo: devtool and tilebuild run there)."""
    f = files_of(m)
    rel = out_dir.replace("\\", "/")
    dem = (f"tiles --bbox={bbox_arg(m['bbox'])} --min-zoom {m['dem'][0]} --max-zoom {m['dem'][1]} "
           f"--pmtiles {rel}/{f['dem']} --verify")
    cache = os.environ.get("DYSTOPO_CACHE") or os.path.join(REPO, "cache")
    extract = os.path.join(cache, "osm", EXTRACT).replace("\\", "/")
    vec = (f"--extract {extract} --bbox {bbox_arg(m['bbox'])} --min-zoom {m['vector'][0]} --max-zoom {m['vector'][1]} "
           f"--profile outdoors --out {rel}/{f['vector']}")
    return {"dem": (":devtool:run", dem), "vector": (":tilebuild:run", vec)}


def read_stamp(folder):
    try:
        with open(os.path.join(folder, "fixture.json"), encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return None


def missing(folder):
    """The archive names not yet in `folder`, or every name when its fixture.json is of another recipe."""
    names = [n for m in MAPS for n in files_of(m).values()]
    stamp = read_stamp(folder)
    if not stamp or stamp.get("key") != recipe_key():
        return names
    return [n for n in names if not os.path.isfile(os.path.join(folder, n)) or os.path.getsize(os.path.join(folder, n)) == 0]


def write_stamp(folder, built):
    stamp = {"key": recipe_key(), "recipe": recipe(), "files": {}, "built": time.strftime("%Y-%m-%dT%H:%M:%S")}
    for m in MAPS:
        for n in files_of(m).values():
            stamp["files"][n] = os.path.getsize(os.path.join(folder, n))
    stamp["lastBuilt"] = built
    with open(os.path.join(folder, "fixture.json"), "w", encoding="utf-8", newline="\n") as fh:
        json.dump(stamp, fh, indent=1)
    return stamp


def gradle(task, args, log):
    exe = os.path.join(REPO, "gradlew.bat" if os.name == "nt" else "gradlew")
    cmd = [exe, task, f"--args={args}", "--console=plain", "-q", "--no-daemon", "-Pkotlin.daemon.jvmargs=-Xmx4g"]
    with open(log, "a", encoding="utf-8") as fh:
        fh.write("$ " + " ".join(cmd) + "\n")
        fh.flush()
        p = subprocess.Popen(cmd, cwd=REPO, stdout=fh, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        try:
            return p.wait(timeout=1800)
        except subprocess.TimeoutExpired:
            p.kill()  # its own handle only (the Gradle wrapper); the build has --no-daemon, so no daemon outlives it
            return -1


def build(from_dir=None, out_dir=OUT_DIR):
    """Builds what is missing in out_dir (or copies a matching set from from_dir); the report."""
    folder = os.path.join(REPO, out_dir)
    os.makedirs(folder, exist_ok=True)
    t0 = time.time()
    rep = {"folder": out_dir.replace("\\", "/"), "key": recipe_key(), "built": [], "copied": [], "kept": []}
    need = missing(folder)
    if need and from_dir and os.path.isdir(from_dir) and not missing(from_dir):
        for n in need:
            shutil.copyfile(os.path.join(from_dir, n), os.path.join(folder, n))
            rep["copied"].append(n)
        need = []
    if need and read_stamp(folder) and read_stamp(folder).get("key") != recipe_key():
        for n in need:  # another recipe's archives: built anew
            p = os.path.join(folder, n)
            if os.path.isfile(p):
                os.remove(p)
    log = os.path.join(folder, "build.log")
    for m in MAPS:
        for role, (task, args) in commands(m, out_dir).items():
            name = files_of(m)[role]
            if name not in need:
                if name not in rep["copied"]:
                    rep["kept"].append(name)
                continue
            t = time.time()
            code = gradle(task, args, log)
            p = os.path.join(folder, name)
            if code != 0 or not os.path.isfile(p):
                rep.update(ok=False, error=f"{task} for {name} failed (exit {code}); see {out_dir}/build.log", seconds=round(time.time() - t0, 1))
                return rep
            rep["built"].append({"file": name, "seconds": round(time.time() - t, 1), "bytes": os.path.getsize(p)})
    stamp = write_stamp(folder, [b["file"] for b in rep["built"]] + rep["copied"]) if (rep["built"] or rep["copied"] or not read_stamp(folder)) else read_stamp(folder)
    rep["files"] = stamp["files"]
    rep["ok"] = True
    rep["seconds"] = round(time.time() - t0, 1)
    return rep


# --- the device -----------------------------------------------------------------------------------------------------

class Device:
    def __init__(self, serial, port):
        self.exe = ad.adb_path()
        if not self.exe:
            raise RuntimeError("adb not found (set ANDROID_HOME)")
        self.serial, self.port, self.token = serial, port, None

    def adb(self, *args, timeout=120):
        return ad.adb(self.exe, self.serial, *args, timeout=timeout)

    def shell(self, cmd, timeout=120):
        return self.adb("shell", cmd, timeout=timeout)

    def runas(self, script, timeout=120):
        return self.shell(f"run-as {PACKAGE} sh -c '{script}'", timeout=timeout)

    def restart(self, timeout=90.0):
        """Force-stop and a cold start; reads the debug port and token again and forwards the host port."""
        self.adb("shell", "am", "force-stop", PACKAGE, timeout=30)
        since = ad.device_now(self.exe, self.serial)
        self.adb("logcat", "-c", timeout=30)
        self.adb("shell", "am", "start", "-W", "-n", ad.activity(PACKAGE), timeout=120)
        _, self.token = ad.bootstrap(self.exe, self.serial, self.port, timeout=timeout, since=since)
        return self.token

    def connect(self):
        if not ad.app_pid(self.exe, self.serial):
            return self.restart()
        _, self.token = ad.bootstrap(self.exe, self.serial, self.port, timeout=30.0)
        return self.token

    def call(self, path, method="GET", timeout=60, body=None):
        """(status, parsed JSON or None, error text or None) for one debug route."""
        import urllib.error
        import urllib.request
        if self.token is None:
            self.connect()
        sep = "&" if "?" in path else "?"
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}{sep}t={self.token}", method=method,
                                     data=(body if body is not None else (b"" if method == "POST" else None)))
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                text = r.read().decode("utf-8")
                return r.status, (json.loads(text) if text.strip() else None), None
        except urllib.error.HTTPError as e:
            text = e.read().decode("utf-8", "replace")
            try:
                return e.code, json.loads(text), f"HTTP {e.code}"
            except ValueError:
                return e.code, None, f"HTTP {e.code}: {text[:200]}"
        except Exception as e:  # a refused connection, a timeout, a body that is not JSON
            return None, None, f"{type(e).__name__}: {e}"[:300]


def network(dev, on, wait_s=30.0):
    """Airplane mode off (on=True) or on; {"network": "on"|"off", "airplane": bool, "route": bool, "seconds"}."""
    t0 = time.time()
    dev.adb("shell", "cmd", "connectivity", "airplane-mode", "disable" if on else "enable", timeout=30)
    want = "0" if on else "1"
    airplane = None
    for _ in range(40):
        _, out = dev.adb("shell", "settings", "get", "global", "airplane_mode_on", timeout=30)
        if out.strip() == want:
            airplane = not on
            break
        time.sleep(0.5)
    route = None
    while time.time() - t0 < wait_s:
        code, out = dev.shell("ip route get 8.8.8.8 >/dev/null 2>&1 && echo up || echo down", timeout=30)
        route = out.strip().endswith("up")
        if route == on:
            break
        time.sleep(1.0)
    ok = airplane is not None and route == on
    return {"network": "on" if on else "off", "ok": ok, "airplane": airplane, "route": route, "seconds": round(time.time() - t0, 1)}


def pack_names(packs_json):
    """The names GET /packs lists, and those in use, from its answer."""
    packs = (packs_json or {}).get("packs") or []
    names = [p.get("name") for p in packs if isinstance(p, dict)]
    in_use = [p.get("name") for p in ((packs_json or {}).get("inUse") or []) if isinstance(p, dict)]
    return names, in_use


def read_maps(dev):
    """What the phone says it holds: GET /packs (names, in use, the primary) and GET /debug/startup's head.packs."""
    st, packs, err = dev.call("/packs")
    names, in_use = pack_names(packs)
    st2, trace, err2 = dev.call("/debug/startup")
    head = (trace or {}).get("head") or {}
    return {"packs": names, "inUse": in_use, "active": (packs or {}).get("active"),
            "startupPacks": int(head["packs"]) if str(head.get("packs", "")).isdigit() else None,
            "errors": [e for e in (err, err2) if e]}


def install(dev, out_dir=OUT_DIR):
    t0 = time.time()
    folder = os.path.join(REPO, out_dir)
    rep = {"serial": dev.serial, "maps": [m["name"] for m in MAPS]}
    # Every other map aside, in both roots (an earlier part may have pushed to either); a second install finds them there.
    dev.runas(f"mkdir -p files/{ASIDE} && for f in files/packs/*.pmtiles; do [ -f \"$f\" ] && mv \"$f\" files/{ASIDE}/; done; true")
    dev.shell(f"mkdir -p {EXTERNAL}/{ASIDE} && for f in {EXTERNAL}/packs/*.pmtiles; do [ -f \"$f\" ] && mv \"$f\" {EXTERNAL}/{ASIDE}/; done; true")
    _, aside = dev.runas(f"ls files/{ASIDE}")
    _, aside_ext = dev.shell(f"ls {EXTERNAL}/{ASIDE}")
    rep["aside"] = sorted(set(x for x in (aside + "\n" + aside_ext).split() if x.endswith(".pmtiles")))
    staging = "/data/local/tmp/two-maps"
    dev.shell(f"rm -rf {staging} && mkdir -p {staging}")
    dev.runas("mkdir -p files/packs")
    for m in MAPS:
        for name in files_of(m).values():
            code, out = dev.adb("push", os.path.join(folder, name), f"{staging}/{name}", timeout=600)
            if code != 0:
                rep.update(ok=False, error=f"adb push {name}: {out.strip()[-200:]}")
                return rep
            dev.runas(f"cp {staging}/{name} files/packs/{name}")
    dev.shell(f"rm -rf {staging}")
    dev.restart()
    maps = read_maps(dev)
    for _ in range(20):  # the catalog is read off the main thread after the first frame
        if set(rep["maps"]) <= set(maps["packs"]):
            break
        time.sleep(1.0)
        maps = read_maps(dev)
    rep.update(maps)
    rep["ok"] = sorted(maps["packs"]) == sorted(rep["maps"]) and maps["startupPacks"] == len(MAPS)
    rep["seconds"] = round(time.time() - t0, 1)
    return rep


def restore(dev):
    t0 = time.time()
    rep = {"serial": dev.serial, "network": network(dev, True)}
    names = " ".join(n for m in MAPS for n in files_of(m).values())
    dev.runas(f"cd files/packs && rm -f {names}; true")
    dev.runas(f"[ -d files/{ASIDE} ] && mv files/{ASIDE}/*.pmtiles files/packs/ 2>/dev/null; rmdir files/{ASIDE} 2>/dev/null; true")
    dev.shell(f"[ -d {EXTERNAL}/{ASIDE} ] && mv {EXTERNAL}/{ASIDE}/*.pmtiles {EXTERNAL}/packs/ 2>/dev/null; rmdir {EXTERNAL}/{ASIDE} 2>/dev/null; true")
    dev.restart()
    rep.update(read_maps(dev))
    rep["ok"] = rep["network"]["ok"] and not (set(rep["packs"]) & {m["name"] for m in MAPS})
    rep["seconds"] = round(time.time() - t0, 1)
    return rep


def main(argv=None):
    ap = argparse.ArgumentParser(description="T339: the two-maps fixture (two overlapping Rainier maps) for an emulator")
    ap.add_argument("cmd", choices=["build", "install", "network", "restore", "recipe"])
    ap.add_argument("state", nargs="?", choices=["on", "off"], help="network: on or off")
    ap.add_argument("--serial", help="the emulator's serial (install, network, restore)")
    ap.add_argument("--port", type=int, default=8696, help="the host port forwarded to the debug server")
    ap.add_argument("--from", dest="from_dir", default="", help="build: a folder with a set built from the same recipe, copied instead")
    ap.add_argument("--out-dir", default=OUT_DIR, help="build: where the archives go, relative to the repo (default out/two-maps)")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "recipe":
            rep = {"ok": True, "key": recipe_key(), "recipe": recipe(), "places": PLACES}
        elif a.cmd == "build":
            rep = build(a.from_dir or None, a.out_dir)
        else:
            if not a.serial:
                ap.error(f"{a.cmd} needs --serial")
            dev = Device(a.serial, a.port)
            if a.cmd == "network":
                if not a.state:
                    ap.error("network needs on or off")
                rep = network(dev, a.state == "on")
            elif a.cmd == "install":
                rep = build(a.from_dir or None, a.out_dir)
                if rep.get("ok"):
                    rep = {"build": rep, **install(dev, a.out_dir)}
            else:
                rep = restore(dev)
    except Exception as e:  # one JSON object whatever happens
        rep = {"ok": False, "error": f"{type(e).__name__}: {e}"[:500]}
    print(json.dumps(rep))
    return 0 if rep.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
