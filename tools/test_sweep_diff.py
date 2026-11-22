"""T259: sweep-diff.py over two synthetic sweep folders (PNGs written here with zlib, no image library): the tolerance,
the status-bar mask on device shots, the in-flight shots as timing, a size change, renumbered steps, added and removed
shots, the skipped composites, --first's order and flag, and the `changes` entry in summary.json (sweep-summary.py).
T288: --first matched against every frame of both folders: a name that matches nothing (exit 1, printed, the sheet still
written, `diff` failed in summary.json and in a steps.tsv line, cleared by a rerun with the right names), one that matches
only unchanged frames (`firstUnchanged`), one that matches a changed pair (first on the sheet), one that matches only
added frames (first among the added, exit 0).
T295: the place sidecars of device frames: a planted `outside` (left out, never on the sheet, nothing placed printed), a
planted `unknown` and a side with no sidecar (kept and listed; --leave-unknown leaves them out), a --leave-out name, a
clean pair (both sides public: kept, not listed), the lists kept by a summary rerun, the refusal of a sheet with an
outside frame on it, and android-check.py's area_of and write_place (a name, never a coordinate).

    python -I tools/test_sweep_diff.py [--channel chrome]
    python -I -m unittest discover -s tools -p test_sweep_diff.py

Needs Playwright with a Chromium (the bundled one by default, as the sheet does with --channel '').
"""
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import zlib

here = os.path.dirname(os.path.abspath(__file__))
channel = sys.argv[sys.argv.index("--channel") + 1] if "--channel" in sys.argv else ""


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(here, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


diff = load("sweep_diff", "sweep-diff.py")
summary = load("sweep_summary", "sweep-summary.py")
acheck = load("android_check", "android-check.py")  # T295: area_of, write_place


def png(path, w, h, rects=()):
    """A w x h RGB PNG, pale grey, with (x, y, rw, rh, (r, g, b)) rectangles painted on it."""
    px = [[(200, 200, 200)] * w for _ in range(h)]
    for x0, y0, rw, rh, c in rects:
        for y in range(y0, min(h, y0 + rh)):
            for x in range(x0, min(w, x0 + rw)):
                px[y][x] = c
    raw = b"".join(b"\x00" + bytes(v for p in row for v in p) for row in px)

    def chunk(t, data):
        return struct.pack(">I", len(data)) + t + data + struct.pack(">I", zlib.crc32(t + data) & 0xFFFFFFFF)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


BLACK, RED = (20, 20, 20), (220, 30, 30)
W, H = 108, 240  # a phone's shape at a tenth; compared at full size (--width 270 is wider)
BUTTON = (10, 100, 80, 20, BLACK)


def make_sweeps(d):
    """The two synthetic sweep folders (a, b) under d, b with steps.tsv and its summary."""
    a, b = os.path.join(d, "sweep-a"), os.path.join(d, "sweep-b")

    def both(rel, ra=(), rb=(), size_b=None):
        png(os.path.join(a, rel), W, H, ra)
        png(os.path.join(b, rel), *(size_b or (W, H)), rb)

    both("ui/features/01-same.png", [BUTTON], [BUTTON])
    both("ui/features/02-speck.png", [BUTTON], [BUTTON, (50, 50, 1, 1, RED)])          # one pixel: timing-sized
    both("ui/features/03-moved.png", [BUTTON], [(10, 140, 80, 20, BLACK)])             # a real change
    both("ui/features/04-clock.png", [(5, 1, 20, 4, BLACK)], [(30, 1, 20, 4, BLACK)])  # inside the status bar
    both("tour/clock-z9.png", [(5, 1, 20, 4, BLACK)], [(30, 1, 20, 4, BLACK)])         # not a device shot: counted
    both("first-run/05-map-first.png", [BUTTON], [(10, 140, 80, 20, BLACK)])           # in flight: timing
    both("release/release-1.png", [BUTTON], [BUTTON], size_b=(H, W))                   # landscape now
    png(os.path.join(a, "ui/upgrade/03-routes.png"), W, H, [BUTTON])                   # renumbered, then changed
    png(os.path.join(b, "ui/upgrade/02-routes.png"), W, H, [(10, 180, 80, 20, BLACK)])
    png(os.path.join(a, "ui/upgrade/09-gone.png"), W, H)
    png(os.path.join(b, "ui/upgrade/08-new-screen.png"), W, H, [BUTTON])
    for skip in ("sheet.png", "device/tour1-sheet.png", "tour/contact-sheet.png", "first-run/server-out/sprite.png"):
        png(os.path.join(a, skip), W, H)
        png(os.path.join(b, skip), W, H, [BUTTON])
    with open(os.path.join(b, "steps.tsv"), "w", encoding="utf-8") as f:
        f.write("features\t0\t12.5\t\n")
    # The summary first, as sweep.ps1 runs it; then the diff adds `changes` to it.
    r = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), b, "--no-sheet"], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    return a, b


