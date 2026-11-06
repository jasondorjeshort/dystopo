"""Launches the Android app again and again and reports its first frame and longest stall (T228).

The start trace's own figures (`StartupTrace`, `GET /debug/startup`) next to the system's (`am start -W`, which runs whatever
the app does, so it also reads a build with the trace off and a release build, which has no debug routes):

  python -I tools/android-startup.py --serial emulator-5574 --launches 10             # cold: force-stops before each launch
  python -I tools/android-startup.py --serial emulator-5574 --launches 5 --warm       # the activity finished, the process alive
  python -I tools/android-startup.py --serial emulator-5574 --launches 5 --hot        # HOME, then back
  python -I tools/android-startup.py --serial emulator-5574 --launches 10 --no-trace  # a release build, or a scratch build with the trace off

Prints one JSON object: `launches` (per launch `amTotalMs`, `amWaitMs`, `launchState`, `kind`, `firstFrameMs`, `longestStallMs`,
`reasons`, `marks`), `medians` (`amTotalMs`, `firstFrameMs`, `longestStallMs`) and, cold or warm, the marks' `markMedians` (ms by
name, the slowest first). Each launch waits for `--wait` s (at most) for the trace's first frame, then `--settle` s before the next;
a device that is still busy reads as slow, so run it on a quiet one (`android-emulator.md`).

On an emulator with `-gpu host` every cold start of this app grows qemu by about 0.5 GB of private memory, trace on or off, and a
reboot is the only thing seen to give it back (T228: 85 launches reached 44 GB). So run at most six cold launches a boot and read
the emulator's private bytes between runs; a warm or hot return, or another app's cold start, costs far less.
"""
import argparse
import importlib.util
import json
import os
import re
import statistics
import sys
import time
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
        "firstFrameMs": w.get("firstFrameMs"), "longestStallMs": w.get("longestStallMs"), "reasons": w.get("reasons"),
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


def summary(launches, mode):
    return {
        "mode": mode, "launches": launches,
        "medians": {k: median(ln.get(k) for ln in launches) for k in ("amTotalMs", "firstFrameMs", "longestStallMs")},
        "markMedians": mark_medians(launches),
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


def run(args):
    exe = args.adb or android_debug.adb_path()
    if not exe:
        raise SystemExit("adb not found")
    mode = "hot" if args.hot else "warm" if args.warm else "cold"
    trace = None if args.no_trace else Trace(exe, args.serial, args.port)
    launches = []
    previous = None
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
            line.update(t)
            previous = None
            try:
                previous = (trace.read().get("window") or {}).get("id")
            except (RuntimeError, OSError, ValueError, urllib.error.URLError):
                pass
        log(f"launch {i + 1}: am {line.get('amTotalMs')} ms, {line.get('kind')}, first frame {line.get('firstFrameMs')} ms, longest stall {line.get('longestStallMs')} ms")
        launches.append(line)
        time.sleep(args.settle)
    return summary(launches, mode)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", required=True)
    ap.add_argument("--launches", type=int, default=10)
    how = ap.add_mutually_exclusive_group()
    how.add_argument("--warm", action="store_true", help="BACK then launch: a new activity in a live process")
    how.add_argument("--hot", action="store_true", help="HOME then launch: the activity comes back")
    ap.add_argument("--no-trace", action="store_true", help="only `am start -W`: a release build, or a build with the trace off")
    ap.add_argument("--port", type=int, default=8698, help="host port forwarded to the app's debug server")
    ap.add_argument("--wait", type=float, default=60.0, help="seconds to wait for each launch's first frame in the trace")
    ap.add_argument("--settle", type=float, default=4.0, help="seconds between one launch and the next")
    ap.add_argument("--adb", help="adb's path (default: from ANDROID_HOME)")
    args = ap.parse_args()
    print(json.dumps(run(args), indent=1))


if __name__ == "__main__":
    main()
