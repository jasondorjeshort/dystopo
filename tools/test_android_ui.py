"""T139: the update part's derived fixture tags (android-ui.py) and the both-units part in sweep-summary.py, offline.

    python -I tools/test_android_ui.py
"""
import importlib.util
import json
import os
import shutil
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

print("test_android_ui: ok")