def run_diff(a, b, first=None, extra=()):
    args = [sys.executable, "-I", os.path.join(here, "sweep-diff.py"), a, b, "--channel", channel]
    if first is not None:
        args += ["--first", first]
    args += list(extra)
    return subprocess.run(args, capture_output=True, text=True)


def read(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


class SweepDiff(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="t259-diff-")
        self.a, self.b = make_sweeps(self.d)

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def changes(self):
        return read(os.path.join(self.b, "changes.json"))

    def summary(self):
        return read(os.path.join(self.b, "summary.json"))

    def test_pairs_tolerance_and_summary(self):
        a, b = self.a, self.b
        self.assertEqual(diff.stem("ui/upgrade/03-routes.png"), "ui/upgrade/routes.png")
        self.assertTrue(diff.in_flight("first-run/05-map-first.png") and not diff.in_flight("first-run/05-map.png"))
        self.assertEqual(diff.matches_first("ui/features/05-route-card.png", ["Route card", "settings"]), ["Route card"])
        names = diff.shots(b)
        self.assertFalse({"sheet.png", "device/tour1-sheet.png", "tour/contact-sheet.png"} & set(names))
        self.assertFalse(any("server-out" in n for n in names), names)

        r = run_diff(a, b, "routes")
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        rep = self.changes()
        changed = [c["path"] for c in rep["changed"]]
        # --first's screen first, flagged; then the folder's order.
        self.assertEqual(changed, ["ui/upgrade/02-routes.png", "release/release-1.png", "tour/clock-z9.png", "ui/features/03-moved.png"])
        self.assertEqual(rep["changed"][0]["first"], ["routes"])
        self.assertEqual(rep["changed"][0]["was"], "ui/upgrade/03-routes.png")
        self.assertEqual((rep["count"], rep["paired"]), (4, 8))
        size = next(c for c in rep["changed"] if c["path"] == "release/release-1.png")
        self.assertTrue(size["sizeChanged"] and size["before"] == [W, H] and size["after"] == [H, W])
        self.assertEqual(rep["fractions"]["ui/features/01-same.png"], 0)
        self.assertLessEqual(rep["fractions"]["ui/features/02-speck.png"], rep["thresholds"]["frac"])  # blurred away
        self.assertEqual(rep["fractions"]["ui/features/04-clock.png"], 0, "the status bar of a device shot is masked")
        self.assertGreater(rep["fractions"]["tour/clock-z9.png"], rep["thresholds"]["frac"])
        self.assertEqual([t["path"] for t in rep["timing"]], ["first-run/05-map-first.png"])
        self.assertEqual((rep["added"], rep["removed"]), (["ui/upgrade/08-new-screen.png"], ["ui/upgrade/09-gone.png"]))
        self.assertEqual(rep["renumbered"], [{"was": "ui/upgrade/03-routes.png", "now": "ui/upgrade/02-routes.png"}])
        self.assertEqual((rep["firstMissing"], rep["firstUnchanged"], rep["firstAdded"]), ([], [], []))
        sheet = os.path.join(b, "changes.png")
        w, h = diff.png_size(sheet)
        self.assertTrue(w == 1600 and h and rep["sheet"]["height"] == h and not rep["sheet"]["cut"], rep["sheet"])

        s = self.summary()
        ch = s["changes"]
        self.assertEqual((ch["count"], ch["paired"], ch["timing"], ch["added"], ch["removed"]), (4, 8, 1, 1, 1))
        self.assertEqual((ch["prev"], ch["first"]), ("sweep-a", ["ui/upgrade/02-routes.png"]))
        self.assertTrue(ch["png"].endswith("changes.png") and ch["json"].endswith("changes.json"))
        self.assertTrue(s["ok"] and s["parts"]["diff"]["ok"], s)
        # A rerun of the summary keeps the entry it reads (changes.json), T289: the three --first lists with it.
        subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), b, "--no-sheet"], capture_output=True, text=True)
        kept = self.summary()["changes"]
        self.assertEqual(kept, ch)
        self.assertIsNone(summary.changes(a))

        # Two identical folders: nothing changed, and a sheet left from an earlier run is removed.
        r = run_diff(b, b)
        self.assertEqual(r.returncode, 0, r.stderr)
        rep = self.changes()
        self.assertTrue(rep["count"] == 0 and not rep["added"] and not rep["removed"] and not os.path.exists(sheet), rep)

        # A folder that is not there: exit 2 with the reason.
        r = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-diff.py"), os.path.join(self.d, "missing"), b],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        self.assertIn("not a folder", r.stdout)

    def test_first_name_matching_nothing_turns_the_sweep_red_until_corrected(self):
        # sweep.ps1 appends the diff's line after a run; a rerun of the diff alone rewrites it.
        with open(os.path.join(self.b, "steps.tsv"), "a", encoding="utf-8") as f:
            f.write("diff\t0\t3.0\t\n")
        r = run_diff(self.a, self.b, "routes,nosuchscreen")
        self.assertEqual(r.returncode, 1, (r.stdout, r.stderr))
        self.assertIn("nosuchscreen", r.stderr)
        rep = self.changes()
        self.assertEqual(rep["firstMissing"], ["nosuchscreen"])
        self.assertEqual(rep["changed"][0]["path"], "ui/upgrade/02-routes.png")  # the name that matched still leads
        self.assertTrue(os.path.exists(os.path.join(self.b, "changes.png")), "the sheet is written before the exit")
        s = self.summary()
        self.assertFalse(s["ok"])
        self.assertIn("diff", s["failed"])
        self.assertEqual(s["changes"]["firstMissing"], ["nosuchscreen"])
        self.assertFalse(s["parts"]["diff"]["ok"])
        self.assertIn("nosuchscreen", s["parts"]["diff"]["note"])
        with open(os.path.join(self.b, "steps.tsv"), encoding="utf-8") as f:
            lines = [l.split("\t") for l in f.read().splitlines()]
        self.assertEqual([l[:2] for l in lines if l[0] == "diff"], [["diff", "1"]])
        # The summary written again from steps.tsv keeps the red.
        subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), self.b, "--no-sheet"], capture_output=True, text=True)
        self.assertIn("diff", self.summary()["failed"])

        # Corrected: green again in both files.
        r = run_diff(self.a, self.b, "routes")
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        s = self.summary()
        self.assertTrue(s["ok"], s["failed"])
        self.assertEqual(s["changes"]["firstMissing"], [])
        self.assertTrue(s["parts"]["diff"]["ok"])
        with open(os.path.join(self.b, "steps.tsv"), encoding="utf-8") as f:
            self.assertEqual([l.split("\t")[1] for l in f.read().splitlines() if l.startswith("diff\t")], ["0"])

    def test_first_name_matching_only_unchanged_frames(self):
        r = run_diff(self.a, self.b, "same,gone,map-first")
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        rep = self.changes()
        # A pair alike, a removed shot, a timing pair: matched, nothing to show.
        self.assertEqual(rep["firstUnchanged"], ["same", "gone", "map-first"])
        self.assertEqual(rep["firstMissing"], [])
        self.assertFalse(any(c.get("first") for c in rep["changed"]))
        self.assertTrue(self.summary()["ok"])

    def test_first_name_matching_only_added_frames(self):
        png(os.path.join(self.b, "ui/aaa/01-alpha.png"), W, H, [BUTTON])  # sorts before the other new shot
        r = run_diff(self.a, self.b)
        self.assertEqual(self.changes()["added"], ["ui/aaa/01-alpha.png", "ui/upgrade/08-new-screen.png"])
        r = run_diff(self.a, self.b, "new-screen")
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        rep = self.changes()
        self.assertEqual(rep["added"], ["ui/upgrade/08-new-screen.png", "ui/aaa/01-alpha.png"])
        self.assertEqual(rep["firstAdded"], ["ui/upgrade/08-new-screen.png"])
        self.assertEqual((rep["firstMissing"], rep["firstUnchanged"]), ([], []))
        s = self.summary()
        self.assertTrue(s["ok"])
        self.assertEqual(s["changes"]["firstAdded"], ["ui/upgrade/08-new-screen.png"])

    def test_route_sheet_pictures_are_left_out(self):
        # T289: the route sheet's pictures and its own sheet, in the new sweep only, have their own comparison.
        png(os.path.join(self.b, "route-sheet/x.png"), W, H, [BUTTON])
        png(os.path.join(self.b, "route-changes.png"), W, H, [BUTTON])
        png(os.path.join(self.b, "route-changes-2.png"), W, H, [BUTTON])
        self.assertFalse([n for n in diff.shots(self.b) if "route-sheet" in n or "route-changes" in n])
        r = run_diff(self.a, self.b, "route")
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        rep = self.changes()
        self.assertEqual(rep["added"], ["ui/upgrade/08-new-screen.png"])
        self.assertEqual(rep["firstAdded"], [])
        r = run_diff(self.a, self.b, "route-sheet")
        self.assertEqual(r.returncode, 1, (r.stdout, r.stderr))
        self.assertEqual(self.changes()["firstMissing"], ["route-sheet"])

    def place(self, folder, rel, area, basis="camera"):
        with open(os.path.join(folder, os.path.splitext(rel)[0] + ".place.json"), "w", encoding="utf-8") as f:
            json.dump({"area": area, "basis": basis}, f)

    def plant_places(self):
        """Every device frame of both sweeps public, then: 03-moved's after outside (a changed pair), 08-new-screen
        outside (an added shot), 01-same's before unknown in its sidecar, 02-speck's before with no sidecar."""
        for folder in (self.a, self.b):
            for rel in diff.shots(folder):
                if diff.is_device(rel):
                    self.place(folder, rel, "rainier", "fix" if rel.startswith("first-run/") else "camera")
        self.place(self.b, "ui/features/03-moved.png", "outside")
        self.place(self.b, "ui/upgrade/08-new-screen.png", "outside", "fix")
        self.place(self.a, "ui/features/01-same.png", "unknown", "none")
        os.remove(os.path.join(self.a, "ui/features/02-speck.place.json"))

    def test_places_outside_unknown_and_leave_out(self):
        self.plant_places()
        r = run_diff(self.a, self.b, "routes")
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        self.assertIn("left out: a side outside the public areas", r.stderr)
        self.assertNotIn("rainier", r.stderr)
        rep = self.changes()
        left = {e["path"]: e for e in rep["leftOut"]}
        self.assertEqual(set(left), {"ui/features/03-moved.png", "ui/upgrade/08-new-screen.png"})
        self.assertEqual((left["ui/features/03-moved.png"]["why"], left["ui/features/03-moved.png"]["sides"]), ("outside", ["after"]))
        self.assertEqual(left["ui/upgrade/08-new-screen.png"]["why"], "outside")
        changed = [c["path"] for c in rep["changed"]]
        self.assertEqual(changed, ["ui/upgrade/02-routes.png", "release/release-1.png", "tour/clock-z9.png"])  # 03-moved gone
        self.assertEqual(rep["added"], [])
        self.assertNotIn("ui/features/03-moved.png", rep["fractions"])
        # The planted unknown and the missing sidecar: kept (compared, in fractions), listed with their side.
        unknown = {e["path"]: e["sides"] for e in rep["unknown"]}
        self.assertEqual(unknown, {"ui/features/01-same.png": ["before"], "ui/features/02-speck.png": ["before"]})
        self.assertIn("ui/features/01-same.png", rep["fractions"])
        # The clean pairs (both sides public) are neither left out nor listed.
        self.assertNotIn("ui/upgrade/02-routes.png", left)
        self.assertNotIn("ui/upgrade/02-routes.png", unknown)
        self.assertEqual(rep["places"]["areas"], {"outside": 2, "rainier": 6})
        self.assertEqual(rep["places"]["basis"], {"camera": 6, "fix": 2})
        self.assertTrue(os.path.exists(os.path.join(self.b, "changes.png")))
        text = json.dumps(rep)
        self.assertNotRegex(text, r"-?\d+\.\d{3,}\s*,\s*-?\d+\.\d{3,}", "no coordinate anywhere in changes.json")
        # summary.json keeps both lists, and so does a rerun of the summary (T292's rule for changes.json's lists).
        ch = self.summary()["changes"]
        self.assertEqual((len(ch["leftOut"]), len(ch["unknown"])), (2, 2))
        subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), self.b, "--no-sheet"], capture_output=True, text=True)
        kept = self.summary()["changes"]
        self.assertEqual((kept["leftOut"], kept["unknown"]), (ch["leftOut"], ch["unknown"]))

        # --leave-unknown and a --leave-out name: exactly those left out besides the outside ones.
        r = run_diff(self.a, self.b, extra=["--leave-unknown", "--leave-out", "clock,nosuchframe"])
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        rep = self.changes()
        why = {e["path"]: e["why"] for e in rep["leftOut"]}
        self.assertEqual(why, {"ui/features/03-moved.png": "outside", "ui/upgrade/08-new-screen.png": "outside",
                               "ui/features/01-same.png": "unknown", "ui/features/02-speck.png": "unknown",
                               "ui/features/04-clock.png": "named", "tour/clock-z9.png": "named"})
        self.assertEqual(rep["unknown"], [])
        self.assertEqual(rep["leaveOutMissing"], ["nosuchframe"])
        self.assertEqual([c["path"] for c in rep["changed"]], ["release/release-1.png", "ui/upgrade/02-routes.png"])
        self.assertIn("2 left out: named by --leave-out", r.stderr)

    def test_old_sweep_without_sidecars_reads_unknown_and_is_kept(self):
        r = run_diff(self.a, self.b)
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        rep = self.changes()
        self.assertEqual(rep["leftOut"], [])
        paths = {e["path"] for e in rep["unknown"]}
        self.assertIn("ui/features/03-moved.png", paths)
        self.assertNotIn("tour/clock-z9.png", paths, "a render is not a device frame")
        self.assertEqual(rep["count"], 4)
        self.assertEqual(rep["places"]["basis"], {"none": 8})

    def test_a_sheet_with_an_outside_frame_is_refused(self):
        # The rules leave such a frame out; with them bypassed, the last check before the sheet refuses it.
        self.plant_places()
        sheet = os.path.join(self.b, "changes.png")
        png(sheet, W, H)
        keep_all = lambda prev, this, p, a, r, *rest: ((p, a, r), [], [], [])
        orig = diff.place_filter
        diff.place_filter = keep_all
        try:
            with self.assertRaises(diff.OutsideOnSheet) as cm:
                diff.run(self.a, self.b, [], 270, 40, 0.004, channel)
        finally:
            diff.place_filter = orig
        self.assertIn("outside the public areas", str(cm.exception))
        self.assertFalse(os.path.exists(sheet), "no changes.png, not even an older one")

    def test_area_names_never_coordinates(self):
        self.assertEqual(acheck.area_of(46.7864, -121.7366), "rainier")
        self.assertEqual(acheck.area_of(45.89, 6.80), "mont-blanc")
        self.assertEqual(acheck.area_of(-16.8, 179.95), "taveuni")  # its bbox crosses the antimeridian
        self.assertEqual(acheck.area_of(0.0, -150.0), "outside")
        self.assertEqual(acheck.area_of(0.0, -150.0, 3), "pacific")  # an overview's frame holds a public area
        self.assertEqual(acheck.area_of(0.0, -150.0, 7), "outside")  # past OVERVIEW_ZOOM the centre decides
        self.assertEqual(acheck.area_of(46.97, -121.90, 13), "rainier-park")  # the tour's Carbon River view
        self.assertEqual(acheck.area_of(None, 1.0), "unknown")
        frame = os.path.join(self.d, "f/01-map.png")
        png(frame, W, H)
        acheck.write_place(frame, acheck.area_of(0.0, -150.0), "camera")
        acheck.write_place(os.path.join(self.d, "f/02-map.png"), "46.78,-121.73", "fix")  # not a name: written unknown
        with open(os.path.join(self.d, "f/01-map.place.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f), {"area": "outside", "basis": "camera"})
        with open(os.path.join(self.d, "f/02-map.place.json"), encoding="utf-8") as f:
            self.assertEqual(json.load(f), {"area": "unknown", "basis": "fix"})
        self.assertEqual(diff.read_place(self.d, "f/01-map.png"), ("outside", "camera"))
        self.assertEqual(diff.read_place(self.d, "f/03-none.png"), ("unknown", None))

    def test_first_outcome_alone(self):
        changed = [{"path": "ui/x/02-moved.png"}]
        out = diff.first_outcome(["moved", "same", "new", "none"], ["ui/x/01-same.png", "ui/x/02-moved.png"],
                                 ["ui/x/01-same.png", "ui/x/02-moved.png", "ui/x/03-new.png"], changed, ["ui/x/03-new.png"])
        self.assertEqual(out, {"missing": ["none"], "unchanged": ["same"], "added": ["ui/x/03-new.png"]})
        self.assertEqual(diff.first_outcome([], [], [], [], []), {"missing": [], "unchanged": [], "added": []})

    # T335: the diff step cannot be silent. A part that crashed leaves a whole folder of frames missing from this sweep
    # (the 2026-11-19 case: 44 a11y frames); the run still writes changes.json, lists them removed, and exits 0.
    def test_removed_frames_write_changes_json(self):
        for k in range(5):
            png(os.path.join(self.a, f"a11y/0{k}-screen-{k}.png"), W, H, [BUTTON])
        r = run_diff(self.a, self.b, "routes")
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        rep = self.changes()
        self.assertEqual(sorted(rep["removed"]), ["a11y/00-screen-0.png", "a11y/01-screen-1.png", "a11y/02-screen-2.png",
                                                  "a11y/03-screen-3.png", "a11y/04-screen-4.png", "ui/upgrade/09-gone.png"])
        self.assertTrue(self.summary()["parts"]["diff"]["ok"])
        # Only removed frames (nothing paired in this sweep at all): still a changes.json, still 0.
        empty = os.path.join(self.d, "sweep-empty")
        os.makedirs(empty)
        with open(os.path.join(empty, "steps.tsv"), "w", encoding="utf-8") as f:
            f.write("a11y\t1\t61.1\t\n")
        r = run_diff(self.a, empty)
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        rep = read(os.path.join(empty, "changes.json"))
        self.assertEqual((rep["paired"], rep["count"]), (0, 0))
        self.assertIn("a11y/00-screen-0.png", rep["removed"])

    def test_a_run_that_writes_no_changes_json_exits_2_and_says_so(self):
        # In-process, with the comparison stood in for and the write made to do nothing: the guard turns exit 0 into 2.
        report = {"firstMissing": [], "leftOut": [], "unknown": [], "leaveOutMissing": [], "paired": 0, "count": 0,
                  "added": [], "removed": [], "firstUnchanged": []}
        saved = (diff.run, diff.write_changes, sys.argv)
        diff.run = lambda *a, **k: report
        diff.write_changes = lambda this, rep: None
        sys.argv = ["sweep-diff.py", self.a, self.b]
        try:
            self.assertEqual(diff.main(), 2)
        finally:
            diff.run, diff.write_changes, sys.argv = saved
        self.assertFalse(os.path.exists(os.path.join(self.b, "changes.json")))
        s = self.summary()
        self.assertFalse(s["ok"])
        self.assertIn("diff", s["failed"])
        self.assertEqual(s["parts"]["diff"]["note"], "sweep-diff.py wrote no changes.json")
        # A changes.json left by an earlier run is not this run's.
        with open(os.path.join(self.b, "changes.json"), "w", encoding="utf-8") as f:
            f.write("{}")
        old = time.time() - 600
        os.utime(os.path.join(self.b, "changes.json"), (old, old))
        self.assertFalse(diff.wrote_changes(self.b, time.time()))
        self.assertTrue(diff.wrote_changes(self.b, old))

    def test_previous_not_a_folder_is_named_in_the_summary(self):
        r = run_diff(os.path.join(self.d, "missing"), self.b)
        self.assertEqual(r.returncode, 2)
        s = self.summary()
        self.assertIn("diff", s["failed"])
        self.assertIn("not a folder", s["parts"]["diff"]["note"])

    def test_record_failure_for_sweep_ps1(self):
        # sweep.ps1's own record when a call left no fresh changes.json: summary.json and the steps.tsv line, when there.
        with open(os.path.join(self.b, "steps.tsv"), "a", encoding="utf-8") as f:
            f.write("diff\tstopped\t0\tstarted and did not finish\n")
        note = "no changes.json written, retried once: sweep-diff.py exit 0 and no changes.json"
        r = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-diff.py"), "--record-failure", self.b,
                            "--code", "0", "--seconds", "4.2", "--note", note], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        s = self.summary()
        self.assertFalse(s["ok"])
        self.assertEqual(s["parts"]["diff"], {"ok": False, "seconds": 4.2, "exit": "2", "note": note})
        with open(os.path.join(self.b, "steps.tsv"), encoding="utf-8") as f:
            self.assertEqual([l for l in f.read().splitlines() if l.startswith("diff\t")], [f"diff\t2\t4.2\t{note}"])
        # The summary written again from steps.tsv keeps the red; a passing run clears it.
        subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), self.b, "--no-sheet"], capture_output=True, text=True)
        self.assertIn("diff", self.summary()["failed"])
        r = run_diff(self.a, self.b)
        self.assertEqual(r.returncode, 0, (r.stdout, r.stderr))
        self.assertTrue(self.summary()["ok"])


