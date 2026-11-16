"""T289: route-diff.py over two planted sweep folders (route JSON as devtool `route` writes it, GPX, small PNGs): an
unchanged route, one whose time moved, one whose line moved at equal figures, one whose figures differ inside the
tolerances (unchanged), one only in the new set, one only in the old; fields absent on both sides and fields devtool
gained later are ignored; the list file's parsing and tools/route-sheet.txt itself; `run` skipping a missing pack by
name and failing a route that is not found; the sheet's pages written.

    python -I -m unittest discover -s tools -p test_route_diff.py

The sheet test needs Playwright with a Chromium (the bundled one) and is skipped without it.
"""
import importlib.util
import json
import os
import shutil
import struct
import tempfile
import unittest
import zlib

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("route_diff", os.path.join(here, "route-diff.py"))
rd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rd)

LINE = [(46.7864, -121.7366), (46.7900, -121.7340), (46.7950, -121.7310), (46.8024, -121.7289)]
BBOX = [-121.745, 46.780, -121.720, 46.810]


def png(path, w=60, h=80):
    raw = b"".join(b"\x00" + bytes([200, 200, 200] * w) for _ in range(h))

    def chunk(t, data):
        return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def plant(sweep, name, figures, line=LINE, extra=None, picture=True):
    """One route as the part leaves it: <name>.json (devtool route's shape), <name>.gpx and <name>.png."""
    d = os.path.join(sweep, "route-sheet")
    os.makedirs(d, exist_ok=True)
    route = {"id": "random-" + name, "name": "Route", "mode": "drive", **figures}
    report = {"pack": "out/rainier-outdoors.pmtiles", "search": {"prefer": "time"}, "route": route, **(extra or {})}
    if picture:
        report["render"] = {"zoom": 14, "bbox": BBOX, "widthPx": 60, "heightPx": 80}
        png(os.path.join(d, name + ".png"))
    with open(os.path.join(d, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(report, f)
    pts = "".join(f'<rtept lat="{la}" lon="{lo}"><ele>1000.{len(name)}</ele></rtept>' for la, lo in line)
    with open(os.path.join(d, name + ".gpx"), "w", encoding="utf-8") as f:
        f.write(f'<?xml version="1.0"?><gpx><rte><name>Route</name><desc>{name} {route["id"]}</desc>{pts}</rte></gpx>')


BASE = {"distanceM": 18069.3, "timeS": 1225, "turns": 2, "turnCostS": 11.6, "fuelIndex": 19537000, "ascentM": 30.4, "points": 4}


class RouteDiffTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="t289-")
        self.a, self.b = os.path.join(self.d, "sweep-a"), os.path.join(self.d, "sweep-b")
        plant(self.a, "same", BASE)
        plant(self.b, "same", BASE, extra={"breakdown": {"totals": {}}})  # a field devtool gained later: ignored
        plant(self.a, "slower", BASE)
        plant(self.b, "slower", {**BASE, "timeS": 1230})
        plant(self.a, "moved-line", BASE)
        plant(self.b, "moved-line", BASE, line=LINE[:2] + [(46.7951, -121.7310)] + LINE[3:])
        plant(self.a, "inside-tolerance", BASE)
        plant(self.b, "inside-tolerance", {**BASE, "distanceM": 18069.35, "timeS": 1226, "fuelIndex": 19537019},
              line=[(la + 4e-7, lo - 4e-7) for la, lo in LINE])  # rounding to six decimals keeps the hash
        plant(self.b, "only-new", BASE)
        plant(self.a, "only-old", BASE)
        hike = {k: v for k, v in BASE.items() if k not in ("turns", "turnCostS", "fuelIndex")}
        plant(self.a, "hike", hike)
        plant(self.b, "hike", hike)
        with open(os.path.join(self.b, "route-sheet", "route-sheet.json"), "w", encoding="utf-8") as f:
            json.dump({"skipped": [{"name": "elsewhere", "why": "no out/x-outdoors.pmtiles"}], "failed": [],
                       "lines": [{"name": n} for n in ("slower", "moved-line", "same", "inside-tolerance", "only-new", "hike")]}, f)

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def test_pairs_by_name_with_tolerances(self):
        r = rd.diff_main(self.a, self.b, "", sheet=False)
        self.assertEqual([(c["name"], c["why"]) for c in r["changed"]], [("slower", ["timeS"]), ("moved-line", ["line"])])
        self.assertEqual(r["count"], 2)
        self.assertEqual(sorted(r["unchanged"]), ["hike", "inside-tolerance", "same"])
        self.assertEqual(r["new"], ["only-new"])
        self.assertEqual(r["removed"], ["only-old"])
        self.assertEqual(r["skipped"], ["elsewhere"])
        slower = r["changed"][0]
        self.assertEqual(slower["differences"]["timeS"], 5)
        self.assertEqual(slower["before"]["figures"]["timeS"], 1225)
        with open(os.path.join(self.b, "route-changes.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f)["count"], 2)

    def test_compare_edges(self):
        a = {"figures": {"distanceM": 100.0, "timeS": 10}, "lineHash": "x"}
        self.assertEqual(rd.compare(a, {"figures": {"distanceM": 100.1, "timeS": 11}, "lineHash": "x"}), [])
        self.assertEqual(rd.compare(a, {"figures": {"distanceM": 100.2, "timeS": 12}, "lineHash": "x"}), ["distanceM", "timeS"])
        self.assertEqual(rd.compare(a, {"figures": {**a["figures"], "turns": 0}, "lineHash": "x"}), ["turns"])  # one side only
        self.assertEqual(rd.compare({"figures": {"fuelIndex": 1e7}}, {"figures": {"fuelIndex": 1e7 + 11}}), ["fuelIndex"])
        self.assertEqual(rd.compare({"figures": {"fuelIndex": 1e7}}, {"figures": {"fuelIndex": 1e7 + 10}}), [])

    def test_hash_reads_points_only(self):
        self.assertEqual(rd.line_hash(LINE), rd.line_hash([(la + 3e-7, lo) for la, lo in LINE]))
        self.assertNotEqual(rd.line_hash(LINE), rd.line_hash(LINE[::-1]))
        gpx = '<gpx><trk><trkseg><trkpt lon="2.5" lat="1.5"/></trkseg></trk></gpx>'
        self.assertEqual(rd.gpx_points(gpx), [(1.5, 2.5)])

    def test_list_file(self):
        with open(os.path.join(here, "route-sheet.txt"), encoding="utf-8") as f:
            lines = rd.parse_list(f.read())
        with open(os.path.join(os.path.dirname(here), "areas.json"), encoding="utf-8") as f:
            areas = json.load(f)["areas"]
        public = set(areas) | {a.replace("-", "") for a in areas}  # the pack `montblanc` is the area `mont-blanc`
        self.assertTrue(all(l["pack"] in public for l in lines), "public packs only")
        drives = [l for l in lines if l["mode"] == "drive"]
        self.assertEqual(len(drives), 18)
        self.assertEqual(sorted({l["prefer"] for l in drives}), ["fuel", "quick", "time"])
        self.assertEqual(len(lines) - len(drives), 6)
        for bad in ("a rainier walk 1,2 3,4", "a rainier hike 1,2 3,4 fuel", "a rainier drive 1,2 3", "A rainier hike 1,2 3,4"):
            with self.assertRaises(ValueError):
                rd.parse_list(bad)
        with self.assertRaises(ValueError):
            rd.parse_list("a rainier hike 1,2 3,4\na rainier hike 1,2 3,4")

    def test_run_skips_a_missing_pack_and_fails_a_route_not_found(self):
        packs = os.path.join(self.d, "packs")
        os.makedirs(packs)
        open(os.path.join(packs, "rainier-outdoors.pmtiles"), "wb").close()
        lines = rd.parse_list("gone montblanc hike 1,2 3,4\nbroken rainier hike 1,2 3,4\n")
        rep = rd.run_list(lines, os.path.join(self.d, "no-such-devtool"), packs, os.path.join(self.d, "out"), 30)
        self.assertEqual(rep["skipped"], [{"name": "gone", "why": "no out/montblanc-outdoors.pmtiles"}])
        self.assertEqual([f["name"] for f in rep["failed"]], ["broken"])
        self.assertEqual(rep["planned"], 0)

    def test_projection_matches_the_window(self):
        pic = {"zoom": 14, "bbox": BBOX, "width": 60, "height": 80}
        pts, map_h = rd.project(pic, [(BBOX[3], BBOX[0]), (BBOX[1], BBOX[2])])
        self.assertTrue(0 <= pts[0][0] < 1 and 0 <= pts[0][1] < 1, pts)
        self.assertAlmostEqual(pts[1][1], map_h, delta=1)

    def test_sheet(self):
        try:
            import playwright.sync_api  # noqa: F401
        except ImportError:
            self.skipTest("no Playwright")
        r = rd.diff_main(self.a, self.b, "", sheet=True)
        self.assertEqual(len(r["pages"]), 1)
        self.assertTrue(os.path.getsize(os.path.join(self.b, "route-changes.png")) > 0)
        with open(os.path.join(self.b, "route-changes.html"), encoding="utf-8") as f:
            page = f.read()
        self.assertLess(page.index("slower"), page.index("only-new"))  # changed first, then new
        self.assertIn("class='other'", page)


if __name__ == "__main__":
    unittest.main()
