"""T139: the update part's derived fixture tags (android-ui.py) and the both-units part in sweep-summary.py, offline; T143: the
quiet fixtures' tags, the Diagnostics row's reading and the corridor width case.

    python -I tools/test_android_ui.py
"""
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile

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

print("test_android_ui: ok")
