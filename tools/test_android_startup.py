"""T228: the parsing of tools/android-startup.py.  Run: python -I tools/test_android_startup.py"""
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
assert "states" not in line["marks"][0], "the summary stays small"
empty = m.parse_trace(None)
assert empty["firstFrameMs"] is None and empty["marks"] == [], empty
assert m.parse_trace({"state": "none", "window": None})["state"] == "none"

assert m.median([3, None, 1, 2]) == 2
assert m.median([None]) is None
assert m.median([]) is None

launches = [
    {"amTotalMs": 1000, "firstFrameMs": 1100, "longestStallMs": 0, "marks": [{"name": "a", "ms": 10}, {"name": "b", "ms": 300}, {"name": "first frame", "ms": None}]},
    {"amTotalMs": 1200, "firstFrameMs": 1300, "longestStallMs": 600, "marks": [{"name": "a", "ms": 30}, {"name": "b", "ms": 100}]},
    {"amTotalMs": 1100, "firstFrameMs": None, "longestStallMs": None},
]
s = m.summary(launches, "cold")
assert s["medians"] == {"amTotalMs": 1100, "firstFrameMs": 1200.0, "longestStallMs": 300.0}, s["medians"]
assert [r["name"] for r in s["markMedians"]] == ["b", "a"], s["markMedians"]
assert s["markMedians"][0] == {"name": "b", "medianMs": 200.0, "maxMs": 300, "launches": 2}, s["markMedians"]
assert s["mode"] == "cold" and len(s["launches"]) == 3

# With no trace (a release build, a build with it off) the system's figure stands alone.
s = m.summary([{"amTotalMs": 900}, {"amTotalMs": 1100}], "cold")
assert s["medians"]["amTotalMs"] == 1000.0 and s["medians"]["firstFrameMs"] is None and s["markMedians"] == [], s

print("ok")
