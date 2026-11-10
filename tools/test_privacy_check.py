"""T271: tools/PrivacyCheck.java's `scan --dump` on a synthetic diagnostic dump, with no device and no private file.

The fixture has DumpFormat's layout (`dystopo-dump 1`, header lines, `== samples`, `== starts`, `== diagnostics`,
`== logcat`, each section ended by an empty line) and a logcat section of the kinds T258's real dump held: threadtime
timestamps, `avg=78.48ms min=1.04ms` timings and GC pauses. A synthetic local file (`--local`) makes the public Rainier
box (areas.json) stand in for a private one, so the timestamps and pauses near 46.8 and 121.7 fall in its ranges.
The cases: the plain scan counts the lenient value hits (as before T271); `scan --dump` reads 0 and exits 0; one
injected lone coordinate of a metre's precision in the logcat section reads 1 and exits 1; an injected pair reads a
pair hit; a short decimal in the diagnostics section still reads a value hit (full rules there); a file that is not a
dump exits 2.

    python -I tools/test_privacy_check.py
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(here)
SCRIPT = os.path.join("tools", "PrivacyCheck.java")

# Rainier's public box from areas.json, standing in for a private box; one term no fixture line contains.
LOCAL = {"areas": {"stand-in": {"bbox": [-121.86, 46.78, -121.66, 46.93]}}, "privateTerms": ["Zzyzxfixtureterm"]}

HEADER = ["dystopo-dump 1", "kind: diagnostics", "atMs: 1794293551292", "at: 2026-11-10T13:36:46Z", "version: 0.1.26-alpha",
          "versionCode: 1445", "build: debug", "sdk: 35", "android: 15", "device: sdk_gphone64_x86_64", "pid: 3979",
          "upS: 412", "samplePeriodS: 60", "sampleKeep: 1440", "logLines: 9", "logError: -"]
SAMPLES = [f"s t={1794293551166 + 60000 * i} up={17 + 60 * i} pid=3979 heapKb={70784 + 512 * i} nativeKb=31567 "
           f"pssKb=166374 threads=362 fds=242 demServed=5 osmServed=12 contourServed=4 tileKb=812 tileErrors=0 "
           f"cacheRuns=1 trims=0 fg=1 rec=idle cpuUs=15500" for i in range(3)]
STARTS = ["0.1.26-alpha cold, first frame 1.20 s, longest stall 0.40 s, finished",
          "0.1.26-alpha warm, first frame 0.46 s, longest stall 0.12 s, finished"]
DIAGNOSTICS = ["Memory: heap 69 MB of 576 MB, native 31 MB", "Tiles: 21 served, 0 errors", "Cache: 722 kB of 1 GB"]
LOGCAT = [
    "11-10 13:36:46.812  3979  4012 I DystopoDirect: render z12 tiles=16 avg=78.48ms min=1.04ms max=121.712ms",
    "11-10 13:36:46.875  3979  3991 I .jasondorjeshort.dystopo: Background concurrent mark compact GC freed 2048KB "
    "AllocSpace bytes, 12(240KB) LOS objects, 46.875% free, 7.1MB/13MB, paused 121.684ms,1.37ms total 210.778ms",
    "11-10 13:36:46.901  3979  4012 D DystopoServer: GET <tile> 200 in 46.85ms",
    "11-10 13:36:47.004  3979  3979 I Choreographer: Skipped 46 frames!  The application may be doing too much work on its main thread.",
    "11-10 13:36:47.112  3979  4020 I DystopoSampler: sample took 15.50ms cpu 121.70ms total",
    "11-10 13:36:47.250  3979  3979 D DystopoMap: idle after 46.91s, <coords> view",
    "11-10 13:36:47.388  3979  4012 W DystopoDirect: 503 for <tile> after 9.00s",
    "11-10 13:36:47.502  3979  3991 I .jasondorjeshort.dystopo: Explicit concurrent mark compact GC paused 46.802ms total 121.775ms",
    "11-10 13:36:47.640  3979  4012 I DystopoCache: trimmed 0.84 MB in 46.80ms",
]


def dump_text(logcat=LOGCAT, diagnostics=DIAGNOSTICS):
    out = list(HEADER)
    for name, lines in (("samples", SAMPLES), ("starts", STARTS), ("diagnostics", diagnostics), ("logcat", logcat)):
        out += ["", "== " + name] + lines
    return "\n".join(out) + "\n"


def java():
    home = os.environ.get("JAVA_HOME")
    return os.path.join(home, "bin", "java") if home else "java"


def scan(text, local, *extra):
    p = subprocess.run([java(), SCRIPT, "scan", "--local", local, *extra], cwd=REPO, input=text, text=True,
                       encoding="utf-8", capture_output=True, timeout=300)
    report = None
    for line in reversed(p.stdout.strip().splitlines()):
        try:
            report = json.loads(line)
            break
        except ValueError:
            pass
    return p.returncode, report, p.stderr


def hits(r):
    return r["termHits"] + r["coordinateValueHits"] + r["coordinatePairHits"]


def main():
    tmp = tempfile.mkdtemp(prefix="t271-")
    failures = []

    def check(name, cond, detail=""):
        print(("ok   " if cond else "FAIL ") + name + (f"  ({detail})" if detail else ""))
        if not cond:
            failures.append(name)

    try:
        local = os.path.join(tmp, "local.json")
        with open(local, "w", encoding="utf-8") as f:
            json.dump(LOCAL, f)
        clean = dump_text()

        code, r, err = scan(clean, local)
        check("plain scan reads the lenient value hits", r is not None and r["coordinateValueHits"] > 0 and code == 1,
              f"exit {code}, {r and r['coordinateValueHits']} value hits, {err.strip()[:200]}")
        old = r["coordinateValueHits"] if r else None

        code, r, err = scan(clean, local, "--dump")
        check("scan --dump reads 0 and exits 0", r is not None and hits(r) == 0 and code == 0 and r.get("dump") is True
              and r.get("logcatLines") == len(LOGCAT), f"exit {code}, report {r}, {err.strip()[:200]}")

        lone = LOGCAT + ["11-10 13:36:47.801  3979  4030 D DystopoTrack: snapped to 46.85312"]
        code, r, _ = scan(dump_text(lone), local, "--dump")
        check("one injected lone coordinate reads 1 and exits 1", r is not None and hits(r) == 1 and r["coordinateValueHits"] == 1
              and code == 1, f"exit {code}, report {r}")

        pair = LOGCAT + ["11-10 13:36:47.801  3979  4030 D DystopoTrack: fix 46.853 -121.760"]
        code, r, _ = scan(dump_text(pair), local, "--dump")
        check("an injected pair in logcat reads a pair hit", r is not None and r["coordinatePairHits"] == 1 and code == 1,
              f"exit {code}, report {r}")

        diag = DIAGNOSTICS + ["Last view: 46.85"]
        code, r, _ = scan(dump_text(diagnostics=diag), local, "--dump")
        check("the diagnostics section keeps the lenient rule", r is not None and r["coordinateValueHits"] == 1 and code == 1,
              f"exit {code}, report {r}")

        term = LOGCAT + ["11-10 13:36:47.801  3979  4030 I DystopoSearch: zzyzx-fixture term"]
        code, r, _ = scan(dump_text(term), local, "--dump")
        check("a private term in logcat still reads", r is not None and r["termHits"] == 1 and code == 1, f"exit {code}, report {r}")

        code, r, err = scan("not a dump\n" + "\n".join(LOGCAT) + "\n", local, "--dump")
        check("a file that is not a dump exits 2", code == 2 and r is None, f"exit {code}, {err.strip()[:200]}")

        print(f"fixture: {len(clean.splitlines())} lines, plain scan {old} value hits, --dump 0")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    if failures:
        print(f"{len(failures)} failed: {', '.join(failures)}")
        sys.exit(1)
    print("all passed")


if __name__ == "__main__":
    main()
