"""Launches the Android app again and again and reports its first frame and longest stall (T228).

The start trace's own figures (`StartupTrace`, `GET /debug/startup`) next to the system's (`am start -W`, which runs whatever
the app does, so it also reads a build with the trace off and a release build, which has no debug routes):

  python -I tools/android-startup.py --serial emulator-5574 --launches 10             # cold: force-stops before each launch
  python -I tools/android-startup.py --serial emulator-5574 --launches 5 --warm       # the activity finished, the process alive
  python -I tools/android-startup.py --serial emulator-5574 --launches 5 --hot        # HOME, then back
  python -I tools/android-startup.py --serial emulator-5574 --launches 10 --no-trace  # a release build, or a scratch build with the trace off

Prints one JSON object: `launches` (per launch `amTotalMs`, `amWaitMs`, `launchState`, `kind`, `firstFrameMs`, `longestStallMs`,
`reasons`, `marks`, and T266's `launchOverheadMs`, the launch runner's own overhead from `GET /debug/startup` `launch.overheadMs`,
ADR 0061), `medians` (`amTotalMs`, `firstFrameMs`, `longestStallMs`, `launchOverheadMs`) and, cold or warm, the marks'
`markMedians` (ms by name, the slowest first). The stages themselves are `GET /debug/launch`'s. Each launch waits for `--wait` s (at most) for the trace's first frame, then `--settle` s before the next;
a device that is still busy reads as slow, so run it on a quiet one (`android-emulator.md`).

On an emulator with `-gpu host` every cold start of this app grows qemu by about 0.5 GB of private memory, trace on or off, and a
reboot is the only thing seen to give it back (T228: 85 launches reached 44 GB). So run at most six cold launches a boot and read
the emulator's private bytes between runs; a warm or hot return, or another app's cold start, costs far less.
Boot through `tools/android-emu.ps1`, whose guard (T231) stops the emulator past its private-bytes limit or under the free-RAM floor and
logs both to `out/emulator-guard-<avd>.log` (`android-emulator.md`); a run the guard cut short ends in an adb error. Clear an earlier
run's thresholds and pending stall first (`POST /debug/startup/thresholds?clear=1`, `POST /debug/startup/stall?clear=1`).

T268, the launch budget (the sweep's `startup` part, `tools/sweep.ps1`):

  python -I tools/android-startup.py --serial emulator-5570 --port 8694 --launches 5 --seed-tracks 52 --dem out/rainier-dem.pmtiles --osm out/rainier-outdoors.pmtiles --budget tools/launch-budget.json
  python -I tools/android-startup.py --set-budget out/sweep-a/startup.json out/sweep-b/startup.json --budget tools/launch-budget.json

Cold, each launch also waits for the map drawn (`fullyLoadedMs`) and reads `GET /debug/launch`: every stage's `ms` and the
StrictMode kinds its declared work does not allow (`undeclared`). The report gains `stageMedians` (per stage: `medianMs`,
`maxMs`, `launches`, `thread`), `medians.fullyLoadedMs`, `undeclared` (stage, kind, launches) and `seed`. `--seed-tracks N`
first imports N synthetic GPX tracks over Rainier (areas.json's box; fixed ids in `dystopo:id`, so a second run imports
none) through `POST /tracks/import`, and `--dem`/`--osm` copy the packs to `files/packs/` (as `android-emu.ps1` does) when
the phone's copy differs in size; then the device's load is waited out (`android_debug.wait_quiet`). `--budget` compares
the medians with the file's ceilings (`budget`: one row per stage and frame, `red` and its `reasons`) and exits 1 when a
median is over its ceiling or a stage shows an undeclared StrictMode kind; a stage missing from the run or from the file
is listed, not red. `--set-budget` writes the file from the reports of several sweeps (`make_budget`, `ceiling`: the largest median
times 1.25, and never under it plus 16 ms, `FLOOR_MS`); lower a ceiling by
hand in the commit that lands an improvement, never raise one without a line in the dated log.
"""
import argparse
import importlib.util
import json
import os
import re
import statistics
import sys
import time
import math
import random
import uuid
import urllib.error
import urllib.request