# T319: --check-first, a screen name against the frames a sweep writes: a synthetic tree of the two tools (the static scan)
# and a synthetic sweep folder (its frames), then the real tree's tools.
UI_SRC = '''
class Run:
    def step(self, name, fn, shot=True):
        self.dev.screencap(name + ("" if ok else "-failed"))


def helper(dev, name="dflt"):
    return dev.screencap(f"{name}-shot")


def saved(dev, step):
    dev.save(f"{step}-{name}", None)  # no literal of its own: left out


def other_step(dev):
    dev.screencap("other-frame")
    helper(dev, "passed")


def part_alpha(args):
    dev = None
    run = Run(dev)

    def first():
        dev.screencap("alpha-one")

    def card(name="alpha-card"):
        def show():
            return dev.screencap(name)
        return show

    for name, fn in (("first", first), ("card", card()), ("big", card("alpha-card-big")), ("other", lambda: other_step(dev))):
        run.step(name, fn)
    run.step("alive", lambda: helper(dev))
    firstrun.settle_map(dev, "alpha-map", 0.002, 90)


def part_debugonly(args):
    run = Run(None)

    def slow():
        dev.screencap("debug-slow")
    run.step("slow", slow)
'''

A11Y_SRC = '''
def scr(c):
    pass


SCREENS = [
    ("map", scr, None, "map"),
] + [(f"settings-{g}", scr, None, "screen") for g in ("data", "about")] + [
    ("horizon", scr, scr, "screen"),
]


def main():
    ap.add_argument("--font-scales", default="1.0,2.0")
'''


