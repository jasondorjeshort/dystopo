"""T259: sweep-diff.py over two synthetic sweep folders (PNGs written here with zlib, no image library): the tolerance,
the status-bar mask on device shots, the in-flight shots as timing, a size change, renumbered steps, added and removed
shots, the skipped composites, --first's order and flag, and the `changes` entry in summary.json (sweep-summary.py).
T288: --first matched against every frame of both folders: a name that matches nothing (exit 1, printed, the sheet still
written, `diff` failed in summary.json and in a steps.tsv line, cleared by a rerun with the right names), one that matches
only unchanged frames (`firstUnchanged`), one that matches a changed pair (first on the sheet), one that matches only
added frames (first among the added, exit 0).

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


def run_diff(a, b, first=None):
    args = [sys.executable, "-I", os.path.join(here, "sweep-diff.py"), a, b, "--channel", channel]
    if first is not None:
        args += ["--first", first]
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

    def test_first_outcome_alone(self):
        changed = [{"path": "ui/x/02-moved.png"}]
        out = diff.first_outcome(["moved", "same", "new", "none"], ["ui/x/01-same.png", "ui/x/02-moved.png"],
                                 ["ui/x/01-same.png", "ui/x/02-moved.png", "ui/x/03-new.png"], changed, ["ui/x/03-new.png"])
        self.assertEqual(out, {"missing": ["none"], "unchanged": ["same"], "added": ["ui/x/03-new.png"]})
        self.assertEqual(diff.first_outcome([], [], [], [], []), {"missing": [], "unchanged": [], "added": []})


if __name__ == "__main__":
    argv = [a for i, a in enumerate(sys.argv) if a != "--channel" and (i == 0 or sys.argv[i - 1] != "--channel")]
    unittest.main(argv=argv)