_spec = importlib.util.spec_from_file_location("android_debug", os.path.join(os.path.dirname(os.path.abspath(__file__)), "android_debug.py"))
android_debug = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(android_debug)
adb = android_debug.adb

PACKAGE = android_debug.PACKAGE
ACTIVITY = PACKAGE + "/io.github.jasondorjeshort.dystopo.app.MainActivity"
AM_LINE = re.compile(r"^\s*(TotalTime|WaitTime|ThisTime|LaunchState):\s*(\S+)", re.M)


def parse_am_start(text):
    """`am start -W`'s figures: `amTotalMs`, `amWaitMs`, `launchState`, each None when the output has none."""
    found = dict(AM_LINE.findall(text or ""))

    def num(key):
        v = found.get(key)
        return int(v) if v is not None and v.isdigit() else None

    return {"amTotalMs": num("TotalTime"), "amWaitMs": num("WaitTime"), "launchState": found.get("LaunchState")}


def parse_trace(j):
    """One launch's line from `GET /debug/startup`: the window's kind, figures and marks."""
    w = (j or {}).get("window") or {}
    return {
        "state": (j or {}).get("state"), "kind": w.get("kind"), "kindSource": w.get("kindSource"),
        "firstFrameMs": w.get("firstFrameMs"), "fullyLoadedMs": w.get("fullyLoadedMs"), "longestStallMs": w.get("longestStallMs"), "reasons": w.get("reasons"),
        "launchOverheadMs": ((j or {}).get("launch") or {}).get("overheadMs"),  # T266: the launch runner's own overhead
        "marks": [{"name": m.get("name"), "atMs": m.get("atMs"), "ms": m.get("ms"), "samples": m.get("samples"), "cpuMs": m.get("cpuMs"), "frame": m.get("frame")} for m in w.get("marks") or []],
    }


def median(values):
    v = [x for x in values if x is not None]
    return statistics.median(v) if v else None


def mark_medians(launches):
    """The median ms of each mark over the launches that had it, the slowest first; {} with no trace."""
    by = {}
    for ln in launches:
        for m in ln.get("marks") or []:
            if m.get("ms") is not None:
                by.setdefault(m["name"], []).append(m["ms"])
    rows = [{"name": k, "medianMs": statistics.median(v), "maxMs": max(v), "launches": len(v)} for k, v in by.items()]
    return sorted(rows, key=lambda r: -r["medianMs"])


def parse_launch(j):
    """T268: one launch's stages from `GET /debug/launch`: name, ms (None when it has no end), thread, undeclared kinds."""
    return [{"name": st.get("name"), "ms": st.get("ms"), "thread": st.get("thread"), "undeclared": list(st.get("undeclared") or []),
             "startMs": st.get("startMs"), "endMs": st.get("endMs")}
            for st in (j or {}).get("stages") or [] if st.get("name")]


def parse_package_reads(trace, firstrun):
    """T280: the APK's update time as each launch read it: `assets` (main thread) and `world` (its worker) from `GET /firstrun`
    `assets` (`versionMs`, the `getPackageInfo` call; `checkMs`, the marker and folders after it; `versionShared`, the read
    the stage found made), and the trace's own read (`GET /debug/startup` `head.packageInfoMs`, on its I/O thread)."""
    a = (firstrun or {}).get("assets") or {}
    w = a.get("world") or {}
    head = ((trace or {}).get("head")) or {}

    def num(v):
        try:
            return float(v)
        except (TypeError, ValueError):
            return None
    keys = ("versionMs", "checkMs", "versionShared")
    return {"assets": {k: a.get(k) for k in keys if k in a}, "world": {k: w.get(k) for k in keys if k in w},
            "trace": {"packageInfoMs": num(head.get("packageInfoMs")), "packageInfoEndMs": num(head.get("packageInfoEndMs"))}}