class CheckFirst(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp(prefix="t319-check-first-")
        self.tools = os.path.join(self.d, "tools")
        os.makedirs(self.tools)
        for name, src in (("android-ui.py", UI_SRC), ("android-a11y.py", A11Y_SRC)):
            with open(os.path.join(self.tools, name), "w", encoding="utf-8") as f:
                f.write(src)
        self.sweep = os.path.join(self.d, "sweep")
        for rel in ("ui/alpha/01-alpha-one.png", "ui/alpha/02-alpha-card.png", "a11y/03-horizon-2.0.png",
                    "device/tour1-0.png", "sheet.png"):
            png(os.path.join(self.sweep, rel), 4, 4)

    def tearDown(self):
        shutil.rmtree(self.d, ignore_errors=True)

    def test_static_scan_of_parts_steps_and_frames(self):
        got = {(w["part"], w["step"], w["frame"]) for w in diff.static_writers(self.tools)}
        for want in [("alpha", "first", "alpha-one"), ("alpha", "card", "alpha-card"), ("alpha", "big", "alpha-card-big"),
                     ("alpha", "other", "other-frame"), ("alpha", "other", "passed-shot"), ("alpha", "alive", "dflt-shot"),
                     ("alpha", "alive", "alive"), ("alpha", None, "alpha-map"), ("debugonly", "slow", "debug-slow"),
                     ("a11y", "map", "map-1.0"), ("a11y", "settings-data", "settings-data-2.0"), ("a11y", "horizon", "horizon-2.0")]:
            self.assertIn(want, got)
        self.assertFalse([w for w in got if w[2].replace("*", "").replace("-", "") == ""], "a pattern with no literal is left out")
        self.assertEqual(len([w for w in got if w[0] == "a11y"]), 8)  # four screens at two scales

    def test_names_against_sweep_and_tools(self):
        r = diff.check_first(["alpha-card", "horizon", "alpha-card-big", "debug-slow", "nowhere", "tour1"], self.sweep,
                             self.tools, parts=["alpha", "a11y", "device-tour"])
        by = {n["name"]: n for n in r["names"]}
        self.assertEqual(r["missing"], ["debug-slow", "nowhere"])
        self.assertEqual((r["missingCount"], r["ok"]), (2, False))
        self.assertEqual((by["alpha-card"]["part"], by["alpha-card"]["step"], by["alpha-card"]["inSweep"]), ("alpha", "card", True))
        self.assertIn("ui/alpha/02-alpha-card.png", by["alpha-card"]["sweepFrames"])
        self.assertEqual((by["horizon"]["part"], by["horizon"]["step"]), ("a11y", "horizon"))
        # A frame new since the sweep (on a task's branch): the static scan alone, in a part the sweep runs.
        self.assertEqual((by["alpha-card-big"]["sweepFrames"], by["alpha-card-big"]["step"], by["alpha-card-big"]["inSweep"]), ([], "big", True))
        # Written only by a part the sweep does not run: named, and missing.
        self.assertEqual((by["debug-slow"]["part"], by["debug-slow"]["step"], by["debug-slow"]["inSweep"]), ("debugonly", "slow", False))
        self.assertEqual((by["nowhere"]["part"], by["nowhere"]["writers"]), (None, []))
        self.assertEqual((by["tour1"]["part"], by["tour1"]["inSweep"]), ("device-tour", True))
        # Without a sweep folder, the static scan alone.
        self.assertEqual(diff.check_first(["alpha-one"], None, self.tools, parts=["alpha"])["missingCount"], 0)

    def test_parts_from_the_script_text_without_powershell(self):
        with open(os.path.join(self.tools, "sweep.ps1"), "w", encoding="utf-8") as f:
            f.write("$runOrder = @('check', 'a11y', 'features', 'matrix', 'phone-tour')\n")

        def no_shell(*a, **kw):
            raise OSError("no powershell")
        self.assertEqual(diff.sweep_parts(self.tools, run=no_shell), (["check", "a11y", "features"], "sweep.ps1 $runOrder (text)"))

    def test_the_real_tools(self):
        have = {(w["part"], w["step"], w["frame"]) for w in diff.static_writers(here)}
        self.assertIn(("flights", "slow", "flights-slow"), have)  # the debug flights part, which the sweep does not run
        self.assertIn(("features", "horizon", "horizon-point"), have)
        self.assertIn(("features", "flights", "flights-refresh-help"), have)
        self.assertIn(("a11y", "horizon-flights", "horizon-flights-2.0"), have)
        parts, _src = diff.sweep_parts(here)
        self.assertIn("features", parts)
        self.assertNotIn("flights", parts)
        r = diff.check_first(["flights-slow", "horizon-point"], None, here, parts=parts)
        self.assertEqual(r["missing"], ["flights-slow"])

    def test_command_line(self):
        p = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-diff.py"), "--check-first", "flights-slow",
                            "horizon-point,flights-row", "--sweep", self.sweep], capture_output=True, text=True)
        self.assertEqual(p.returncode, 1, p.stderr[-400:])
        r = json.loads(p.stdout)
        self.assertEqual(([n["name"] for n in r["names"]], r["missingCount"]), (["flights-slow", "horizon-point", "flights-row"], 1))
        self.assertIn("flights-slow", p.stderr)
        p = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-diff.py"), "--check-first", "horizon-point"],
                           capture_output=True, text=True)
        self.assertEqual((p.returncode, json.loads(p.stdout)["missingCount"]), (0, 0))


if __name__ == "__main__":
    argv = [a for i, a in enumerate(sys.argv) if a != "--channel" and (i == 0 or sys.argv[i - 1] != "--channel")]
    unittest.main(argv=argv)
