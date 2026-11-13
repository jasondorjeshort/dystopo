"""T228: the parsing of tools/android-startup.py; T268: the stages, the budget and the seed.  Run: python -I tools/test_android_startup.py"""
import importlib.util
import os

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("android_startup", os.path.join(here, "android-startup.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

AM = """Starting: Intent { cmp=io.github.jasondorjeshort.dystopo/.app.MainActivity }
Status: ok
LaunchState: COLD
Activity: io.github.jasondorjeshort.dystopo/.app.MainActivity
TotalTime: 1086
WaitTime: 1090
Complete
"""
got = m.parse_am_start(AM)
assert got == {"amTotalMs": 1086, "amWaitMs": 1090, "launchState": "COLD"}, got
assert m.parse_am_start("") == {"amTotalMs": None, "amWaitMs": None, "launchState": None}
assert m.parse_am_start("Warning: Activity not started\nTotalTime: x\n")["amTotalMs"] is None

TRACE = {
    "state": "open",
    "launch": {"overheadMs": 6.5, "mainMs": 4.5, "lagMs": 2.0},
    "window": {
        "id": "42-1000", "kind": "cold", "kindSource": "startInfo", "firstFrameMs": 2100, "longestStallMs": 700, "reasons": [],
        "marks": [
            {"name": "PackSet.open", "atMs": 200, "ms": 300, "samples": 3, "cpuMs": 10, "states": {"disk": 3}, "frame": "x.y(Z.kt:1)"},
            {"name": "first frame", "atMs": 2100, "ms": None, "samples": 0, "cpuMs": 0, "states": {}, "frame": None},
        ],
    },
}
line = m.parse_trace(TRACE)
assert line["kind"] == "cold" and line["kindSource"] == "startInfo" and line["firstFrameMs"] == 2100 and line["longestStallMs"] == 700, line
assert [x["name"] for x in line["marks"]] == ["PackSet.open", "first frame"]
assert line["launchOverheadMs"] == 6.5, line  # T266
assert m.parse_trace({"state": "none", "window": None})["launchOverheadMs"] is None
assert "states" not in line["marks"][0], "the summary stays small"
empty = m.parse_trace(None)
assert empty["firstFrameMs"] is None and empty["marks"] == [], empty
assert m.parse_trace({"state": "none", "window": None})["state"] == "none"

assert m.median([3, None, 1, 2]) == 2
assert m.median([None]) is None
assert m.median([]) is None

launches = [
    {"amTotalMs": 1000, "firstFrameMs": 1100, "longestStallMs": 0, "launchOverheadMs": 7.0, "marks": [{"name": "a", "ms": 10}, {"name": "b", "ms": 300}, {"name": "first frame", "ms": None}]},
    {"amTotalMs": 1200, "firstFrameMs": 1300, "longestStallMs": 600, "launchOverheadMs": 5.0, "marks": [{"name": "a", "ms": 30}, {"name": "b", "ms": 100}]},
    {"amTotalMs": 1100, "firstFrameMs": None, "longestStallMs": None},
]
s = m.summary(launches, "cold")
assert s["medians"] == {"amTotalMs": 1100, "firstFrameMs": 1200.0, "fullyLoadedMs": None, "longestStallMs": 300.0, "launchOverheadMs": 6.0}, s["medians"]
assert "stageMedians" not in s, "no stages read, no stage table"
assert [r["name"] for r in s["markMedians"]] == ["b", "a"], s["markMedians"]
assert s["markMedians"][0] == {"name": "b", "medianMs": 200.0, "maxMs": 300, "launches": 2}, s["markMedians"]
assert s["mode"] == "cold" and len(s["launches"]) == 3

# With no trace (a release build, a build with it off) the system's figure stands alone.
s = m.summary([{"amTotalMs": 900}, {"amTotalMs": 1100}], "cold")
assert s["medians"]["amTotalMs"] == 1000.0 and s["medians"]["firstFrameMs"] is None and s["markMedians"] == [], s

# ---------- T268 ----------
import json
import math
import xml.etree.ElementTree as ET

assert m.parse_trace({"window": {"firstFrameMs": 3600, "fullyLoadedMs": 5200}})["fullyLoadedMs"] == 5200

LAUNCH = {"stages": [
    {"name": "prepare", "thread": "main", "ms": 40, "startMs": 100, "endMs": 140, "undeclared": []},
    {"name": "tracking", "thread": "launch-tracking", "ms": 300, "undeclared": []},
    {"name": "style", "thread": "main", "ms": None, "startMs": 900, "endMs": None},
    {"name": "assets", "thread": "main", "ms": 5, "undeclared": ["DiskReadViolation"]},
    {"ms": 3},
]}
st = m.parse_launch(LAUNCH)
assert [x["name"] for x in st] == ["prepare", "tracking", "style", "assets"], st
assert st[2] == {"name": "style", "ms": None, "thread": "main", "undeclared": [], "startMs": 900, "endMs": None} and st[3]["undeclared"] == ["DiskReadViolation"], st
assert st[0]["startMs"] == 100 and st[0]["endMs"] == 140 and st[1]["startMs"] is None, st  # T280: when each ran

# T280: the update-time reads of one launch, from GET /firstrun and the trace's header
pr = m.parse_package_reads({"head": {"packageInfoMs": "0.6", "fdTableMs": "21.4"}},
                           {"assets": {"state": "current", "versionMs": 0.1, "checkMs": 40.4, "version": "1",
                                       "world": {"state": "current", "versionMs": 0.1, "checkMs": 40.3}}})
assert pr == {"assets": {"versionMs": 0.1, "checkMs": 40.4}, "world": {"versionMs": 0.1, "checkMs": 40.3},
              "trace": {"packageInfoMs": 0.6, "fdTableMs": 21.4}}, pr
assert m.parse_package_reads(None, None) == {"assets": {}, "world": {}, "trace": {"packageInfoMs": None, "fdTableMs": None}}
assert m.parse_launch(None) == [] and m.parse_launch({"stages": None}) == []


def launch(ff, full, stages):
    return {"firstFrameMs": ff, "fullyLoadedMs": full, "stages": [{"name": n, "ms": v, "thread": "main", "undeclared": u} for n, v, u in stages]}


ls = [launch(3600, 5000, [("prepare", 40, []), ("tracking", 300, []), ("style", None, [])]),
      launch(3700, 5200, [("prepare", 60, []), ("tracking", 200, []), ("style", 900, [])]),
      launch(4600, 6100, [("prepare", 50, ["DiskReadViolation"]), ("tracking", 250, []), ("style", 700, [])])]
s = m.summary(ls, "cold")
assert s["medians"]["firstFrameMs"] == 3700 and s["medians"]["fullyLoadedMs"] == 5200, s["medians"]
sm = {r["name"]: r for r in s["stageMedians"]}
assert [r["name"] for r in s["stageMedians"]] == ["prepare", "tracking", "style"], "the list's order, not the slowest first"
assert sm["prepare"] == {"name": "prepare", "medianMs": 50, "maxMs": 60, "launches": 3, "thread": "main"}, sm
assert sm["style"]["medianMs"] == 800.0 and sm["style"]["launches"] == 2, sm
assert s["undeclared"] == [{"stage": "prepare", "kind": "DiskReadViolation", "launches": [3]}], s["undeclared"]

# The ceiling: the larger median times 1.25, up to a whole ms, and never under the median plus 16 ms.
assert m.ceiling([3700, 3600]) == 4625 and m.ceiling([3600, None]) == 4500 and m.ceiling([None]) is None
assert m.ceiling([0]) == 16 and m.ceiling([0.4]) == 17 and m.ceiling([8]) == 24 and m.ceiling([64]) == 80 and m.ceiling([68]) == 85
assert m.ceiling([8], floor=0) == 10 and m.ceiling([100], factor=1.5) == 150

# make_budget from two reports, then the check: green on its own medians.
r1 = {"medians": {"firstFrameMs": 3600, "fullyLoadedMs": 5000}, "stageMedians": [{"name": "prepare", "medianMs": 40}, {"name": "tracking", "medianMs": 300}]}
r2 = {"medians": {"firstFrameMs": 3700, "fullyLoadedMs": None}, "stageMedians": [{"name": "prepare", "medianMs": 48}, {"name": "style", "medianMs": 800}]}
b = m.make_budget([r1, r2], ["a/startup.json", "b/startup.json"], "2026-11-13")
assert b["frames"] == {"firstFrameMs": {"ceilingMs": 4625, "medianMs": [3600, 3700]}, "fullyLoadedMs": {"ceilingMs": 6250, "medianMs": [5000, None]}}, b["frames"]
assert b["stages"] == {"prepare": {"ceilingMs": 64, "medianMs": [40, 48]}, "tracking": {"ceilingMs": 375, "medianMs": [300, None]},
                       "style": {"ceilingMs": 1000, "medianMs": [None, 800]}}, b["stages"]
assert b["factor"] == 1.25 and b["floorMs"] == 16 and b["set"] == {"date": "2026-11-13", "from": ["a/startup.json", "b/startup.json"]} and b["aliases"] == {}
assert m.make_budget([r1], ["a"], "d", old={"aliases": {"old": "new"}, "rule": "R"})["aliases"] == {"old": "new"}
assert json.loads(m.format_budget(b)) == b, "the file's text reads back as the budget"
assert '\n  "prepare": {"ceilingMs": 64, "medianMs": [40, 48]},\n' in m.format_budget(b), "one line per stage"

rep_ok = {"medians": {"firstFrameMs": 3700, "fullyLoadedMs": 5200},
          "stageMedians": [{"name": "prepare", "medianMs": 50}, {"name": "tracking", "medianMs": 300}, {"name": "style", "medianMs": 800}], "undeclared": []}
c = m.check_budget(rep_ok, b)
assert not c["red"] and c["reasons"] == [], c
assert [(x["name"], x["status"]) for x in c["rows"]] == [("firstFrameMs", "ok"), ("fullyLoadedMs", "ok"), ("prepare", "ok"), ("tracking", "ok"), ("style", "ok")], c["rows"]
assert c["rows"][0] == {"name": "firstFrameMs", "kind": "frame", "medianMs": 3700, "ceilingMs": 4625, "status": "ok"}

# A ceiling lowered under its median turns it red, and only that row is over (the Done-when check).
low = json.loads(json.dumps(b))
low["stages"]["tracking"]["ceilingMs"] = 299
c = m.check_budget(rep_ok, low)
assert c["red"] and c["reasons"] == ["stage tracking: median 300 ms over its ceiling of 299 ms"], c
assert [x["name"] for x in c["rows"] if x["status"] == "over"] == ["tracking"], c["rows"]
low["frames"]["firstFrameMs"]["ceilingMs"] = 3000
assert m.check_budget(rep_ok, low)["reasons"][0] == "frame firstFrameMs: median 3700 ms over its ceiling of 3000 ms"
assert not m.check_budget(dict(rep_ok, medians={"firstFrameMs": 4625, "fullyLoadedMs": 1}), b)["red"], "at the ceiling is not over"

# An undeclared StrictMode kind is red with every ceiling met.
c = m.check_budget(dict(rep_ok, undeclared=[{"stage": "prepare", "kind": "DiskReadViolation", "launches": [3]}]), b)
assert c["red"] and c["reasons"] == ["stage prepare: StrictMode DiskReadViolation, which its declared work does not allow (launches [3])"], c
assert c["undeclared"][0]["stage"] == "prepare"

# A stage the run did not time is missing, a stage the file lacks is unbudgeted: listed, neither red.
c = m.check_budget({"medians": {"firstFrameMs": 3700}, "stageMedians": [{"name": "prepare", "medianMs": 50}, {"name": "newstage", "medianMs": 9},
                                                                       {"name": "style", "medianMs": None}]}, b)
st = {x["name"]: x["status"] for x in c["rows"]}
assert st == {"firstFrameMs": "ok", "fullyLoadedMs": "missing", "prepare": "ok", "tracking": "missing", "style": "missing", "newstage": "unbudgeted"}, st
assert not c["red"], c

# A renamed stage keeps its ceiling through aliases {old name: new name}.
ren = json.loads(json.dumps(b))
ren["aliases"] = {"tracking": "tracker"}
c = m.check_budget({"medians": {}, "stageMedians": [{"name": "tracker", "medianMs": 400}]}, ren)
row = next(x for x in c["rows"] if x["name"] == "tracker")
assert row["status"] == "over" and row["ceilingMs"] == 375 and c["red"], c
assert not any(x["status"] == "unbudgeted" for x in c["rows"]), c["rows"]

# The checked-in budget file reads; every ceiling sits over the medians it was set from and no higher than the rule gives
# (a ceiling is lowered by hand after an improvement, never raised).
with open(os.path.join(here, "launch-budget.json"), encoding="utf-8") as f:
    real = json.load(f)
assert real["factor"] == m.FACTOR and set(real["frames"]) == {"firstFrameMs", "fullyLoadedMs"}, real.get("frames")
assert len(real["stages"]) >= 20, "the stages of T266's list"
for k, v in list(real["frames"].items()) + list(real["stages"].items()):
    assert max(x for x in v["medianMs"] if x is not None) < v["ceilingMs"] <= m.ceiling(v["medianMs"], real["factor"], real["floorMs"]), (k, v)

# The seed: a fixed id per index, inside areas.json's Rainier box, a GPX the importer reads.
tid, text = m.seed_gpx(3, 50)
assert (tid, text) == m.seed_gpx(3, 50) and tid != m.seed_gpx(4, 50)[0]
with open(os.path.join(os.path.dirname(here), "areas.json"), encoding="utf-8") as f:
    w, so, e, n = json.load(f)["areas"]["rainier"]["bbox"]
assert m.RAINIER == (w, so, e, n)
ns = {"g": "http://www.topografix.com/GPX/1/1", "d": "https://jasondorjeshort.github.io/dystopo/gpx/1"}
root = ET.fromstring(text.encode("utf-8"))
assert root.find("g:trk/g:extensions/d:id", ns).text == tid
pts = root.findall("g:trk/g:trkseg/g:trkpt", ns)
assert len(pts) == 50 and all(so <= float(p.get("lat")) <= n and w <= float(p.get("lon")) <= e for p in pts)
assert len(m.seed_gpx(0, 500)[1]) < 1024 * 1024, "under the loopback server's 1 MiB body limit"

print("ok")