def stage_medians(launches):
    """T268: the median ms of each stage over the launches that timed it, in the list's order."""
    by, order, threads = {}, [], {}
    for ln in launches:
        for st in ln.get("stages") or []:
            n = st["name"]
            if n not in order:
                order.append(n)
            if st.get("thread"):
                threads[n] = st["thread"]
            if st.get("ms") is not None:
                by.setdefault(n, []).append(st["ms"])
    return [{"name": n, "medianMs": statistics.median(by[n]) if n in by else None, "maxMs": max(by[n]) if n in by else None,
             "launches": len(by.get(n, [])), "thread": threads.get(n)} for n in order]


def undeclared(launches):
    """T268: each stage and StrictMode kind its declared work does not allow, with the launches (from 1) it was seen in."""
    out = {}
    for i, ln in enumerate(launches):
        for st in ln.get("stages") or []:
            for kind in st.get("undeclared") or []:
                out.setdefault((st["name"], kind), []).append(i + 1)
    return [{"stage": s, "kind": k, "launches": v} for (s, k), v in out.items()]


def summary(launches, mode):
    out = {
        "mode": mode, "launches": launches,
        "medians": {k: median(ln.get(k) for ln in launches) for k in ("amTotalMs", "firstFrameMs", "fullyLoadedMs", "longestStallMs", "launchOverheadMs")},
        "markMedians": mark_medians(launches),
    }
    if any(ln.get("stages") for ln in launches):  # T268
        out["stageMedians"] = stage_medians(launches)
        out["undeclared"] = undeclared(launches)
    return out


# ---------- T268: the launch budget ----------

FACTOR = 1.25
# A stage of a few ms swings by more than a quarter of itself from launch to launch (0 to 11 ms for cacheIndex in five
# launches, T268's first run) and `GET /debug/launch` times in whole ms, so a ceiling is never closer to its median than one
# frame at 60 Hz: a new disk read on the main thread costs more than that and still shows.
FLOOR_MS = 16
FRAMES = ("firstFrameMs", "fullyLoadedMs")


def ceiling(medians, factor=FACTOR, floor=FLOOR_MS):
    """The ceiling from one or two runs' medians: the larger times `factor`, rounded up to a whole ms, and at least that
    median plus `floor` ms."""
    v = [m for m in medians if m is not None]
    return max(math.ceil(max(v) * factor - 1e-9), math.ceil(max(v)) + floor) if v else None


def check_budget(report, budget):
    """The medians of `report` against the ceilings of `budget`: rows (`name`, `kind` stage or frame, `medianMs`,
    `ceilingMs`, `status` ok, over, missing or unbudgeted), the undeclared StrictMode kinds, `red` and its `reasons`.
    `aliases` ({old name: new name}) lets a ceiling set under a stage's old name read the renamed stage."""
    aliases = budget.get("aliases") or {}
    measured = {r["name"]: r for r in report.get("stageMedians") or []}
    rows, reasons, seen = [], [], set()
    for name, c in (budget.get("frames") or {}).items():
        rows.append(_row(name, "frame", (report.get("medians") or {}).get(name), c.get("ceilingMs"), reasons))
    for name, c in (budget.get("stages") or {}).items():
        reads = aliases.get(name, name)
        seen.add(reads)
        rows.append(_row(reads, "stage", (measured.get(reads) or {}).get("medianMs"), c.get("ceilingMs"), reasons))
    for name, r in measured.items():
        if name not in seen:
            rows.append({"name": name, "kind": "stage", "medianMs": r.get("medianMs"), "ceilingMs": None, "status": "unbudgeted"})
    bad = report.get("undeclared") or []
    for u in bad:
        reasons.append(f"stage {u['stage']}: StrictMode {u['kind']}, which its declared work does not allow (launches {u['launches']})")
    return {"set": budget.get("set"), "factor": budget.get("factor"), "rows": rows, "undeclared": bad, "red": bool(reasons), "reasons": reasons}


def _row(name, kind, median_ms, ceiling_ms, reasons):
    if median_ms is None:
        return {"name": name, "kind": kind, "medianMs": None, "ceilingMs": ceiling_ms, "status": "missing"}
    over = ceiling_ms is not None and median_ms > ceiling_ms
    if over:
        reasons.append(f"{kind} {name}: median {median_ms} ms over its ceiling of {ceiling_ms} ms")
    return {"name": name, "kind": kind, "medianMs": median_ms, "ceilingMs": ceiling_ms, "status": "over" if over else "ok"}


