"""T259: sweep-diff.py over two synthetic sweep folders (PNGs written here with zlib, no image library): the tolerance,
the status-bar mask on device shots, the in-flight shots as timing, a size change, renumbered steps, added and removed
shots, the skipped composites, --first's order and flag, and the `changes` entry in summary.json (sweep-summary.py).

    python -I tools/test_sweep_diff.py [--channel chrome]

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
d = tempfile.mkdtemp(prefix="t259-diff-")
try:
    a, b = os.path.join(d, "sweep-a"), os.path.join(d, "sweep-b")

    def both(rel, ra=(), rb=(), size_b=None):
        png(os.path.join(a, rel), W, H, ra)
        png(os.path.join(b, rel), *(size_b or (W, H)), rb)

    button = (10, 100, 80, 20, BLACK)
    both("ui/features/01-same.png", [button], [button])
    both("ui/features/02-speck.png", [button], [button, (50, 50, 1, 1, RED)])          # one pixel: timing-sized
    both("ui/features/03-moved.png", [button], [(10, 140, 80, 20, BLACK)])             # a real change
    both("ui/features/04-clock.png", [(5, 1, 20, 4, BLACK)], [(30, 1, 20, 4, BLACK)])  # inside the status bar
    both("tour/clock-z9.png", [(5, 1, 20, 4, BLACK)], [(30, 1, 20, 4, BLACK)])         # not a device shot: counted
    both("first-run/05-map-first.png", [button], [(10, 140, 80, 20, BLACK)])           # in flight: timing
    both("release/release-1.png", [button], [button], size_b=(H, W))                   # landscape now
    png(os.path.join(a, "ui/upgrade/03-routes.png"), W, H, [button])                   # renumbered, then changed
    png(os.path.join(b, "ui/upgrade/02-routes.png"), W, H, [(10, 180, 80, 20, BLACK)])
    png(os.path.join(a, "ui/upgrade/09-gone.png"), W, H)
    png(os.path.join(b, "ui/upgrade/08-new-screen.png"), W, H, [button])
    for skip in ("sheet.png", "device/tour1-sheet.png", "tour/contact-sheet.png", "first-run/server-out/sprite.png"):
        png(os.path.join(a, skip), W, H)
        png(os.path.join(b, skip), W, H, [button])
    with open(os.path.join(b, "steps.tsv"), "w", encoding="utf-8") as f:
        f.write("features\t0\t12.5\t\n")

    assert diff.stem("ui/upgrade/03-routes.png") == "ui/upgrade/routes.png"
    assert diff.in_flight("first-run/05-map-first.png") and not diff.in_flight("first-run/05-map.png")
    assert diff.matches_first("ui/features/05-route-card.png", ["Route card", "settings"]) == ["Route card"]
    names = diff.shots(b)
    assert "sheet.png" not in names and "device/tour1-sheet.png" not in names and "tour/contact-sheet.png" not in names
    assert not any("server-out" in n for n in names), names

    # The summary first, as sweep.ps1 runs it; then the diff adds `changes` to it.
    r = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), b, "--no-sheet"], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    r = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-diff.py"), a, b, "--channel", channel,
                        "--first", "routes"], capture_output=True, text=True)
    assert r.returncode == 0, (r.stdout, r.stderr)
    rep = json.load(open(os.path.join(b, "changes.json"), encoding="utf-8"))
    changed = [c["path"] for c in rep["changed"]]
    # --first's screen first, flagged; then the folder's order.
    assert changed == ["ui/upgrade/02-routes.png", "release/release-1.png", "tour/clock-z9.png", "ui/features/03-moved.png"], changed
    assert rep["changed"][0]["first"] == ["routes"] and rep["changed"][0]["was"] == "ui/upgrade/03-routes.png"
    assert rep["count"] == 4 and rep["paired"] == 8, rep
    size = next(c for c in rep["changed"] if c["path"] == "release/release-1.png")
    assert size["sizeChanged"] and size["before"] == [W, H] and size["after"] == [H, W]
    assert rep["fractions"]["ui/features/01-same.png"] == 0
    assert rep["fractions"]["ui/features/02-speck.png"] <= rep["thresholds"]["frac"], rep["fractions"]  # blurred away
    assert rep["fractions"]["ui/features/04-clock.png"] == 0, "the status bar of a device shot is masked"
    assert rep["fractions"]["tour/clock-z9.png"] > rep["thresholds"]["frac"]
    assert [t["path"] for t in rep["timing"]] == ["first-run/05-map-first.png"]
    assert rep["added"] == ["ui/upgrade/08-new-screen.png"] and rep["removed"] == ["ui/upgrade/09-gone.png"]
    assert rep["renumbered"] == [{"was": "ui/upgrade/03-routes.png", "now": "ui/upgrade/02-routes.png"}]
    sheet = os.path.join(b, "changes.png")
    w, h = diff.png_size(sheet)
    assert w == 1600 and h and rep["sheet"]["height"] == h and not rep["sheet"]["cut"], rep["sheet"]

    s = json.load(open(os.path.join(b, "summary.json"), encoding="utf-8"))
    ch = s["changes"]
    assert ch["count"] == 4 and ch["paired"] == 8 and ch["timing"] == 1 and ch["added"] == 1 and ch["removed"] == 1, ch
    assert ch["prev"] == "sweep-a" and ch["first"] == ["ui/upgrade/02-routes.png"]
    assert ch["png"].endswith("changes.png") and ch["json"].endswith("changes.json")
    # A rerun of the summary keeps the entry (it reads changes.json itself).
    r = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), b, "--no-sheet"], capture_output=True, text=True)
    assert json.load(open(os.path.join(b, "summary.json"), encoding="utf-8"))["changes"] == ch
    assert summary.changes(a) is None

    # Two identical folders: nothing changed, and a sheet left from an earlier run is removed.
    r = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-diff.py"), b, b, "--channel", channel], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr
    rep = json.load(open(os.path.join(b, "changes.json"), encoding="utf-8"))
    assert rep["count"] == 0 and not rep["added"] and not rep["removed"] and not os.path.exists(sheet), rep

    # A folder that is not there: exit 2 with the reason.
    r = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-diff.py"), os.path.join(d, "missing"), b], capture_output=True, text=True)
    assert r.returncode == 2 and "not a folder" in r.stdout
    print("test_sweep_diff: ok")
finally:
    shutil.rmtree(d, ignore_errors=True)
