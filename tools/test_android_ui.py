"""T139: the update part's derived fixture tags (android-ui.py) and the both-units part in sweep-summary.py, offline; T143: the
quiet fixtures' tags, the Diagnostics row's reading and the corridor width case.

    python -I tools/test_android_ui.py
"""
import importlib.util
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time

here = os.path.dirname(os.path.abspath(__file__))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(here, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


ui = load("android_ui", "android-ui.py")
summary = load("sweep_summary", "sweep-summary.py")

# tag_above: the app's reading of a tag without version.json (its leading dotted numbers), patch steps above the build.
assert ui.tag_above("0.1.10-dev") == "v0.1.11-alpha"
assert ui.tag_above("0.1.11-12-gabc1234-sweep", 3) == "v0.1.14-alpha"
assert ui.tag_above("v0.2-alpha", 2) == "v0.2.2-alpha"
assert ui.tag_above("1.0.9", 1) == "v1.0.10-alpha"  # numeric, not text: 9 + 1 is 10
try:
    ui.tag_above("dev")
    raise AssertionError("a name without numbers must be refused")
except ValueError:
    pass
# Every newer fixture of the update part has a tag of its own, all above the build (the fixed v0.1.9/10/11 before).
assert sorted(ui.ABOVE) == ["offer", "wrong-certificate", "wrong-checksum"], ui.ABOVE
tags = {k: ui.tag_above("0.1.11-sweep", n) for k, n in ui.ABOVE.items()}
assert len(set(tags.values())) == 3 and all(int(t.split(".")[2].split("-")[0]) > 11 for t in tags.values()), tags

# T143: the quiet fixtures' tags, below a build (the fixed v0.1.2-alpha before), and the report's `derived`.
assert ui.tag_below("0.1.12-dev") == "v0.1.11-alpha"
assert ui.tag_below("0.1.12-3-gabc1234-sweep", 2) == "v0.1.10-alpha"
assert ui.tag_below("0.2.0") == "v0.1.0-alpha"  # no patch to step down: the minor goes down
assert ui.tag_below("1.0") == "v0.0.0-alpha"
for bad in ("dev", "0.0.0"):
    try:
        ui.tag_below(bad)
        raise AssertionError(f"{bad!r} must be refused")
    except ValueError:
        pass
names = {"previous": "0.1.12-alpha", "current": "0.1.13-5-gabc-dev"}
offered = {"install-previous": "v0.1.11-alpha", "lower-quiet": "v0.1.12-alpha", "wrong-checksum": "v0.1.14-alpha",
           "wrong-certificate": "v0.1.15-alpha", "offer": "v0.1.16-alpha"}
assert ui.derived_tags(offered, names), offered
assert not ui.derived_tags({**offered, "lower-quiet": "v0.1.2-alpha"}, names), "a fixed tag is not derived"
assert not ui.derived_tags({}, names)
assert sorted(ui.BELOW) == ["install-previous", "lower-quiet"], ui.BELOW

# T143: the Diagnostics row read across scrolls, and its sections.
first = ["About and advanced", "Updates", "Diagnostics", "How much memory...", "Max heap: 576.0 MB", "Heap used now: 42.3 MB"]
second = ["Heap used now: 42.3 MB", "Peak since launch: 120.0 MB", "Last memory signal: none", "Last session",
          "Ended: force-stopped at 2026-10-25 14:00:01", "Last memory signal: none", "Peak heap: 150.0 MB of 576.0 MB",
          "Refresh", "Copy report"]
lines = ui.merge_screens(ui.merge_screens([], first), second)
assert lines.count("Heap used now: 42.3 MB") == 1 and lines.count("Last memory signal: none") == 2, lines
sec = ui.diagnostics_sections(lines)
assert sec["now"]["Max heap"] == "576.0 MB" and sec["now"]["Last memory signal"] == "none", sec
assert sec["last"]["Ended"].startswith("force-stopped") and ui.mb_of(sec["last"]["Peak heap"]) == 150.0, sec
assert ui.diagnostics_sections(["Updates"]) == {"now": {}, "last": {}}
assert ui.mb_of("not read") is None


# T143: the corridor width as a phone reports it (bothunits' width case), on a fake debug server.
class FakeSrv:
    def __init__(self, width):
        self.width = width

    def call(self, method, path):
        if path == "/settings":
            return 200, {"tripCorridorM": self.width}
        if path == "/areas/trips":
            return 200, {"widthM": self.width}
        return 200, {"settingsSync": {"synced": {ui.WIDTH_KEY: str(self.width)}}}


w = ui.width_state(FakeSrv(2000))
assert (w["tripCorridorM"], w["widthM"], w["synced"]) == (2000, 2000, "2000"), w

# sweep-summary: a both-units report becomes the part's carries and screenshots; a skip line is listed, not failed.
d = tempfile.mkdtemp(prefix="t139-summary-")
try:
    shots = os.path.join(d, "ui", "bothunits")
    os.makedirs(shots)
    for n in ("01-a-on-to-b-b.png", "02-a-on-to-b-a.png"):
        open(os.path.join(shots, n), "wb").close()
    report = {"part": "bothunits", "ok": True, "peer": "http://10.0.2.2:8783", "steps": [
        {"step": "peer", "ok": True}, {"step": "baseline", "ok": True},
        {"step": "a-on-to-b", "ok": True, "value": "on", "dstAfter": {"bothUnits": True}, "png": os.path.join(shots, "01-a-on-to-b-b.png")},
        {"step": "b-off-to-a", "ok": True, "value": "off", "dstAfter": {"bothUnits": False}}],
        "screenshots": [os.path.join(shots, "01-a-on-to-b-b.png")]}
    with open(os.path.join(d, "bothunits.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1)  # android-ui.py's stdout: the report alone (its log lines go to stderr)
    got = summary.bothunits(d, "bothunits")
    assert got["carries"]["a-on-to-b"] == {"ok": True, "value": "on", "dstBothUnits": True}, got
    assert got["carries"]["b-off-to-a"]["dstBothUnits"] is False, got
    assert got["peer"] == "http://10.0.2.2:8783" and got["screenshots"] == ["ui/bothunits/01-a-on-to-b-b.png"], got
    assert "error" not in got, got
    sections = [s for s, _, _ in summary.sheet_images(d)]
    assert sections.count("Both units, two phones through a sync peer") == 2, sections

    # The whole script on a steps.tsv with the part run, then with it skipped for want of a second emulator.
    def run(lines):
        with open(os.path.join(d, "steps.tsv"), "w", encoding="utf-8") as f:
            f.write("".join(l + "\r\n" for l in lines))
        p = subprocess.run([sys.executable, "-I", os.path.join(here, "sweep-summary.py"), d, "--no-sheet"], capture_output=True, text=True)
        return p.returncode, json.load(open(os.path.join(d, "summary.json"), encoding="utf-8"))

    code, s = run(["bothunits\t0\t95.2\tpeer http://10.0.2.2:8783, phones emulator-5636 and emulator-5638"])
    assert code == 0 and s["parts"]["bothunits"]["ok"] and s["parts"]["bothunits"]["carries"], s
    code, s = run(["bothunits\tskip\t40.0\tskipped: the second emulator emulator-5638 (x-b) did not start (android-emu.ps1 exit 1)"])
    assert code == 0 and s["ok"] and "bothunits" not in s["parts"], s
    assert s["skipped"] and s["skipped"][0].startswith("bothunits (skipped: the second emulator"), s
finally:
    shutil.rmtree(d, ignore_errors=True)

# T176: the recenter part reads the zoom from the scale bar's description and width.
assert ui.scale_metres("Scale 500 ft") == 500 * 0.3048
assert ui.scale_metres("Scale 1 km and 0.5 mi") == 1000.0
assert ui.scale_metres("Scale 2,000 ft") == 2000 * 0.3048
assert ui.scale_metres("Scale") is None
bar = lambda desc, w: [{"desc": "Recenter", "bounds": [0, 0, 10, 10]}, {"desc": desc, "bounds": [20, 2000, 20 + w, 2060]}]
d2 = 2.625  # a Pixel 6's 420 dpi
mpp13 = ui.scale_mpp(bar("Scale 1 km", round(84 * d2 + ui.SCALE_PAD_DP * d2)), d2)  # 1 km over an 84 dp ruler
mpp14 = ui.scale_mpp(bar("Scale 500 m", round(84 * d2 + ui.SCALE_PAD_DP * d2)), d2)  # one level in: half the metres
assert ui.zoom_step(mpp13, mpp14) == 1.0, (mpp13, mpp14)
assert abs(ui.zoom_step(mpp14, ui.scale_mpp(bar("Scale 200 m", round(33.6 * d2 + ui.SCALE_PAD_DP * d2)), d2))) < 0.05
assert ui.zoom_step(None, mpp14) is None and ui.scale_mpp([{"desc": "Recenter", "bounds": [0, 0, 1, 1]}]) is None

# T185: the checked capture (android-firstrun.py's Device.capture) on a fake adb: empty, cut short, then good bytes.
fr = ui.firstrun
png_dir = tempfile.mkdtemp(prefix="t185-png-")
fr.check.encode_png(fr.check.Raster(3, 2, bytearray(range(18))), os.path.join(png_dir, "good.png"))
GOOD = open(os.path.join(png_dir, "good.png"), "rb").read()
shutil.rmtree(png_dir, ignore_errors=True)
assert fr.png_problem(GOOD) is None and fr.png_problem(b"") == "empty"
assert fr.png_problem(GOOD[:len(GOOD) // 2]).startswith("cut short") and fr.png_problem(b"<html>").startswith("not a PNG")


def raw_frame(w, h, fmt=1, head=16):
    return struct.pack("<III", w, h, fmt) + bytes(head - 12) + bytes(range(w * h * 4))


GOOD_RAW = raw_frame(2, 2)
assert fr.raw_problem(GOOD_RAW) is None and fr.raw_problem(raw_frame(2, 2, head=12)) is None
assert fr.raw_problem(GOOD_RAW[:-3]).startswith("cut short") and fr.raw_problem(b"\x00" * 8).startswith("short")


class FakeAdb:
    """subprocess.run for Device: answers each screencap with the next (exit code, stdout, stderr) of a script, or raises
    TimeoutExpired for the entry "timeout". Records the commands and the sleeps."""

    def __init__(self, answers):
        self.answers, self.cmds, self.sleeps = list(answers), [], []

    def __call__(self, cmd, capture_output=True, timeout=60):
        self.cmds.append(cmd)
        a = self.answers.pop(0)
        if a == "timeout":
            raise subprocess.TimeoutExpired(cmd, timeout)
        code, out, err = a
        return subprocess.CompletedProcess(cmd, code, out, err)

    def sleep(self, s):
        self.sleeps.append(s)


def fake_device(answers):
    fake = FakeAdb(answers)
    d = tempfile.mkdtemp(prefix="t185-capture-")
    return fr.Device("emulator-5999", d, exe="adb-fake", run=fake, sleep=fake.sleep), fake, d


def reset_stats():
    fr.CAPTURE_STATS.update(captures=0, retries=0, failures=0, reasons=[])


# screencap: empty, cut short, good; the file is the good PNG, two retries after 0.5 and 1 s, counted.
reset_stats()
dev, fake, d = fake_device([(0, b"", b""), (0, GOOD[:40], b""), (0, GOOD, b"")])
try:
    path, raw = dev.screencap("install-previous")
    assert raw == GOOD and open(path, "rb").read() == GOOD and os.path.basename(path) == "01-install-previous.png", path
    assert fake.sleeps == [0.5, 1] and dev.capture_retries == 2 and len(fake.cmds) == 3, (fake.sleeps, fake.cmds)
    assert all(c[-2:] == ["screencap", "-p"] and c[:3] == ["adb-fake", "-s", "emulator-5999"] for c in fake.cmds), fake.cmds
    rep = fr.capture_report()
    assert rep["captureRetries"] == 2 and len(rep["captureRetryReasons"]) == 2 and "captureFailures" not in rep, rep
    assert "1: empty" in rep["captureRetryReasons"][0] and "cut short" in rep["captureRetryReasons"][1], rep
finally:
    shutil.rmtree(d, ignore_errors=True)

# Every attempt empty: four retries (0.5, 1, 2, 4 s), one error naming all five attempts, and no file written at all.
reset_stats()
dev, fake, d = fake_device([(0, b"", b"")] * 5)
try:
    try:
        dev.screencap("never")
        raise AssertionError("an empty capture must not pass")
    except fr.CaptureError as e:
        assert "failed 5 times" in str(e) and "5: empty" in str(e), str(e)
    assert fake.sleeps == [0.5, 1, 2, 4] and os.listdir(d) == [] and dev.shots == 0, (fake.sleeps, os.listdir(d))
    assert fr.capture_report()["captureRetries"] == 4 and fr.capture_report()["captureFailures"] == 1
finally:
    shutil.rmtree(d, ignore_errors=True)

# A failing exit code (with its stderr in the reason) and a timeout are retried like empty bytes.
reset_stats()
dev, fake, d = fake_device([(1, b"", b"error: closed"), "timeout", (0, GOOD, b"")])
try:
    assert dev.screencap("x")[1] == GOOD and dev.capture_retries == 2
    reasons = fr.CAPTURE_STATS["reasons"]
    assert "exit 1 (error: closed)" in reasons[0] and "exit None (no answer within 60 s)" in reasons[1], reasons
finally:
    shutil.rmtree(d, ignore_errors=True)

# Raw frames (Device.frame, which the parts' still() uses): empty, cut short, then a whole 2x2 RGBA frame.
reset_stats()
dev, fake, d = fake_device([(0, b"", b""), (0, GOOD_RAW[:-5], b""), (0, GOOD_RAW, b"")])
try:
    t, r = dev.frame()
    assert (r.w, r.h) == (2, 2) and r.px(1, 0) == (4, 5, 6) and t is not None, (r.w, r.h, r.px(1, 0))
    assert all("-p" not in c for c in fake.cmds) and fake.sleeps == [0.5, 1], fake.cmds
finally:
    shutil.rmtree(d, ignore_errors=True)

# A raw format raw_raster cannot read (RGB_565 here) is not retried: PNG frames from then on, with no raw capture first.
reset_stats()
dev, fake, d = fake_device([(0, struct.pack("<III", 2, 2, 4) + bytes(4) + bytes(8), b""), (0, GOOD, b""), (0, GOOD, b"")])
try:
    _, r = dev.frame()
    assert (r.w, r.h) == (3, 2) and not dev.raw_frames and fake.sleeps == [], (r.w, fake.sleeps)
    _, r = dev.frame()
    assert [c[-1] for c in fake.cmds] == ["screencap", "-p", "-p"] and fr.CAPTURE_STATS["retries"] == 0, fake.cmds
finally:
    shutil.rmtree(d, ignore_errors=True)

# Run.step: a failed step whose failure screenshot never comes good reports the error and no png (wave 22's 0-byte
# 01-install-previous-failed.png is not written).
reset_stats()
dev, fake, d = fake_device([(0, b"", b"")] * 5)
ui.dismiss_foreign = lambda dev: None  # no uiautomator on the fake
try:
    r = ui.Run(dev)
    assert not r.step("install-previous", lambda: {"ok": False, "error": "boom"})
    assert "png" not in r.steps[0] and os.listdir(d) == [], (r.steps[0], os.listdir(d))
finally:
    shutil.rmtree(d, ignore_errors=True)
reset_stats()

# T191: a step whose process cannot be created (WinError 1455, the paging file full: the 2026-10-31 sweep's record and
# alive steps) fails at once, says so, and tries no failure screenshot (that would need a process too).
if os.name == "nt":
    reset_stats()
    dev, fake, d = fake_device([])  # no answers: a screencap attempt would fail the test by popping an empty list

    def no_process():
        raise OSError(22, "The paging file is too small for this operation to complete", None, 1455)
    try:
        r = ui.Run(dev)
        t0 = time.time()
        assert not r.step("alive", no_process)
        s = r.steps[0]
        assert time.time() - t0 < 2 and s.get("hostOutOfMemory") is True and "paging file" in s["error"] and "png" not in s, s
        assert fake.cmds == [], fake.cmds
        assert ui.host_out_of_memory(MemoryError()) and not ui.host_out_of_memory(OSError(2, "missing"))
    finally:
        shutil.rmtree(d, ignore_errors=True)
    reset_stats()

# T219: the wave's labels, old and new. The route card's header: distance then " · ", with or without a climb.
for line in ("1.8 mi · up 1318 ft · typical 1:15", "2.1 km · 0:40", "1.8 mi · +1318 ft · 1:15", "900 ft · down 300 ft · 0:20"):
    assert re.match(ui.CARD, line), line
for line in ("Nov 3 · 1.2 mi", "Tracks · 3", "From: Paradise"):
    assert not re.match(ui.CARD, line), line
assert ui.route_rows(["Routes", "Paradise to Panorama Point", "1.8 mi · up 1318 ft · 1:15 hike", "Route", "2.1 km · 0:40 bike", "1 via point"]) == \
    (["1.8 mi · up 1318 ft · 1:15 hike", "2.1 km · 0:40 bike"], ["Paradise to Panorama Point", "Route"])
# The Tracks sheet's title and the older list; the row's line, new and old, and one track across the upgrade.
assert re.match(ui.TRACKS_LIST, "Tracks · 12") and re.match(ui.TRACKS_LIST, "Sync status") and not re.match(ui.TRACKS_LIST, "Tracks")
new = ui.parse_track_rows(["Tracks · 1", "● Track 2026-11-03 04:39\nNov 3 · 312 ft", "On foot ›"])
old = ui.parse_track_rows(["Track 2026-11-03 04:39\n14 pts  312 ft\nrecording", "Share"])
assert new == [("Track 2026-11-03 04:39", "Nov 3 · 312 ft")] and old == [("Track 2026-11-03 04:39", "14 pts  312 ft")], (new, old)
assert ui.same_first_track(old, new) and ui.same_first_track(new, new) and not ui.same_first_track(new, [("Track 2026-11-03 04:39", "Nov 3 · 1 mi")])
assert not ui.same_first_track([], new) and not ui.same_first_track(new, [("Track 2026-11-04 09:00", "Nov 4 · 1 mi")])
# T262: the Tracks and routes screen: its title, a track's name and line as two texts, the eye's words, a route's row.
assert re.match(ui.TRACKS_LIST, "Tracks and routes") and re.match(ui.TRACKS_SCREEN, "Tracks and routes")
t262 = ["Tracks and routes", "All · 3", "Tracks · 2", "Track 2026-11-03 04:39", "On foot · Nov 3 · 312 ft", "Shown", "More actions for x",
        "Track 2026-11-02 10:00", "Track · Nov 2 · 0.2 mi · recording", "Hidden"]
assert ui.parse_track_rows(t262) == [("Track 2026-11-03 04:39", "On foot · Nov 3 · 312 ft"), ("Track 2026-11-02 10:00", "Track · Nov 2 · 0.2 mi · recording")]
assert ui.track_states(t262) == ["Shown", "Hidden"]
assert ui.same_first_track(new, ui.parse_track_rows(t262)), "the sheet's row and the screen's row are one track"
assert not ui.same_first_track(new, [("Track 2026-11-03 04:39", "On foot · Nov 3 · 1 mi")])
assert ui.route_rows(["Paradise to Panorama Point", "Hike route · Nov 10 · 1.8 mi · up 1318 ft · 1:15", "Line route · Nov 9 · 2.1 km · 0:40"]) ==     (["1.8 mi · up 1318 ft · 1:15", "2.1 km · 0:40"], ["Paradise to Panorama Point", "Hike route · Nov 10 · 1.8 mi · up 1318 ft · 1:15"])
# T222's failure line and the older ones; a sentence that says it worked is no failure.
for line in ("Download stopped: the connection dropped.", "Sync failed: could not reach your server", "Download failed: timeout"):
    assert re.search(ui.FAILED, line), line
for line in ("Synced with your server: 14 sent, nothing new received", "Syncing with your server", "Paused at 40%. Continues when you are online"):
    assert not re.search(ui.FAILED, line), line
# T223's version lines and the update line.
for line in ("This build: 0.1.19-alpha", "Up to date · 0.1.19-alpha"):
    assert re.match(ui.VERSION_LINE, line), line
assert re.match(ui.UPDATE_LINE, "Up to date · 0.1.19-alpha")
# Only the documents app's button is the save dialog's: the route card has a Save of its own.
assert ui.is_save_button({"pkg": "com.google.android.documentsui", "text": "SAVE", "bounds": [0, 0, 1, 1]})
assert ui.is_save_button({"pkg": "com.android.documentsui", "text": "Save", "bounds": [0, 0, 1, 1]})
assert not ui.is_save_button({"pkg": "io.github.jasondorjeshort.dystopo", "text": "Save", "bounds": [0, 0, 1, 1]})

# The Close of the sheet on top is the last in the tree.
closes = [{"text": "Close", "bounds": [0, 0, 1, 1], "id": "tracks"}, {"text": "Done", "bounds": [0, 0, 1, 1]}, {"text": "Close", "bounds": [2, 2, 3, 3], "id": "status"}]
assert ui.top_close(closes)["id"] == "status" and ui.top_close([]) is None

# A node's place counts once two dumps agree: a page still moving is not tapped.
class Moving:
    def __init__(self):
        self.tops = iter([300, 200, 100, 100])

    def nodes(self):
        return [{"text": "Refresh", "bounds": [0, next(self.tops), 10, 10]}]

    def find(self, pattern, nodes):
        return nodes[0]

    def texts(self):
        return []


real_sleep = ui.time.sleep
ui.time.sleep = lambda s: None
try:
    assert ui.settled(Moving(), "^Refresh$")["bounds"][1] == 100
finally:
    ui.time.sleep = real_sleep

# T235 (ADR 0056 B1b): the one list's labels, the Packs screen's still accepted.
for label in ("Packs", "Offline maps"):
    assert re.search(ui.PACKS_CHIP, label), label
assert not re.search(ui.PACKS_CHIP, "Offline maps ›") and not re.search(ui.PACKS_CHIP, "Offline areas")
for header in ("▸ From server", "▾ From server", "From server"):
    assert re.search(ui.FROM_SERVER, header), header
assert re.search(ui.ADD_FOLD, "▸ More ways to add a map") and re.search(ui.ADD_FOLD, "▾ More ways to add a map")
assert not re.search(ui.ADD_FOLD, "From server")
# A finished catalog download: the catalog row's word, the Packs screen's words, or one more row of the list reading Saved.
assert ui.pack_saved(["rainier", "INSTALLED"]) and ui.pack_saved(["ACTIVE"]) and ui.pack_saved(["Saved as rainier"])
assert ui.pack_saved(["default", "Saved", "rainier", "Saved"], saved_before=1)
assert not ui.pack_saved(["default", "Saved", "rainier", "Downloading 40%"], saved_before=1)


class Folded:
    """A list whose fold is below the window until one swipe, then shows folded."""
    def __init__(self):
        self.swipes, self.taps = 0, []

    def wait_for(self, pattern, timeout):
        n = {"text": "▸ More ways to add a map", "bounds": [0, 0, 1, 1]} if self.swipes else None
        return (n if n and re.search(pattern, n["text"]) else None), []

    def shell(self, *a):
        if a[:2] == ("input", "swipe"):
            self.swipes += 1
        return "Physical size: 1080x2400" if a[:2] == ("wm", "size") else "Physical density: 420"

    def tap(self, n):
        self.taps.append(n["text"])


ui.time.sleep = lambda s: None
try:
    f = Folded()
    assert ui.open_fold_header(f, ui.ADD_FOLD)["text"].endswith("add a map") and f.swipes == 1 and f.taps == ["▸ More ways to add a map"]
    g = Folded()
    assert ui.open_fold_header(g, ui.FROM_SERVER, swipes=2) is None and g.swipes == 3 and not g.taps
finally:
    ui.time.sleep = real_sleep

# T243: the long-press card's nodes from its coordinate line to Cancel, and another element's node inside it (478deae5's
# strip, drawn above the card, its semantics node left under it); the window holding the card is not one.
def card_node(text, b, desc="", click=True):
    return {"text": text, "desc": desc, "cls": "TextView", "clickable": click, "bounds": b, "pkg": ui.PACKAGE}


card = [card_node("", [0, 0, 1080, 2400], click=False), card_node("46.80241, -121.72892", [47, 1822, 907, 1859], click=False),
        card_node("Route to here", [47, 2026, 262, 2063]), card_node("Waypoint here", [304, 2026, 534, 2152]),
        card_node("Cancel", [885, 2101, 1011, 2219]), card_node("© OpenStreetMap contributors", [16, 2219, 1064, 2345])]
strip = card_node("", [0, 2063, 404, 2189], desc="elevation 61 ft · climb – ft/h")
actions, intruders = ui.card_intruders(card + [strip, card_node("", [336, 1619, 740, 1745], desc="the strip where it is drawn")])
assert list(actions) == ["Route to here", "Waypoint here", "Cancel"], actions
assert intruders == [["elevation 61 ft · climb – ft/h", [0, 2063, 404, 2189]]], intruders
assert ui.card_intruders(card) == (actions, []) and ui.card_intruders(card[:1]) == ({}, [])

# T253: the record steps end a recording as a user does: Pause, then Finish; a build before T253 by its one Stop. The
# folded chip reads the red distance while recording, and Hide folds the bar (the "×" on an older build).
class Recorder:
    """The bar's chips by state: folded, or unfolded with its record group."""
    def __init__(self, old=False):
        self.state, self.open, self.old, self.taps = "recording", False, old, []

    def nodes(self):
        if not self.open:
            label = "● 1.2 km" if self.state != "idle" else "Tracks"
            return [{"text": "Waypoint", "desc": "", "bounds": [0, 0, 1, 1]}, {"text": label, "desc": "", "bounds": [2, 0, 3, 1]}]
        group = {"idle": ["Record"], "recording": ["Stop"] if self.old else ["Pause"], "paused": ["Resume", "Finish"]}[self.state]
        names = (["×"] if self.old else ["Hide"]) + ["Waypoint", "Tracks"] + group
        return [{"text": t, "desc": "", "bounds": [i, 0, i + 1, 1]} for i, t in enumerate(names)]

    def texts(self, nodes=None):
        return [n["text"] for n in (nodes or self.nodes())]

    def find(self, pattern, nodes=None, exact=False):
        return next((n for n in (nodes or self.nodes()) if re.search(pattern, n["text"])), None)

    def wait_for(self, pattern, timeout, exact=False):
        nodes = self.nodes()
        return self.find(pattern, nodes), nodes

    def tap(self, n):
        t = n["text"]
        self.taps.append(t)
        if t.startswith("●") or t == "Tracks":
            self.open = True
        elif t in ("Hide", "×"):
            self.open = False
        elif t == "Pause":
            self.state = "paused"
        elif t == "Resume":
            self.state = "recording"
        elif t in ("Finish", "Stop"):
            self.state = "idle"

    def tap_text(self, pattern, timeout=15, exact=False):
        n = self.find(pattern)
        if not n:
            raise RuntimeError(pattern)
        self.tap(n)
        return n


for old, taps in ((False, ["● 1.2 km", "Pause", "Finish"]), (True, ["● 1.2 km", "Stop"])):
    r = Recorder(old)
    ui.finish_recording(r)
    assert r.state == "idle" and r.taps == taps, (old, r.taps)
    ui.close_tracks(r)
    assert not r.open and r.taps[-1] == ("×" if old else "Hide"), r.taps
r = Recorder()
r.state, r.open = "paused", True
ui.finish_recording(r)
assert r.taps == ["Finish"], r.taps  # already paused: Finish alone

# T272: the recording notification's actions read from dumpsys: Pause alone while recording, Resume and Finish paused.
DUMP = """  NotificationRecord(0x0123: pkg=com.other user=UserHandle{0} id=31 tag=null importance=2
        actions={
            [0] "Stop" -> PendingIntent{1}
        }
  NotificationRecord(0x058d: pkg=%s user=UserHandle{0} id=31 tag=null importance=2 key=0|x: Notification(channel=tracking actions=%d vis=PRIVATE))
      uid=10209 userId=0
        actions={
%s
        }
      effectiveNotificationChannel=NotificationChannel{mId='tracking'}
"""
row = '            [%d] "%s" -> PendingIntent{443dd01: PendingIntentRecord{7808c8d %s broadcastIntent}}'
assert ui.notification_actions(DUMP % (ui.PACKAGE, 1, row % (0, "Pause", ui.PACKAGE))) == ["Pause"]
assert ui.notification_actions(DUMP % (ui.PACKAGE, 2, row % (0, "Resume", ui.PACKAGE) + "\n" + row % (1, "Finish", ui.PACKAGE))) == ["Resume", "Finish"]
assert ui.notification_actions("nothing posted") is None
assert ui.notification_actions(DUMP.split("  NotificationRecord(0x058d")[0]) is None  # another app's id 31 is not ours

print("test_android_ui: ok")

# T278: a kept Walk's row says "Walk route"; a trip kept before T278 still "Hike route"; the Trip details row's title.
assert ui.route_row_words(["Chamonix streets", "Walk route · Nov 13 · 2.3 km · 0:33", "Hike route · Nov 9 · 1.8 mi · up 1318 ft · 1:15", "Walk"]) == ["Walk", "Hike"]
assert ui.route_rows(["Chamonix streets", "Walk route · Nov 13 · 2.3 km · 0:33"]) == (["2.3 km · 0:33"], ["Chamonix streets"])
assert re.match(ui.TRIP_DETAILS, "▸ Trip details") and re.match(ui.TRIP_DETAILS, "▾ Trip details") and not re.match(ui.TRIP_DETAILS, "Trip details")
# end T278