def format_budget(b):
    """The budget file's text: one line per frame and per stage, so a lowered ceiling is a one-line diff."""
    keys = list(b)
    lines = ["{"]
    for i, k in enumerate(keys):
        end = "," if i < len(keys) - 1 else ""
        v = b[k]
        if k in ("frames", "stages") and v:
            names = list(v)
            lines.append(f" {json.dumps(k)}: {{")
            lines += [f"  {json.dumps(n)}: {json.dumps(v[n])}" + ("," if j < len(names) - 1 else "") for j, n in enumerate(names)]
            lines.append(" }" + end)
        else:
            lines.append(f" {json.dumps(k)}: {json.dumps(v)}{end}")
    return "\n".join(lines) + "\n}\n"


def make_budget(reports, sources, date, factor=FACTOR, old=None):
    """The budget file from one or two reports: each frame's and stage's ceiling by `ceiling`, with the medians it came from."""
    old = old or {}
    frames, stages = {}, {}
    for k in FRAMES:
        ms = [(r.get("medians") or {}).get(k) for r in reports]
        if any(m is not None for m in ms):
            frames[k] = {"ceilingMs": ceiling(ms, factor), "medianMs": ms}
    names = []
    for r in reports:
        for row in r.get("stageMedians") or []:
            if row["name"] not in names:
                names.append(row["name"])
    for n in names:
        ms = [next((row.get("medianMs") for row in r.get("stageMedians") or [] if row["name"] == n), None) for r in reports]
        if any(m is not None for m in ms):
            stages[n] = {"ceilingMs": ceiling(ms, factor), "medianMs": ms}
    return {
        "about": "T268: the launch budget. Ceilings in ms per stage of GET /debug/launch (shared launch/Launch.kt) and for the "
                 "first frame and the map drawn, against the medians of tools/android-startup.py --launches 5 (cold, a seeded "
                 "store) in the sweep's startup part; a median over its ceiling, or a StrictMode kind a stage's declared work "
                 "does not allow, turns the part red.",
        "rule": "Set by android-startup.py --set-budget from the medians of sweeps (the first ceilings: three sweeps of 2026-11-13): the largest times the factor, rounded "
                "up to whole ms, and never under that median plus floorMs (one frame at 60 Hz; a stage of a few ms swings by "
                "more than a quarter of itself). Lower a ceiling by hand in the commit that lands an improvement; never raise "
                "one without a line in the dated log. A renamed stage keeps its ceiling through aliases, {old name: new name}.",
        "factor": factor,
        "floorMs": FLOOR_MS,
        "set": {"date": date, "from": sources},
        "frames": frames,
        "stages": stages,
        "aliases": old.get("aliases") or {},
    }


def log(*a):
    print(*a, file=sys.stderr, flush=True)


class Trace:
    """`GET /debug/startup` of the app's current process, through a forwarded host port."""

    def __init__(self, exe, serial, port):
        self.exe, self.serial, self.port = exe, serial, port
        self.token = None

    def reset(self):
        self.token = None

    def read(self, timeout=10.0):
        if self.token is None:
            _, self.token = android_debug.bootstrap(self.exe, self.serial, self.port, timeout=30.0)
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}/debug/startup", headers={"X-Dystopo-Token": self.token})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))

    def call(self, path, method="GET", body=None, timeout=60.0):
        """T268: one request to the debug server with the token; the JSON answer."""
        if self.token is None:
            _, self.token = android_debug.bootstrap(self.exe, self.serial, self.port, timeout=30.0)
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=body, method=method,
                                     headers={"X-Dystopo-Token": self.token, "Content-Type": "application/gpx+xml"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8"))

    def wait_launch(self, wait):
        """T268: the trace once the map is drawn (`fullyLoadedMs`) and `GET /debug/launch` once every stage that started has
        ended, or the last reads when `wait` s pass first: (trace, launch)."""
        deadline = time.time() + wait
        trace = launch = None
        while time.time() < deadline:
            try:
                trace = self.read()
                if ((trace.get("window") or {}).get("fullyLoadedMs")) is not None:
                    launch = self.call("/debug/launch", timeout=10.0)
                    if all(st.get("endMs") is not None for st in launch.get("stages") or [] if st.get("startMs") is not None):
                        return trace, launch
            except (RuntimeError, OSError, ValueError, urllib.error.URLError):
                self.token = None
            time.sleep(0.5)
        return trace, launch

    def wait_frame(self, wait, opened_after=None):
        """The trace once its window has a first frame and is the one this launch opened; the last read if none came in time."""
        deadline = time.time() + wait
        last = None
        while time.time() < deadline:
            try:
                last = self.read()
                w = last.get("window") or {}
                if w.get("firstFrameMs") is not None and w.get("id") != opened_after:
                    return last
            except (RuntimeError, OSError, ValueError, urllib.error.URLError):
                self.token = None
            time.sleep(0.3)
        return last


# ---------- T268: the seeded store ----------

RAINIER = (-121.86, 46.78, -121.66, 46.93)  # areas.json's public box (west, south, east, north)
SEED_NS = uuid.UUID("6f1b2a9e-3c4d-4e5f-8a7b-268268268268")


def seed_gpx(i, points, box=RAINIER):
    """Synthetic track `i` (0-based) as GPX: a seeded walk of `points` fixes 5 s apart inside `box`, its id fixed by `i`
    (`dystopo:id`), so importing it twice keeps one copy. Returns (id, text)."""
    rnd = random.Random(268000 + i)
    w, so, e, n = box
    lat, lon = so + (n - so) * rnd.uniform(0.2, 0.8), w + (e - w) * rnd.uniform(0.2, 0.8)
    ele, heading = rnd.uniform(800, 2400), rnd.uniform(0, 2 * math.pi)
    t0 = 1767225600 + i * 86400 + 8 * 3600  # 2026-01-01 08:00 UTC, a day apart
    tid = str(uuid.uuid5(SEED_NS, f"track-{i}"))
    pts = []
    for k in range(points):
        heading += rnd.uniform(-0.4, 0.4)
        lat = min(n, max(so, lat + 0.00009 * math.cos(heading)))
        lon = min(e, max(w, lon + 0.00013 * math.sin(heading)))
        ele = min(4300.0, max(500.0, ele + rnd.uniform(-2.0, 2.5)))
        ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t0 + 5 * k))
        pts.append(f'<trkpt lat="{lat:.6f}" lon="{lon:.6f}"><ele>{ele:.1f}</ele><time>{ts}</time></trkpt>')
    text = ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<gpx version="1.1" creator="dystopo android-startup.py" xmlns="http://www.topografix.com/GPX/1/1" '
            'xmlns:dystopo="https://jasondorjeshort.github.io/dystopo/gpx/1">'
            f'<trk><name>Launch seed {i + 1:02d}</name><extensions><dystopo:id>{tid}</dystopo:id></extensions>'
            f'<trkseg>{"".join(pts)}</trkseg></trk></gpx>\n')
    return tid, text


def push_pack(exe, serial, src, name):
    """Copies `src` to the app's files/packs/<name> through run-as unless the phone's copy has the same size; True when copied."""
    size = os.path.getsize(src)
    _, out = adb(exe, serial, "shell", "run-as", PACKAGE, "stat", "-c", "%s", f"files/packs/{name}", timeout=60)
    if out.strip().isdigit() and int(out.strip()) == size:
        return False
    staged = f"/data/local/tmp/dystopo-startup-{name}"
    code, out = adb(exe, serial, "push", src, staged, timeout=600)
    if code != 0:
        raise RuntimeError(f"adb push {src} failed: {out.strip()[-300:]}")
    code, out = adb(exe, serial, "shell", f"run-as {PACKAGE} mkdir -p files/packs && run-as {PACKAGE} cp {staged} files/packs/{name}; rm -f {staged}", timeout=300)
    if code != 0:
        raise RuntimeError(f"copying {name} into files/packs failed: {out.strip()[-300:]}")
    return True


def seed(args, exe, trace):
    """The packs (`--dem`, `--osm`) and `--seed-tracks` synthetic tracks, then the counts the report gives as `seed`."""
    t0 = time.time()
    out = {"packsCopied": []}
    for src, name in ((args.dem, "dem.pmtiles"), (args.osm, "osm.pmtiles")):
        if src and push_pack(exe, args.serial, src, name):
            out["packsCopied"].append(name)
    adb(exe, args.serial, "shell", "am", "start", "-W", "-n", ACTIVITY, timeout=180)
    trace.reset()
    have = set()
    deadline = time.time() + 120
    while True:
        try:
            have = {t.get("id") for t in trace.call("/tracks").get("tracks") or []}
            break
        except (RuntimeError, OSError, ValueError, urllib.error.URLError):
            trace.reset()
            if time.time() > deadline:
                raise
            time.sleep(1.0)
    imported = 0
    for i in range(args.seed_tracks):
        tid, text = seed_gpx(i, args.seed_points)
        if tid in have:
            continue
        r = trace.call("/tracks/import", "POST", text.encode("utf-8"))
        imported += len(r.get("imported") or [])
    tracks = trace.call("/tracks").get("tracks") or []
    _, ls = adb(exe, args.serial, "shell", "run-as", PACKAGE, "ls", "files/packs", timeout=60)
    out.update({
        "tracks": len(tracks), "tracksImported": imported, "seedTracks": args.seed_tracks, "seedPoints": args.seed_tracks * args.seed_points,
        "points": sum(t.get("points") or 0 for t in tracks) if any("points" in t for t in tracks) else None,
        "packs": sorted(x for x in ls.split() if x.endswith(".pmtiles")), "seconds": round(time.time() - t0, 1),
    })
    log(f"seed: {out['tracks']} tracks ({imported} imported now), packs {out['packs']} ({out['packsCopied'] or 'none'} copied) in {out['seconds']} s")
    return out


def run(args):
    exe = args.adb or android_debug.adb_path()
    if not exe:
        raise SystemExit("adb not found")
    mode = "hot" if args.hot else "warm" if args.warm else "cold"
    trace = None if args.no_trace else Trace(exe, args.serial, args.port)
    launches = []
    previous = None
    seeded = None
    if trace and (args.seed_tracks or args.dem or args.osm):  # T268
        seeded = seed(args, exe, trace)
    if args.quiet:  # T268: a device still busy reads as slow
        pid = android_debug.app_pid(exe, args.serial)
        q = android_debug.wait_quiet(exe, args.serial, pid, timeout=args.quiet)
        log(f"quiet: {q.get('quiet')} after {q.get('waitedS')} s")
        seeded = dict(seeded or {}, quiet={"quiet": q.get("quiet"), "waitedS": q.get("waitedS")})
    if mode != "cold":
        adb(exe, args.serial, "shell", "am", "start", "-n", ACTIVITY)
        time.sleep(args.settle)
        if trace:
            previous = ((trace.wait_frame(args.wait) or {}).get("window") or {}).get("id")
    for i in range(args.launches):
        if mode == "cold":
            adb(exe, args.serial, "shell", "am", "force-stop", PACKAGE)
            time.sleep(1.0)
            if trace:
                trace.reset()
        elif mode == "warm":
            adb(exe, args.serial, "shell", "input", "keyevent", "BACK")
            time.sleep(2.0)
        else:
            adb(exe, args.serial, "shell", "input", "keyevent", "HOME")
            time.sleep(2.0)
        _, out = adb(exe, args.serial, "shell", "am", "start", "-W", "-n", ACTIVITY, timeout=180)
        line = parse_am_start(out)
        if trace:
            t = parse_trace(trace.wait_frame(args.wait, previous))
            if mode == "cold":  # T268: the map drawn, then every stage's time from GET /debug/launch
                full, launch = trace.wait_launch(args.wait)
                if full:
                    t = parse_trace(full)
                t["stages"] = parse_launch(launch)
                try:  # T280: how long the update-time read took in each place
                    t["packageReads"] = parse_package_reads(full, trace.call("/firstrun", timeout=10.0))
                except (RuntimeError, OSError, ValueError, urllib.error.URLError) as e:
                    t["packageReads"] = {"error": str(e)[:200]}
            line.update(t)
            previous = None
            try:
                previous = (trace.read().get("window") or {}).get("id")
            except (RuntimeError, OSError, ValueError, urllib.error.URLError):
                pass
        st = {x["name"]: x.get("ms") for x in line.get("stages") or []}
        if "world" in st or "assets" in st:  # T280
            log(f"launch {i + 1}: world {st.get('world')} ms, assets {st.get('assets')} ms, update-time reads {json.dumps(line.get('packageReads'))}")
        log(f"launch {i + 1}: am {line.get('amTotalMs')} ms, {line.get('kind')}, first frame {line.get('firstFrameMs')} ms, map drawn {line.get('fullyLoadedMs')} ms, longest stall {line.get('longestStallMs')} ms, runner {line.get('launchOverheadMs')} ms")
        launches.append(line)
        time.sleep(args.settle)
    out = summary(launches, mode)
    if seeded is not None:
        out["seed"] = seeded
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial")
    ap.add_argument("--launches", type=int, default=10)
    how = ap.add_mutually_exclusive_group()
    how.add_argument("--warm", action="store_true", help="BACK then launch: a new activity in a live process")
    how.add_argument("--hot", action="store_true", help="HOME then launch: the activity comes back")
    ap.add_argument("--no-trace", action="store_true", help="only `am start -W`: a release build, or a build with the trace off")
    ap.add_argument("--port", type=int, default=8698, help="host port forwarded to the app's debug server")
    ap.add_argument("--wait", type=float, default=60.0, help="seconds to wait for each launch's first frame in the trace")
    ap.add_argument("--settle", type=float, default=4.0, help="seconds between one launch and the next")
    ap.add_argument("--adb", help="adb's path (default: from ANDROID_HOME)")
    # T268: the seeded store and the launch budget
    ap.add_argument("--seed-tracks", type=int, default=0, help="import this many synthetic tracks first (fixed ids: none twice)")
    ap.add_argument("--seed-points", type=int, default=500, help="fixes per synthetic track")
    ap.add_argument("--dem", help="DEM pack copied to files/packs/dem.pmtiles first unless the phone's has its size")
    ap.add_argument("--osm", help="vector pack copied to files/packs/osm.pmtiles first unless the phone's has its size")
    ap.add_argument("--quiet", type=float, default=0, help="before the launches, wait up to this many s for a quiet device")
    ap.add_argument("--budget", help="the ceilings file (tools/launch-budget.json): compare, exit 1 when red")
    ap.add_argument("--set-budget", nargs="+", metavar="REPORT", help="write --budget from one or two reports instead of launching")
    args = ap.parse_args()
    if args.set_budget:
        if not args.budget:
            ap.error("--set-budget needs --budget, the file to write")
        reports = [load_report(p) for p in args.set_budget]
        old = load_report(args.budget) if os.path.exists(args.budget) else None
        b = make_budget(reports, [p.replace(os.sep, "/") for p in args.set_budget], time.strftime("%Y-%m-%d"), old=old)
        with open(args.budget, "w", encoding="utf-8", newline="\n") as f:
            f.write(format_budget(b))
        print(json.dumps(b, indent=1))
        return 0
    if not args.serial:
        ap.error("--serial is required")
    out = run(args)
    if args.budget:
        out["budget"] = check_budget(out, load_report(args.budget))
        out["budget"]["file"] = args.budget.replace(os.sep, "/")
        for r in out["budget"]["reasons"]:
            log("RED: " + r)
    print(json.dumps(out, indent=1))
    return 1 if (out.get("budget") or {}).get("red") else 0


def load_report(path):
    """A report or budget file: its last JSON value (a report captured with log lines around it reads too)."""
    with open(path, encoding="utf-8-sig") as f:
        text = f.read()
    try:
        return json.loads(text)
    except ValueError:
        return json.loads(text[text.index("{"):text.rindex("}") + 1])


if __name__ == "__main__":
    sys.exit(main())
