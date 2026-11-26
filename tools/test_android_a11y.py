"""T270: android-a11y.py's checks offline: sizes by touch bounds, labels, duplicates and overlaps, the pixels of a text
node (contrast, text outside its bounds), the baseline's keys and matching, and the outlined frame.

    python -I tools/test_android_a11y.py
"""
import importlib.util
import json
import os
import shutil
import tempfile

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("android_a11y", os.path.join(here, "android-a11y.py"))
a11y = importlib.util.module_from_spec(spec)
spec.loader.exec_module(a11y)
check = a11y.check

D = 2.625  # the emulators' density (420 dpi)
SCREEN = [1080, 2400]


def node(i, b, parent=0, touch=None, **kw):
    return {"id": i, "parent": parent, "boundsPx": b, "touchBoundsPx": touch or b, "placed": True, "enabled": True, **kw}


# Size: Compose stretches a small clickable's touch area; the check reads the touch bounds, not the layout bounds.
small_stretched = node(1, [0, 0, 84, 84], touch=[-21, -21, 105, 105], clickable=True, text="Fit")  # 32 dp drawn, 48 touch
small = node(2, [200, 0, 284, 60], clickable=True, text="x")  # 32 x 22.9 dp, no stretch
not_clickable = node(3, [300, 0, 310, 10], text="label")
disabled = node(4, [400, 0, 410, 10], clickable=True, enabled=False, text="off")
offscreen = node(5, [0, 3000, 50, 3050], clickable=True, text="below")
hidden = node(6, [500, 0, 510, 10], clickable=True, text="h", hidden=True)
transparent = node(7, [520, 0, 530, 10], clickable=True, text="t", transparent=True)
found = a11y.size_findings([small_stretched, small, not_clickable, disabled, offscreen, hidden, transparent], SCREEN, D)
assert [f["label"] for f in found] == ["x"], found
assert found[0]["under24"] is True and found[0]["touchDp"] == [32.0, 22.9], found
assert a11y.size_findings([node(8, [0, 0, 126, 126], clickable=True)], SCREEN, D) == [], "48 dp exactly passes"
assert a11y.size_findings([node(9, [0, 0, 126, 70], clickable=True)], SCREEN, D)[0]["under24"] is False  # 26.7 dp

# A node cut by its list's edge (drawn smaller than its own size) is measured where it shows whole.
cut = node(15, [0, 2271, 342, 2284], clickable=True, text="From where I am", sizePx=[342, 126])
assert a11y.clipped(cut) and a11y.size_findings([cut], SCREEN, D) == [] and not a11y.clipped(small)

# Labels: a clickable with no text, no description and no descendant text; a merged child's text counts.
icon = node(10, [0, 0, 126, 126], clickable=True, role="Button")
icon_desc = node(11, [0, 200, 126, 326], clickable=True, contentDescription="Recenter")
parent = node(12, [0, 400, 300, 526], clickable=True)
child = node(13, [10, 410, 200, 500], parent=12, text="Download")
field = node(14, [0, 600, 500, 700], clickable=True, editableText="")
un = a11y.unlabelled_findings([icon, icon_desc, parent, child, field], SCREEN, D)
assert [(f["boundsPx"], f["role"], f["editable"]) for f in un] == [([0, 0, 126, 126], "Button", False), ([0, 600, 500, 700], None, True)], un

# Duplicates and overlaps: same bounds, crossing bounds; nesting by geometry or by the tree is not an overlap.
a = node(20, [0, 0, 200, 100], clickable=True, text="A")
b = node(21, [0, 0, 200, 100], clickable=True, text="B")
c = node(22, [150, 50, 350, 150], clickable=True, text="C")
d = node(23, [400, 0, 600, 200], clickable=True, text="D")
e = node(24, [450, 50, 500, 100], clickable=True, text="E")  # inside D
f = node(25, [700, 0, 800, 100], clickable=True, text="F")
g = node(26, [790, 0, 900, 100], parent=25, clickable=True, text="G")  # crosses its own parent: nested by the tree
h = node(27, [900, 0, 1000, 100], clickable=True, text="H")
i = node(28, [999, 0, 1050, 100], clickable=True, text="I")  # shares one pixel column: under the slack
ov = a11y.overlap_findings([a, b, c, d, e, f, g, h, i], SCREEN, D)
kinds = sorted((x["kind"], x["label"], x["other"]) for x in ov)
assert kinds == [("duplicate", "A", "B"), ("overlap", "A", "C"), ("overlap", "B", "C")], kinds

# Contrast and text outside its bounds, on a synthetic frame.
r = check.Raster(400, 300)
check.fill(r, 0, 0, 400, 300, (255, 255, 255))
check.fill(r, 10, 10, 60, 10, (0, 0, 0))  # black "text" on white: 21:1
check.fill(r, 10, 110, 60, 10, (150, 150, 150))  # grey on white: about 2.96:1
check.fill(r, 200, 0, 200, 300, (40, 90, 40))  # a dark green panel
check.fill(r, 210, 110, 60, 10, (60, 110, 60))  # barely lighter green text: low
assert abs(a11y.contrast_ratio((0, 0, 0), (255, 255, 255)) - 21.0) < 0.01
assert abs(a11y.contrast_ratio((118, 118, 118), (255, 255, 255)) - 4.54) < 0.02  # the AA grey
um = [
    node(30, [0, 0, 100, 30], text="Black"),
    node(31, [0, 100, 100, 130], text="Grey"),
    node(32, [0, 100, 100, 130], text="Grey large", textStyle={"fontSizeSp": 24, "fontScale": 1, "sizeDp": 24.0, "bold": False}),
    node(33, [0, 200, 100, 230], text="Gone"),  # all white: none of its text's pixels
    node(34, [200, 100, 300, 130], text="Green"),
    node(35, [0, 200, 100, 230], text="Disabled", parent=36),
    node(36, [0, 190, 120, 240], clickable=True, enabled=False),
]
tf, read = a11y.text_findings(um, SCREEN, D, r)
got = {x["label"]: (x["kind"], x.get("ratio"), x.get("need")) for x in tf}
assert read == 5, read
assert got["Grey"][0] == "contrast" and 2.8 < got["Grey"][1] < 3.1 and got["Grey"][2] == 4.5, got
assert got["Grey large"] == ("contrast", got["Grey"][1], 3.0), got  # 3:1 for large text: grey at 2.96 still fails
assert got["Gone"][0] == "textOutside" and got["Green"][0] == "contrast" and "Black" not in got and "Disabled" not in got, got
assert a11y.is_large({"textStyle": {"sizeDp": 19.0, "bold": True}}) and not a11y.is_large({"textStyle": {"sizeDp": 19.0}})
# A thin glyph (a "-" in its round button: 0.8% of the rectangle) is read as the text, not the button's grey border.
dash = check.Raster(100, 100)
check.fill(dash, 0, 0, 100, 100, (255, 252, 245))
check.fill(dash, 0, 0, 100, 2, (226, 216, 198))  # the border's top edge, 2%
check.fill(dash, 40, 48, 20, 4, (30, 30, 30))  # the dash, 0.8%
assert a11y.text_findings([node(37, [0, 0, 100, 100], text="-")], SCREEN, D, dash)[0] == [], "the dash reads 15:1"
# A rectangle whose farthest colour is under 1.2:1 holds no text of its own: textOutside, not contrast.
faint = check.Raster(100, 20)
check.fill(faint, 0, 0, 100, 20, (255, 252, 245))
check.fill(faint, 0, 0, 100, 2, (240, 238, 232))
assert a11y.text_findings([node(38, [0, 0, 100, 20], text="96")], SCREEN, D, faint)[0][0]["kind"] == "textOutside"

# Over imagery (no colour holds 45% of the rectangle) a contrast row is advisory.
busy = check.Raster(40, 10)
for x in range(40):
    check.fill(busy, x, 0, 1, 10, ((x * 53) % 256, (x * 97) % 256, (x * 31) % 256))
cr = a11y.colour_read(busy, [0, 0, 40, 10])
assert cr["dominantShare"] < a11y.BUSY_SHARE, cr

# Text under a live readout (the strip) is advisory: it dims a stale value by design and changes between runs.
strip = [node(50, [0, 100, 100, 130], testTag="readoutStrip"), node(51, [0, 100, 100, 130], parent=50, text="Grey")]
sf = a11y.text_findings(strip, SCREEN, D, r)[0]
assert sf[0]["kind"] == "contrast" and sf[0]["advisory"] and sf[0]["dynamic"], sf

# GET /layout's semanticsAway as findings.
away = a11y.layout_away({"semanticsAway": [{"id": "routeTo", "name": "Route to here", "semanticsPx": [10.4, 20, 110, 60]}]}, D)
assert away[0]["kind"] == "textOutside" and away[0]["source"] == "layout" and away[0]["boundsPx"] == [10, 20, 110, 60], away

# The dump's own size count (what a check from the dump alone would say).
dn = [{"clickable": True, "bounds": [0, 0, 84, 84], "pkg": a11y.PACKAGE}, {"clickable": True, "bounds": [0, 0, 130, 130], "pkg": a11y.PACKAGE},
      {"clickable": False, "bounds": [0, 0, 1, 1], "pkg": a11y.PACKAGE}, {"clickable": True, "bounds": [0, 0, 1, 1], "pkg": "com.android.systemui"}]
assert a11y.dump_counts(dn, D) == {"clickable": 2, "under48ByDump": 1}

# The baseline: keys with digits as #, matching within 6 dp, one to one; new and gone.
assert a11y.key_label("Show 12 directions") == "Show # directions" and a11y.key_label("") == "-"
assert a11y.key_label("Viewed earlier: 3.4 MB of 1 GB") == a11y.key_label("Viewed earlier: 30 MB of 1 GB") == "Viewed earlier: # B of # B"
# T294: a size keys alike whatever its unit (the wave-34 sweep read "kB" against the baseline's "MB").
assert a11y.key_label("Viewed earlier: 840 kB of 2 GB") == a11y.key_label("Viewed earlier: 12 MB of 2 GB") ==     a11y.key_label("Viewed earlier: 1.2 GB of 2 TB") == a11y.key_label("Viewed earlier: 900 KB of 2GB") ==     a11y.key_label("Viewed earlier: 512 B of 2 GB") == "Viewed earlier: # B of # B"
assert a11y.key_label("Offline maps ›") == "Offline maps ›" and a11y.key_label("Bake 2 Bagels") == "Bake # Bagels"
assert a11y.key_label("Route B") == "Route B" and a11y.key_label("3 km") == "# km"
f1 = a11y.finding("under48", node(40, [263, 263, 525, 341], text="Fit"), D)
e1 = a11y.entry_of("route:1.0", f1)
assert e1 == {"screen": "route:1.0", "kind": "under48", "label": "Fit", "boundsDp": [100, 100, 200, 130]}, e1
moved = dict(e1, boundsDp=[104, 100, 200, 130])
far = dict(e1, boundsDp=[110, 100, 200, 130])
down = dict(e1, boundsDp=[100, 135, 200, 165])  # a row lower, same size
taller = dict(e1, boundsDp=[100, 100, 200, 140])
assert a11y.matches(e1, moved) and a11y.matches(e1, down) and not a11y.matches(e1, far) and not a11y.matches(e1, taller)
assert not a11y.matches(e1, dict(e1, screen="route:2.0")) and not a11y.matches(e1, dict(e1, boundsDp=[100, 160, 200, 190]))
new, gone, matched, mv = a11y.compare([e1, dict(e1, label="Old")], [moved, moved])
assert matched == 1 and new == [moved] and gone == [dict(e1, label="Old")] and mv == [], (new, gone, matched, mv)

# T319: matched by screen, kind and label first; the place only tells twins apart and pairs unlabelled lines.
# Wave 43's case: the long-press card gained a row, and its accepted contrast lines sat 49 dp higher (y 810 to 761);
# Cancel also went from the right of the row to the left. Each label is alone on its screen: moved, not new.
lp = "longpress:1.0"
base43 = [{"screen": lp, "kind": "contrast", "label": "Download around here", "boundsDp": [18, 810, 155, 838]},
          {"screen": lp, "kind": "contrast", "label": "Cancel", "boundsDp": [340, 810, 382, 838]},
          {"screen": "first-run:2.0", "kind": "contrast", "label": "On foot", "boundsDp": [317, 258, 403, 296]}]
now43 = [{"screen": lp, "kind": "contrast", "label": "Download around here", "boundsDp": [18, 761, 155, 790]},
         {"screen": lp, "kind": "contrast", "label": "Sky from here", "boundsDp": [18, 790, 104, 818]},
         {"screen": lp, "kind": "contrast", "label": "Cancel", "boundsDp": [120, 790, 162, 818]},
         {"screen": "first-run:2.0", "kind": "overlap", "label": "On foot", "boundsDp": [317, 258, 403, 296]}]
assert not a11y.matches(base43[0], now43[0]), "49 dp is past the top's tolerance"
new, gone, matched, mv = a11y.compare(base43, now43)
assert [e["label"] for e in mv] == ["Download around here", "Cancel"] and mv[0]["wasDp"] == [18, 810, 155, 838], mv
assert [(e["label"], e["kind"]) for e in new] == [("Sky from here", "contrast"), ("On foot", "overlap")], new  # a new label; a kind changed
assert gone == [base43[2]] and matched == 2, (gone, matched)
# The same label on another screen or scale is another key.
assert a11y.compare(base43[:1], [dict(now43[0], screen="longpress:2.0")])[0] != []
# Twins (the two "‹ Settings" of a settings page): nearest first, a pair within the tolerances before any past them.
tw = "settings-data:1.0"
t_top = {"screen": tw, "kind": "contrast", "label": "‹ Settings", "boundsDp": [24, 56, 325, 86]}
t_low = dict(t_top, boundsDp=[24, 256, 325, 286])
new, gone, matched, mv = a11y.compare([t_top, t_low], [dict(t_top, boundsDp=[24, 60, 325, 90])])
assert new == [] and gone == [t_low] and matched == 1 and mv == [], (new, gone, mv)
new, gone, matched, mv = a11y.compare([t_top, t_low], [dict(t_top, boundsDp=[24, 120, 325, 150]), dict(t_top, boundsDp=[24, 330, 325, 360])])
assert new == [] and gone == [] and [e["wasDp"] for e in mv] == [[24, 56, 325, 86], [24, 256, 325, 286]], mv
# Three now against two: the nearest two pair, the third is new.
far3 = dict(t_top, boundsDp=[24, 700, 325, 730])
new, gone, matched, mv = a11y.compare([t_top, t_low], [far3, dict(t_low), dict(t_top)])
assert new == [far3] and gone == [] and mv == [], (new, mv)
# An unlabelled line is matched by place only, as before: within the tolerances, else new and gone.
un_b = {"screen": "activity-picker:1.0", "kind": "unlabelled", "label": "-", "boundsDp": [22, 66, 189, 84]}
assert a11y.compare([un_b], [dict(un_b, boundsDp=[22, 90, 189, 108])])[2] == 1
far_un = dict(un_b, boundsDp=[22, 300, 189, 318])
assert a11y.compare([un_b], [far_un])[:3] == ([far_un], [un_b], 0)

# --replay: a saved run's report against a baseline file, no device: counts.new and counts.moved, exit 1 on anything new.
import subprocess
import sys
tmp = tempfile.mkdtemp(prefix="t319-a11y-")
try:
    run_json = os.path.join(tmp, "a11y.json")
    findings = [{"kind": e["kind"], "label": e["label"], "boundsPx": [round(v * D) for v in e["boundsDp"]], "boundsDp": e["boundsDp"]}
                for e in now43 if e["screen"] == lp]
    findings.append({"kind": "contrast", "label": "Busy", "boundsPx": [0, 0, 1, 1], "boundsDp": [0, 0, 1, 1], "advisory": True})
    with open(run_json, "w", encoding="utf-8") as fh:
        json.dump({"part": "a11y", "screens": [{"screen": lp, "findings": findings}, {"screen": "share:1.0", "skipped": "x"}]}, fh)
    base_path = os.path.join(tmp, "baseline.json")
    with open(base_path, "w", encoding="utf-8") as fh:
        fh.write(a11y.baseline_text(base43[:2], taken="2026-11-21"))
    rep = a11y.replay(run_json, base_path)
    assert rep["counts"]["moved"] == 2 and rep["counts"]["new"] == 1 and rep["counts"]["findings"] == 3 and not rep["ok"], rep["counts"]
    tool = os.path.join(here, "android-a11y.py")
    p = subprocess.run([sys.executable, "-I", tool, "--replay", run_json, "--baseline", base_path], capture_output=True, text=True)
    assert p.returncode == 1 and json.loads(p.stdout)["counts"]["new"] == 1, (p.returncode, p.stdout[:300], p.stderr[-300:])
    with open(base_path, "w", encoding="utf-8") as fh:
        fh.write(a11y.baseline_text(base43[:2] + [{"screen": lp, "kind": "contrast", "label": "Sky from here", "boundsDp": [18, 790, 104, 818]}]))
    p = subprocess.run([sys.executable, "-I", tool, "--replay", run_json, "--baseline", base_path], capture_output=True, text=True)
    assert p.returncode == 0 and json.loads(p.stdout)["counts"] == {"findings": 3, "entries": 3, "matched": 3, "moved": 2, "new": 0, "gone": 0}, p.stdout[:400]
    # A live run still needs --serial and --port.
    p = subprocess.run([sys.executable, "-I", tool], capture_output=True, text=True)
    assert p.returncode == 2 and "--serial" in p.stderr, p.stderr[-300:]
finally:
    shutil.rmtree(tmp, ignore_errors=True)
# The baseline file's first line is BASELINE_ABOUT's copy.
with open(os.path.join(here, "a11y-baseline.json"), encoding="utf-8") as fh:
    assert json.loads(fh.readline().rstrip()[:-len(', "entries": [')] + "}")["about"] == a11y.BASELINE_ABOUT, "tools/a11y-baseline.json's first line copies BASELINE_ABOUT"
# end T319

# T321: the Horizon view at a chosen point is a screen of its own: opened at Mount Rainier's summit by the debug route (as
# "Sky from here" opens it), the first peak of its list picked (its "View from here" shows), closed by the view's end.
he = [s for s in a11y.SCREENS if s[0] == "horizon-elsewhere"]
assert len(he) == 1 and he[0][3] == "screen" and he[0][2] is a11y.end_horizon, he


class PeakSrv:
    def __init__(self, peaks):
        self.calls, self.peaks = [], peaks

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        return {"peaks": {"peaks": self.peaks}} if method == "GET" else {}


_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    ps = PeakSrv([{"name": "Liberty Cap"}, {"name": "Little Tahoma"}])
    he[0][1](type("C", (), {"srv": ps})())
    assert ps.calls[0] == ("POST", "/horizon/ui?lat=46.8529&lon=-121.7604&wait=90000"), ps.calls
    assert ps.calls[1][0] == "GET" and ps.calls[1][1].startswith("/horizon?wait="), ps.calls
    assert ps.calls[2] == ("POST", "/horizon?select=Liberty%20Cap"), ps.calls
    # No peaks there (no pack): the view at the point alone, nothing picked.
    ps = PeakSrv([])
    he[0][1](type("C", (), {"srv": ps})())
    assert [m for m, _p in ps.calls] == ["POST", "GET"], ps.calls
finally:
    a11y.time.sleep = _sleep
# end T321

# T326: the Panorama view is a screen of its own: opened by the switch's route (its third segment), waited for until its
# skyline and peaks are drawn, closed by the switch's "Map".
pano = [s for s in a11y.SCREENS if s[0] == "panorama"]
assert len(pano) == 1 and pano[0][3] == "screen" and pano[0][2] is a11y.end_panorama, pano


class PanoSrv:
    def __init__(self):
        self.calls = []

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        return {}

    def call(self, method, path, **kw):
        self.calls.append((method, path))
        return 200, {}


_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    ps = PanoSrv()
    ctx = type("C", (), {"srv": ps})()
    pano[0][1](ctx)
    # T327: the four toggles; T330: the steering setting read, then the view pinned in Drag (fixed by T331: T330 changed
    # the screen and not this assertion).
    assert ps.calls == [("POST", "/settings?aircraft=1"), ("GET", "/settings"), ("POST", "/settings?panoramaSteering=drag"),
                        ("POST", "/horizon/switch?to=panorama"), ("GET", "/panorama?wait=60000")], ps.calls
    ps.calls.clear()
    pano[0][2](ctx)
    assert ps.calls == [("POST", "/horizon/switch?to=map"), ("POST", "/settings?aircraft=0")], ps.calls  # PanoSrv's {} found no setting to put back
    ctx.steering_found = "follow"
    ps.calls.clear()
    pano[0][2](ctx)
    assert ps.calls[-1] == ("POST", "/settings?panoramaSteering=follow"), ps.calls
finally:
    a11y.time.sleep = _sleep
# end T326

# T327: the Panorama with the Flights layer on the replay: opened by the switch, turned to the closest flight the view
# lists, scrolled to the Flights list; closed by "Map", the replay stopped and Flight tracking off again.
pf = [s for s in a11y.SCREENS if s[0] == "panorama-flights"]
assert len(pf) == 1 and pf[0][3] == "screen" and pf[0][2] is a11y.end_panorama_flights, pf


class PanoFlightsSrv(PanoSrv):
    def ok(self, method, path, **kw):
        self.calls.append((method, path.split("&wait=")[0] if path.startswith("/aircraft/replay") else path))
        return {"layers": {"flights": {"aircraft": [["a1b2c3", "N123AB", 123.4, 5.6]]}}} if path == "/panorama" else {}


a11y.time.sleep = lambda s: None
try:
    ps = PanoFlightsSrv()
    pf[0][1](type("C", (), {"srv": ps})())
    assert ps.calls == [("POST", "/aircraft/replay?name=a11y&undulationM=-18.4"), ("POST", "/horizon?flights=1&skyRange=all"), ("POST", "/horizon/switch?to=panorama"),
                        ("GET", "/panorama?wait=60000"), ("GET", "/panorama"), ("POST", "/panorama?bearing=123.4")], ps.calls  # T344: no scroll
    ps.calls.clear()
    pf[0][2](type("C", (), {"srv": ps})())
    assert ps.calls[1] == ("POST", "/horizon/switch?to=map") and ps.calls[-1] == ("POST", "/settings?aircraft=0"), ps.calls
finally:
    a11y.time.sleep = _sleep
# end T327

tmp = tempfile.mkdtemp(prefix="t270-a11y-")
try:
    text = a11y.baseline_text([dict(e1, kind="contrast"), e1], taken="2026-11-13")
    lines = text.splitlines()
    assert len(lines) == 4 and lines[1].startswith('{"screen": "route:1.0", "kind": "contrast"') and lines[-1] == "]}", lines
    p = os.path.join(tmp, "baseline.json")
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(text)
    assert a11y.load_baseline(p) == [dict(e1, kind="contrast"), e1]
    assert json.load(open(p, encoding="utf-8"))["taken"] == "2026-11-13"
    # The outlined frame: the finding's edge drawn in its kind's colour, the inside left as it was.
    m = a11y.mark(r, [dict(f1, boundsPx=[100, 100, 200, 200])])
    assert m.px(100, 150) == a11y.KIND_COLOURS["under48"] and m.px(150, 150) == r.px(150, 150) and r.px(100, 150) == (255, 255, 255)
    check.encode_png(m, os.path.join(tmp, "m.png"))
finally:
    shutil.rmtree(tmp, ignore_errors=True)

# T288: an open help pop-up is a screen (Plan drives for's, over its settings page), opened through /help/open by its key.
calls = []


class FakeSrv:
    def ok(self, method, path, **kw):
        calls.append((method, path))
        return {}


help_screens = [s for s in a11y.SCREENS if s[0] == "help-drive"]
assert len(help_screens) == 1 and help_screens[0][3] == "screen", help_screens
help_screens[0][1](type("C", (), {"srv": FakeSrv()})())
assert calls == [("POST", "/help/open?key=settings.drivePreference")], calls
assert len({s[0] for s in a11y.SCREENS}) == len(a11y.SCREENS), "screen names are unique (the baseline keys on them)"

# T307, T308: the Horizon view (the GPS view, `gps`, before T308) is a screen of its own, opened and closed by its debug route.
assert not [s for s in a11y.SCREENS if s[0] == "gps"], "T308 renamed the screen horizon"
horizon = [s for s in a11y.SCREENS if s[0] == "horizon"]
assert len(horizon) == 1 and horizon[0][3] == "screen" and horizon[0][2] is not None, horizon
calls.clear()
a11y.time.sleep, _sleep = (lambda s: None), a11y.time.sleep
horizon[0][1](type("C", (), {"srv": FakeSrv()})())
a11y.time.sleep = _sleep
assert calls == [("POST", "/horizon/ui?open=1")], calls

# T300: the navigation screen pins what earlier parts leave (a heading, the orientation) before it starts, and is skipped
# with the reason when the started navigation still has a heading or the map is not north-up.
import math


class NavSrv:
    def __init__(self, heading=None, mode="north-up"):
        self.calls, self.heading, self.mode = [], heading, mode

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        if path == "/nav":
            return {"heading": {"deg": self.heading, "source": "course" if self.heading is not None else None}}
        if path == "/camera":
            return {"orientation": {"mode": self.mode}}
        return {}


def nav_ctx(srv):
    return type("C", (), {"srv": srv, "track_id": "t1"})()


sleep, pin_s = a11y.time.sleep, a11y.NAV_PIN_S
a11y.time.sleep, a11y.NAV_PIN_S = (lambda s: None), 0.0
try:
    srv = NavSrv()
    a11y.scr_nav(nav_ctx(srv))
    posts = [p for m, p in srv.calls if m == "POST"]
    assert posts[:2] == ["/camera/heading", "/camera/orientation?mode=north-up"], posts
    assert posts[-1] == "/nav/start?track=t1" and posts[-2].startswith(f"/location?lat={a11y.PARADISE[0]}&lon={a11y.PARADISE[1]}&"), posts
    jump = [p for p in posts if p.startswith("/location")][0]
    lat, lon = (float(v.split("=")[1]) for v in jump.split("?")[1].split("&")[:2])
    km = 6371 * math.hypot(math.radians(lat - a11y.PARADISE[0]), math.radians(lon - a11y.PARADISE[1]) * math.cos(math.radians(lat)))
    assert km > 2.5, km  # farther than Heading.JUMP_M, so the course is dropped
    for bad in (NavSrv(heading=310.0), NavSrv(mode="facing")):
        try:
            a11y.scr_nav(nav_ctx(bad))
            raise AssertionError("a heading or facing left must skip the screen")
        except RuntimeError as e:
            assert "did not hold" in str(e), e
finally:
    a11y.time.sleep, a11y.NAV_PIN_S = sleep, pin_s

# T311: the flight card is a map screen; it plays the replay fixture (a body), selects one aircraft, and its end puts
# Flight tracking, the replay and the selection back.
flights = [s for s in a11y.SCREENS if s[0] == "flights-card"]
assert len(flights) == 1 and flights[0][3] == "map" and flights[0][2] is not None, flights
assert os.path.isfile(a11y.FLIGHTS_REPLAY), a11y.FLIGHTS_REPLAY


class BodySrv:
    def __init__(self):
        self.calls = []

    def ok(self, method, path, body=None, **kw):
        self.calls.append((method, path.split("?")[0], body is not None))
        return {}

    def call(self, method, path, body=None, **kw):
        self.calls.append((method, path, body is not None))
        return 200, {}


bs = BodySrv()
flights[0][1](type("C", (), {"srv": bs})())
assert [c[1] for c in bs.calls] == ["/camera", "/aircraft/replay", "/aircraft"] and bs.calls[1][2], bs.calls
bs.calls.clear()
flights[0][2](type("C", (), {"srv": bs})())
assert ("POST", "/settings?aircraft=0", False) in bs.calls and any(p.startswith("/aircraft/replay?stop=1") for _m, p, _b in bs.calls), bs.calls
# end T311

# T312: the Horizon view with Flights is a screen; it plays the replay fixture, turns the layer on, opens the view and
# scrolls to the list; its end closes the view and puts Flight tracking and the replay back.
hf = [s for s in a11y.SCREENS if s[0] == "horizon-flights"]
assert len(hf) == 1 and hf[0][3] == "screen" and hf[0][2] is not None, hf
bs = BodySrv()
_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    hf[0][1](type("C", (), {"srv": bs})())
finally:
    a11y.time.sleep = _sleep
assert [c[1] for c in bs.calls] == ["/aircraft/replay", "/horizon", "/horizon/ui", "/horizon"] and bs.calls[0][2], bs.calls
bs.calls.clear()
hf[0][2](type("C", (), {"srv": bs})())
assert ("POST", "/horizon/ui?open=0", False) in bs.calls and ("POST", "/settings?aircraft=0", False) in bs.calls, bs.calls
# end T312

# T335: the launch check's `since`: the clock when the tool took over the app, then the clock before restart_app's
# force-stop; a reset after a failed call keeps the launch's.
class _Resp:
    status = 200

    def read(self):
        return b"{}"

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


_saved = (a11y.debug.device_now, a11y.debug.bootstrap, a11y.urllib.request.urlopen, a11y.time.sleep)
clock = iter(["11-22 09:00:00.000", "11-22 09:05:00.000"])
boots, shells = [], []
a11y.debug.device_now = lambda exe, serial: next(clock)
a11y.debug.bootstrap = lambda exe, serial, port, timeout=30.0, since=None: (boots.append(since), (41000, "tok"))[1]
a11y.time.sleep = lambda s: None
try:
    srv = a11y.Srv("adb", "emulator-5640", 8700)
    assert srv.since == "11-22 09:00:00.000", srv.since
    a11y.urllib.request.urlopen = lambda req, timeout=0: (_ for _ in ()).throw(OSError("refused"))
    try:
        srv.call("GET", "/health")
        raise AssertionError("must raise")
    except OSError:
        pass
    assert boots == ["11-22 09:00:00.000"] * 3, boots  # every bootstrap of the first launch
    boots.clear()
    a11y.urllib.request.urlopen = lambda req, timeout=0: _Resp()
    dev = type("D", (), {"shell": lambda self, *a, **k: shells.append(a[:2])})()
    a11y.restart_app(type("C", (), {"dev": dev, "srv": srv})())
    assert shells == [("am", "force-stop"), ("am", "start")], shells
    assert srv.since == "11-22 09:05:00.000" and boots == ["11-22 09:05:00.000"], (srv.since, boots)
    srv.reset()  # a failed call: still this launch's clock
    assert srv.since == "11-22 09:05:00.000"
finally:
    a11y.debug.device_now, a11y.debug.bootstrap, a11y.urllib.request.urlopen, a11y.time.sleep = _saved
# end T335

# T332: the map outside every offline map is a map screen: the online sources off (the server and the public sources),
# the camera at Mont Blanc, waited for until the outside line and its action show; its end puts both sources back as found.
mo = [s for s in a11y.SCREENS if s[0] == "map-outside"]
assert len(mo) == 1 and mo[0][3] == "map" and mo[0][2] is a11y.end_map_outside, mo


class OutsideSrv:
    def __init__(self):
        self.calls = []

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        if path == "/settings":
            return {"server": "http://10.0.2.2:9050"}
        if path == "/firstrun/direct":
            return {"enabled": True}
        if path == "/firstrun":
            return {"mapMessage": "outside", "actionShown": "Go to my offline maps"}
        return {}

    def call(self, method, path, **kw):
        self.calls.append((method, path))
        return 200, {}


_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    osrv = OutsideSrv()
    ctx = type("C", (), {"srv": osrv})()
    mo[0][1](ctx)
    assert [p for _m, p in osrv.calls][:4] == ["/settings", "/firstrun/direct", "/settings?server=", "/firstrun/direct?on=0"], osrv.calls
    assert osrv.calls[4][1].startswith("/camera?lat=45.8326&lon=6.8652") and osrv.calls[-1] == ("GET", "/firstrun"), osrv.calls
    osrv.calls.clear()
    mo[0][2](ctx)
    assert [p for _m, p in osrv.calls][:2] == ["/settings?server=http%3A%2F%2F10.0.2.2%3A9050", "/firstrun/direct?on=1"], osrv.calls
finally:
    a11y.time.sleep = _sleep
# end T332

# T333: the screen recording-weak sets the fault sky and the shortened run, records, and waits for the line; its end
# stops and deletes the recording and puts the time and the sky back, in that order.
rw = [s for s in a11y.SCREENS if s[0] == "recording-weak"]
assert len(rw) == 1 and rw[0][3] == "map" and rw[0][2] is a11y.end_recording_weak, rw


class WeakSrv:
    def __init__(self):
        self.calls = []
        self.polls = 0

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        if path.startswith("/tracks/record"):
            return {"started": {"id": "t-1"}}
        if path == "/tracks/weak":
            self.polls += 1
            return {"shown": self.polls >= 2}
        return {}

    def call(self, method, path, **kw):
        self.calls.append((method, path))
        return 200, {}


_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    wsrv = WeakSrv()
    ctx = type("C", (), {"srv": wsrv, "recorded": None})()
    rw[0][1](ctx)
    paths = [p for _m, p in wsrv.calls]
    assert paths[:4] == ["/gnss?case=fault", f"/tracks/weak?holdS={a11y.WEAK_HOLD_S}", "/tracks/record?source=injected&activity=foot", "/tracks/ui?bar=1"], paths
    assert paths[-1] == "/tracks/weak" and wsrv.polls == 2 and ctx.recorded == "t-1", paths
    assert sum(1 for p in paths if p.startswith("/location?")) == 2, paths
    wsrv.calls.clear()
    rw[0][2](ctx)
    assert [p for _m, p in wsrv.calls] == ["/tracks/stop", "/tracks/t-1", "/tracks/weak?holdS=default", "/gnss?clear=1"], wsrv.calls
    assert ctx.recorded is None
finally:
    a11y.time.sleep = _sleep
# end T333

# T331: the Panorama following the phone with a simulated attitude, a target off the view, a correction and the
# weak-compass line; its end puts each pin back (the steering as found, drag when nothing was read).
pa = [s for s in a11y.SCREENS if s[0] == "panorama-attitude"]
assert len(pa) == 1 and pa[0][3] == "screen" and pa[0][2] is a11y.end_panorama_attitude, pa


class AttitudeSrv:
    def __init__(self, steering):
        self.calls, self.steering = [], steering

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        if path == "/settings":
            return {"panoramaSteering": self.steering} if self.steering else {}
        if path == "/panorama":
            return {"hint": {"shown": True}, "arrow": {"side": "left"}, "correctionDeg": 4}
        return {}

    def call(self, method, path, **kw):
        self.calls.append((method, path))
        return 200, {}


_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    for found, back in (("drag", "drag"), ("follow", "follow"), (None, "drag")):
        asrv = AttitudeSrv(found)
        ctx = type("C", (), {"srv": asrv})()
        pa[0][1](ctx)
        paths = [p for _m, p in asrv.calls]
        assert paths[:4] == ["/settings", "/settings?panoramaSteering=follow", "/horizon/switch?to=panorama", "/panorama?wait=60000"], paths
        assert paths[4] == "/panorama?attitude=60,-20,25&attitudeNoise=0" and paths[5] == "/panorama?target=peak%3AMount%20Rainier", paths
        assert paths[6] == "/panorama?correction=4&compassStatus=1" and paths[-1] == "/panorama", paths
        asrv.calls.clear()
        pa[0][2](ctx)
        assert [p for _m, p in asrv.calls] == ["/panorama?compassStatus=none&correction=none&target=none&attitude=none", "/horizon/switch?to=map",
                                               f"/settings?panoramaSteering={back}"], asrv.calls
finally:
    a11y.time.sleep = _sleep
# end T331

# T340: the Panorama at a chosen point (Mount Rainier's summit, as "Sky from here" then the switch open it); its end goes
# back to the map, lets the point go and puts the steering back as found.
pe = [s for s in a11y.SCREENS if s[0] == "panorama-elsewhere"]
assert len(pe) == 1 and pe[0][3] == "screen" and pe[0][2] is a11y.end_panorama_elsewhere, pe
_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    esrv = AttitudeSrv("follow")
    ctx = type("C", (), {"srv": esrv})()
    pe[0][1](ctx)
    assert [p for _m, p in esrv.calls] == ["/settings", "/settings?panoramaSteering=drag", "/horizon/ui?lat=46.8529&lon=-121.7604&wait=90000",
                                           "/horizon/switch?to=panorama", "/panorama?wait=90000"], esrv.calls
    esrv.calls.clear()
    pe[0][2](ctx)
    assert [p for _m, p in esrv.calls] == ["/horizon/switch?to=map", "/horizon/ui?open=0", "/settings?panoramaSteering=follow"], esrv.calls
finally:
    a11y.time.sleep = _sleep
# end T340

# T342: the Offline maps list with a map off: the first row with a switch is turned off, the list opened; the end turns it
# back on and closes the list. A phone with no map has no such row: the setup says so.
class RowsSrv:
    def __init__(self, rows):
        self.calls, self.rows = [], rows

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        return {"rows": self.rows} if path == "/areas/rows" else {}

    def call(self, method, path, **kw):
        self.calls.append((method, path))
        return 200, {}


om = [s for s in a11y.SCREENS if s[0] == "offline-maps-off"]
assert len(om) == 1 and om[0][3] == "screen" and om[0][2] is a11y.end_offline_maps_off, om
_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    osrv = RowsSrv([{"id": "area:a0001", "off": None}, {"id": "pack:rainier fine", "off": False}, {"id": "pack:b", "off": False}])
    ctx = type("C", (), {"srv": osrv})()
    om[0][1](ctx)
    assert [p for _m, p in osrv.calls] == ["/areas/rows", "/areas/rows/off?off=1&id=pack%3Arainier%20fine", "/packs/ui?show=true"], osrv.calls
    osrv.calls.clear()
    om[0][2](ctx)
    assert [p for _m, p in osrv.calls] == ["/areas/rows/off?off=0&id=pack%3Arainier%20fine", "/packs/ui?show=false"], osrv.calls
    none = type("C", (), {"srv": RowsSrv([{"id": "area:a0001", "off": None}])})()
    try:
        om[0][1](none)
        raise AssertionError("a list with no switch must fail the setup")
    except RuntimeError as e:
        assert "no row" in str(e), e
    om[0][2](none)  # nothing turned off: nothing to put back but the list
    assert [p for _m, p in none.srv.calls] == ["/areas/rows", "/packs/ui?show=false"], none.srv.calls
finally:
    a11y.time.sleep = _sleep
# end T342

# T348: the screen recording-stale records, makes the last fix read three days old, turns the card on, unfolds the bar
# and waits for the question card; its end stops and deletes the recording.
rs = [s for s in a11y.SCREENS if s[0] == "recording-stale"]
assert len(rs) == 1 and rs[0][3] == "map" and rs[0][2] is a11y.end_recording_stale, rs


class StaleSrv(WeakSrv):
    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        if path.startswith("/tracks/record"):
            return {"started": {"id": "t-2"}}
        if path == "/tracks/stale":
            self.polls += 1
            return {"shown": self.polls >= 2}
        return {}


_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    ssrv = StaleSrv()
    ctx = type("C", (), {"srv": ssrv, "recorded": None, "drawn_check": None})()
    rs[0][1](ctx)
    assert ctx.drawn_check is a11y.stale_drawn, ctx.drawn_check  # T373: the frame checked against the semantics
    paths = [p for _m, p in ssrv.calls]
    assert paths[1] == "/tracks/record?source=injected&activity=foot" and ctx.recorded == "t-2", paths
    assert paths[3:6] == [f"/tracks/stale?lastFixAgeH={a11y.STALE_AGE_H}&hold=1", "/tracks/wayback?on=1", "/tracks/ui?bar=0"], paths
    assert paths[-1] == "/tracks/stale" and ssrv.polls == 2, paths
    ssrv.calls.clear()
    rs[0][2](ctx)
    assert [p for _m, p in ssrv.calls] == ["/tracks/stop", "/tracks/t-2"], ssrv.calls
    assert ctx.recorded is None
finally:
    a11y.time.sleep = _sleep
# end T348

# T373: the frame is kept only between two semantics reads that agree. The track bar's "Hide" read 200 px away in the
# second read is a move; the live readout's own words changing are not; a node that came is.
def sem_of(*windows):
    return {"windows": [{"unmerged": list(w)} for w in windows]}


strip = node(40, [0, 900, 400, 1000], testTag="readoutStrip")
hide_a = node(41, [920, 717, 1021, 774], text="Hide")
hide_b = node(41, [150, 930, 380, 1020], text="Hide")
v1 = node(42, [10, 910, 100, 960], parent=40, text="5268 ft")
v2 = node(42, [10, 910, 130, 960], parent=40, text="no fix 6 s")
clock = node(43, [500, 500, 620, 540], text="0:00:06")
clock2 = node(43, [500, 500, 622, 540], text="0:00:07")  # within STEADY_PX
a = a11y.places(sem_of([strip, v1, hide_a, clock]))
assert a11y.shifted(a, a11y.places(sem_of([strip, v2, hide_a, clock2]))) == [], "the readout's words and a tick are no move"
assert a11y.shifted(a, a11y.places(sem_of([strip, v1, hide_b, clock]))) == ["Hide"]
assert a11y.shifted(a, a11y.places(sem_of([strip, v1, hide_a, clock, node(44, [0, 0, 10, 10], text="Waypoint")]))) == ["Waypoint"]
assert a11y.shifted(a, a11y.places(sem_of([strip, v1, hide_a, clock], [hide_a]))) == ["Hide"], "a window's node keys apart"


class SnapSrv:
    """The semantics reads in turn from `pairs` (two a try); every other call recorded."""
    def __init__(self, pairs):
        self.reads, self.pairs, self.calls = 0, pairs, []

    def ok(self, method, path, **kw):
        if path == "/semantics":
            s = self.pairs[min(self.reads, len(self.pairs) - 1)]
            self.reads += 1
            return dict(s, density=D, screenPx=SCREEN)
        self.calls.append(path)
        return {}

    def call(self, method, path, **kw):
        self.calls.append(path)
        return 200, {}


class SnapDev:
    def frame(self):
        return None, check.Raster(4, 4)

    def nodes(self):
        return []


_settle, _sleep = a11y.settle, a11y.time.sleep
a11y.settle, a11y.time.sleep = (lambda dev, **kw: None), (lambda s: None)
try:
    moved, held = sem_of([hide_a]), sem_of([hide_b])
    fix_path = f"/location?lat={a11y.PARADISE[0]}&lon={a11y.PARADISE[1]}&accuracy=8"
    sctx = type("C", (), {"dev": SnapDev(), "srv": SnapSrv([moved, held, held, held])})()
    a11y.snapshot(sctx, "recording-stale:2.0", 2.0, "screen")
    assert sctx.frame_tries == 2 and sctx.unsteady == [] and sctx.srv.reads == 4, (sctx.frame_tries, sctx.unsteady)
    assert sctx.srv.calls.count(fix_path) == 2, sctx.srv.calls
    never = type("C", (), {"dev": SnapDev(), "srv": SnapSrv([moved, held] * a11y.FRAME_TRIES)})()
    a11y.snapshot(never, "x:1.0", 1.0, "screen")
    assert never.frame_tries == a11y.FRAME_TRIES and never.unsteady == ["Hide"], never.unsteady
    # A screen's drawn check: a frame it answers with a reason is taken again (the semantics agreeing is not enough).
    answers = ["drawn away from its semantics: Hide", None]
    dctx = type("C", (), {"dev": SnapDev(), "srv": SnapSrv([held]), "drawn_check": staticmethod(lambda r, s: answers.pop(0))})()
    a11y.snapshot(dctx, "recording-stale:2.0", 2.0, "screen")
    assert dctx.frame_tries == 2 and dctx.unsteady == [] and answers == [], (dctx.frame_tries, dctx.unsteady)

finally:
    a11y.settle, a11y.time.sleep = _settle, _sleep

# stale_drawn: the bar's chips dark where the semantics put them, or which are not.
dr = check.Raster(1080, 1200)
check.fill(dr, 0, 0, 1080, 1200, (200, 205, 190))  # the map
check.fill(dr, 920, 717, 102, 58, (43, 36, 32))  # Hide drawn in the column arrangement
sem_b = {"screenPx": [1080, 1200], "windows": [{"unmerged": [node(50, [920, 717, 1022, 775], text="Hide"), node(51, [0, 0, 10, 10], text="x")]}]}
sem_a = {"screenPx": [1080, 1200], "windows": [{"unmerged": [node(50, [243, 946, 345, 1004], text="Hide")]}]}
assert a11y.stale_drawn(dr, sem_b) is None
assert a11y.stale_drawn(dr, sem_a) == "drawn away from its semantics: Hide"

# close_all folds the track bar's panel (opening Tracks and routes unfolds it and its close leaves it so).
csrv = SnapSrv([sem_of()])
a11y.close_all(csrv)
assert csrv.calls.index("/tracks/ui?open=0") < csrv.calls.index("/tracks/ui?bar=0"), csrv.calls
# end T373

# T343: the Download card at Mount Rainier's summit (z13), opened as the map's button does; its end closes it and puts the
# camera back on the view the screens start from.
dc = [s for s in a11y.SCREENS if s[0] == "download-card"]
assert len(dc) == 1 and dc[0][3] == "map" and dc[0][2] is a11y.end_download_card, dc
_sleep = a11y.time.sleep
a11y.time.sleep = lambda s: None
try:
    dsrv = RowsSrv([])
    ctx = type("C", (), {"srv": dsrv})()
    dc[0][1](ctx)
    calls = [p for _m, p in dsrv.calls]
    assert calls[0] == "/readout/reset" and calls[1].startswith("/location?") and calls[2:] == [
        "/camera?lat=46.8529&lon=-121.7604&zoom=13&bearing=0", "/areas/region?wait=60000"], dsrv.calls
    dsrv.calls.clear()
    dc[0][2](ctx)
    assert [p for _m, p in dsrv.calls][0] == "/areas/picker/close" and dsrv.calls[1][1].startswith("/camera?"), dsrv.calls
finally:
    a11y.time.sleep = _sleep
# The readout keys alike with and without its readings (the barometer's "–", the climb's "?"), never a "?" in prose.
assert a11y.key_label("elevation 5405 ft · climb ? ft/h · pressure – mbar") == a11y.key_label("elevation 5405 ft · climb 0 ft/h · pressure 1013 mbar")
assert a11y.key_label("What? Tap here") == "What? Tap here"
# end T343

# T344: the List view with the Flights replay: opened by the switch's "List", waited for its peaks; its end is the
# panorama-flights end (the replay stopped, Flight tracking off).
pl = [s for s in a11y.SCREENS if s[0] == "panorama-list"]
assert len(pl) == 1 and pl[0][3] == "screen" and pl[0][2] is a11y.end_panorama_list, pl
a11y.time.sleep = lambda s: None
try:
    ls = PanoFlightsSrv()
    pl[0][1](type("C", (), {"srv": ls})())
    assert ls.calls == [("POST", "/aircraft/replay?name=a11y&undulationM=-18.4"), ("POST", "/horizon?flights=1&skyRange=all"),
                        ("POST", "/horizon/switch?to=list"), ("GET", "/skylist?wait=60000")], ls.calls
    ls.calls.clear()
    pl[0][2](type("C", (), {"srv": ls})())
    assert ls.calls[1] == ("POST", "/horizon/switch?to=map") and ls.calls[-1] == ("POST", "/settings?aircraft=0"), ls.calls
finally:
    a11y.time.sleep = _sleep
# end T344

# T351: the Panorama with its layers' panel open: Flight tracking on, the steering read and pinned in Drag, the switch's
# Panorama, the wait, then Layers; its end closes the panel first and puts back what it found.
py = [s for s in a11y.SCREENS if s[0] == "panorama-layers"]
assert len(py) == 1 and py[0][3] == "screen" and py[0][2] is a11y.end_panorama_layers, py
a11y.time.sleep = lambda s: None
try:
    ys = PanoSrv()
    yctx = type("C", (), {"srv": ys})()
    py[0][1](yctx)
    assert ys.calls == [("POST", "/settings?aircraft=1"), ("GET", "/settings"), ("POST", "/settings?panoramaSteering=drag"),
                        ("POST", "/horizon/switch?to=panorama"), ("GET", "/panorama?wait=60000"), ("POST", "/panorama?layers=1")], ys.calls
    ys.calls.clear()
    yctx.layers_steering_found = "follow"
    py[0][2](yctx)
    assert ys.calls[0] == ("POST", "/panorama?layers=0") and ys.calls[-1] == ("POST", "/settings?panoramaSteering=follow"), ys.calls
finally:
    a11y.time.sleep = _sleep
# end T351

# T353: the Flights layer's status line is the map's even when the bare map (Flights off) did not show it: under a sky
# view it is in the map's layer, so it is covered, not read for contrast on the view's pixels.
_mt = a11y.map_tags([{"id": 1, "testTag": "scaleBar"}])
assert _mt == {"scaleBar", "flightsStatus"}, _mt
assert 7 in a11y.in_layer([{"id": 7, "testTag": "flightsStatus"}, {"id": 9}], _mt)
# end T353

# T363: the readout's states (the bar dimmed in a gap, its values marked "?") and the glance view after a jump and a gap:
# the barometer set to Never and put back as found, the glance view opened by its route and closed by the end.
# T372: the pressure entry off for both screens and put back as found; the glance view opened held, its scene read back
# and made again when it is not the one wanted.
HELD = {"open": True, "holding": True,
        "lines": [{"kind": "elevation", "reasonCode": "resuming"}, {"kind": "climb", "reasonCode": "resuming"}],
        "notes": {"recent": ["GPS lost for 8 s, 1 s ago", "Position jumped 1.9 mi, 9 s ago", "Position jumped 1.9 mi, 10 s ago"]}}


class ReadoutSrv:
    def __init__(self, glances=None):
        self.calls = []
        self.glances = list(glances or [HELD])

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        if path == "/settings":
            return {"readoutBarometer": "always", "readout": {"pressure": "shown=0;pace=0;damping=medium"}}
        if path == "/readout":
            return {"bar": {"fields": [{"entry": "elevation", "mark": True}]}}
        if path.startswith("/readout/glance?open=1"):
            return self.glances.pop(0) if len(self.glances) > 1 else self.glances[0]
        return {}

    def call(self, method, path, **kw):
        self.calls.append((method, path))
        return 200, {}


ry = {s[0]: s for s in a11y.SCREENS if s[0] in ("readout-states", "glance")}
assert ry["readout-states"][3] == "map" and ry["glance"][3] == "screen", ry
assert a11y.pressure_shown("shown=1;pace=0") == "1" and a11y.pressure_shown("pace=0;shown=0") == "0"
assert a11y.pressure_shown(None) is None and a11y.pressure_shown("pace=0") is None
assert a11y.glance_scene(HELD) is None
assert "hold" in a11y.glance_scene(dict(HELD, holding=False))
assert "climb" in a11y.glance_scene(dict(HELD, lines=[HELD["lines"][0], {"kind": "climb", "reasonCode": "filling"}]))
two_gaps = dict(HELD, notes={"recent": ["GPS lost for 5 s, 0 s ago"] + HELD["notes"]["recent"]})
assert "last minute" in a11y.glance_scene(two_gaps)
assert "last minute" in a11y.glance_scene(dict(HELD, notes={"recent": HELD["notes"]["recent"] + ["1 late GPS fix skipped, 3 s ago"]}))
a11y.time.sleep = lambda s: None
try:
    rs = ReadoutSrv()
    rctx = type("C", (), {"srv": rs})()
    ry["readout-states"][1](rctx)
    assert rs.calls[:4] == [("GET", "/settings"), ("POST", "/settings?readoutBarometer=never"),
                            ("POST", "/settings?readout.pressure=shown=0"), ("POST", "/readout/reset")], rs.calls
    assert sum(1 for c in rs.calls if c[1].startswith("/location?")) == 4 and rs.calls[-1] == ("GET", "/readout"), rs.calls
    rs.calls.clear()
    ry["glance"][1](rctx)
    assert any("provider=network" in c[1] for c in rs.calls) and rs.calls[-1] == ("POST", "/readout/glance?open=1&hold=1"), rs.calls
    assert rs.calls[-2][1].startswith("/location?"), "the gap ended before the view opens"
    rs.calls.clear()
    ry["glance"][2](rctx)
    assert rs.calls == [("POST", "/readout/glance?open=0"), ("POST", "/settings?readoutBarometer=always"),
                        ("POST", "/settings?readout.pressure=shown=0")], rs.calls
    # A scene with a second gap is closed and made again; three that miss fail the screen with the reason.
    rs = ReadoutSrv([two_gaps, HELD])
    rctx = type("C", (), {"srv": rs})()
    ry["glance"][1](rctx)
    assert rs.calls.count(("POST", "/readout/glance?open=1&hold=1")) == 2 and ("POST", "/readout/glance?open=0") in rs.calls, rs.calls
    rs = ReadoutSrv([two_gaps])
    rctx = type("C", (), {"srv": rs})()
    try:
        ry["glance"][1](rctx)
        raise AssertionError("a scene never made should fail")
    except RuntimeError as e:
        assert "3 tries" in str(e) and "last minute" in str(e), e
    assert rs.calls.count(("POST", "/readout/glance?open=1&hold=1")) == a11y.GLANCE_TRIES
finally:
    a11y.time.sleep = _sleep
# end T363

# T364: the map with Flight tracking on and Show flights off is a map screen; it plays the replay, hides the map's flights
# and checks the map asks for none; its end shows them again and puts Flight tracking and the replay back.
class HiddenSrv(BodySrv):
    def ok(self, method, path, body=None, **kw):
        self.calls.append((method, path, body is not None))
        return {"mapAsksBecause": "hidden", "count": 0} if path.startswith("/aircraft?map=0") else {}


hid = [s for s in a11y.SCREENS if s[0] == "map-flights-hidden"]
assert len(hid) == 1 and hid[0][3] == "map" and hid[0][2] is not None, hid
hs = HiddenSrv()
hid[0][1](type("C", (), {"srv": hs})())
assert [c[1].split("?")[0] for c in hs.calls] == ["/camera", "/aircraft/replay", "/aircraft"] and hs.calls[1][2], hs.calls
assert hs.calls[2][1].startswith("/aircraft?map=0"), hs.calls
hs.calls.clear()
hid[0][2](type("C", (), {"srv": hs})())
assert hs.calls[0][1].startswith("/aircraft?map=1") and ("POST", "/settings?aircraft=0", False) in hs.calls, hs.calls


class ShownSrv(HiddenSrv):
    def ok(self, method, path, body=None, **kw):
        self.calls.append((method, path, body is not None))
        return {"mapAsksBecause": None, "count": 3}


try:
    hid[0][1](type("C", (), {"srv": ShownSrv()})())
    raise AssertionError("a map still showing flights passed")
except RuntimeError as e:
    assert "still shows flights" in str(e), e
# end T364

# T369: the Download card over the public map source's cap and a trip's corridor offered, both in direct mode (the server and
# the phone's own reading as found put back by their ends); each checks what it shows and fails when the app does not.
class CapSrv:
    def __init__(self, over=True, offered=True, recorded=False):
        self.calls, self.over, self.offered, self.recorded = [], over, offered, recorded

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        if path == "/settings":
            return {"server": "http://10.0.2.2:8765"}
        if path == "/firstrun/direct":
            return {"enabled": False}
        if path.startswith("/areas/region"):
            return {"overCap": self.over, "canDownload": not self.over, "refusal": "This area needs about 4,700 map tiles" if self.over else None}
        if path.startswith("/routes?"):
            return {"id": "r-1"}
        if path.startswith("/areas/trips"):
            return {"offers": {"r-1": {"area": "a1", "bytes": 1}} if self.offered else {}, "corridors": [{"tripId": "r-1"}] if self.recorded else []}
        return {}

    def call(self, method, path, **kw):
        self.calls.append((method, path))
        return 200, {}


cap = {s[0]: s for s in a11y.SCREENS if s[0] in ("download-card-over", "trip-offer")}
assert set(cap) == {"download-card-over", "trip-offer"} and all(s[3] == "map" and s[2] is not None for s in cap.values()), cap
a11y.time.sleep = lambda s: None
try:
    cs = CapSrv()
    cctx = type("C", (), {"srv": cs})()
    cap["download-card-over"][1](cctx)
    paths = [p for _m, p in cs.calls]
    assert paths[2:4] == ["/settings?server=", "/firstrun/direct?on=1"], paths
    assert any(p.startswith("/areas/region?bbox=-122.26,46.35,-121.26,47.35&zoom=10") for p in paths), paths
    cs.calls.clear()
    cap["download-card-over"][2](cctx)
    assert cs.calls[0] == ("POST", "/areas/picker/close") and ("POST", "/settings?server=http%3A%2F%2F10.0.2.2%3A8765") in cs.calls \
        and ("POST", "/firstrun/direct?on=0") in cs.calls, cs.calls
    try:
        cap["download-card-over"][1](type("C", (), {"srv": CapSrv(over=False)})())
        raise AssertionError("a card within the cap passed")
    except RuntimeError as e:
        assert "not over the cap" in str(e), e
    ts = CapSrv()
    tctx = type("C", (), {"srv": ts})()
    cap["trip-offer"][1](tctx)
    paths = [p for _m, p in ts.calls]
    assert "/settings?server=" in paths and any(p.startswith("/routes?points=") and "mode=crow" in p for p in paths) and any(p.startswith("/nav/shade?at=") for p in paths), paths
    ts.calls.clear()
    cap["trip-offer"][2](tctx)
    assert ts.calls[0] == ("DELETE", "/routes/r-1") and ("POST", "/firstrun/direct?on=0") in ts.calls, ts.calls
    try:
        cap["trip-offer"][1](type("C", (), {"srv": CapSrv(recorded=True)})())
        raise AssertionError("a corridor recorded without the tap passed")
    except RuntimeError as e:
        assert "without the tap" in str(e), e
finally:
    a11y.time.sleep = _sleep
# end T369


# T360: the coverage panel's screen waits for the card's panel to read steady with an offline map shown first, fails when it
# does not; download-card and coverage-panel keep a fix coming every second from their setup to their end.
class CoverageSrv:
    def __init__(self, first="rainier · Full detail · shown", open_=True):
        self.calls, self.first, self.open = [], first, open_

    def ok(self, method, path, **kw):
        self.calls.append((method, path))
        if path == "/online":
            lines = [self.first, "Online · nothing finer here"]
            return {"chip": {"open": self.open, "text": "Online · on this phone", "coverage": {"lines": lines, "shown": lines}}}
        return {}

    def call(self, method, path, **kw):
        self.calls.append((method, path))
        return 200, {}


cov = {s[0]: s for s in a11y.SCREENS if s[0] in ("coverage-panel", "download-card")}
assert cov["coverage-panel"][3] == "map" and cov["coverage-panel"][2] is not None, cov
assert a11y.coverage_scene({"open": True, "coverage": {"shown": ["rainier · Full detail · shown"]}}) is None
assert "not an offline map" in a11y.coverage_scene({"open": True, "coverage": {"shown": ["Online · shown"]}})
assert "not open" in a11y.coverage_scene({"open": False, "coverage": {"shown": ["rainier · Full detail · shown"]}})
a11y.time.sleep = lambda s: None
_feed = a11y.FixFeed
try:
    started = []

    class StubFeed:
        def __init__(self, srv):
            self.thread = type("T", (), {"start": lambda _s: started.append(1)})()

        def stop(self):
            started.append(-1)

    a11y.FixFeed = StubFeed
    vs = CoverageSrv()
    vctx = type("C", (), {"srv": vs})()
    cov["coverage-panel"][1](vctx)
    paths = [p for _m, p in vs.calls]
    assert "/readout/reset" in paths and "/online/chip?open=1" in paths and any(p.startswith("/camera?lat=46.8529") for p in paths), paths
    assert paths.index("/online/cache/clear") < paths.index("/online/chip?open=1"), paths  # the cache pinned before the card
    assert started == [1], started
    vs.calls.clear()
    cov["coverage-panel"][2](vctx)
    assert started == [1, -1] and ("POST", "/online/chip?open=0") in vs.calls and vctx.fix_feed is None, (started, vs.calls)
    started.clear()
    fctx = type("C", (), {"srv": CoverageSrv(first="Nothing on this phone here")})()
    try:
        cov["coverage-panel"][1](fctx)
        raise AssertionError("a panel with no map shown passed")
    except RuntimeError as e:
        assert "not steady" in str(e) and "not an offline map" in str(e), e
    # A setup that raised: the run's loop still calls the screen's end (in its finally), which stops the feed.
    assert started == [1], started
    cov["coverage-panel"][2](fctx)
    assert started == [1, -1] and fctx.fix_feed is None, started
    import inspect
    loop = inspect.getsource(a11y.run)
    assert loop.index("setup(c)") < loop.index("finally:", loop.index("setup(c)")) < loop.index("teardown(c)"), "teardown must run in the screen's finally"
    # The real feed's thread ends on stop.
    real = _feed(CoverageSrv())
    real.thread.start()
    real.stop()
    assert not real.thread.is_alive()
    started.clear()
    dctx = type("C", (), {"srv": CoverageSrv()})()
    cov["download-card"][1](dctx)
    assert started == [1], started
    cov["download-card"][2](dctx)
    assert started == [1, -1], started
finally:
    a11y.time.sleep = _sleep
    a11y.FixFeed = _feed
# end T360


# T381: the layout's hold before the frame (hold_layout), and the two screens in SCREENS.
import unittest


class FlipSrv:
    """GET /layout and GET /semantics as the app answered them: the track bar at one of two places, the next one each read
    while `flips` is on (the finding's scene before T381), else the same."""

    def __init__(self, flips):
        self.flips, self.reads = flips, 0

    def ok(self, method, path, **kw):
        if path == "/layout":
            self.reads += 1
            x = 21 if (not self.flips or self.reads % 2) else 279
            return {"elements": [{"name": "waypointAndTracks", "rectPx": [x, 912, 1059, 1410]}, {"name": "scaleBar", "rectPx": [x, 0, 1, 1]}]}
        if path == "/semantics":
            return {"windows": [{"merged": [{"text": "Hide", "clickable": True, "boundsPx": [169, 925, 382, 1025]}]}]}
        return {}


class T381HoldLayout(unittest.TestCase):
    def setUp(self):
        self.saved = (a11y.HOLD_S, a11y.HOLD_STEP_S, a11y.HOLD_DEADLINE_S)
        a11y.HOLD_S, a11y.HOLD_STEP_S, a11y.HOLD_DEADLINE_S = 0.05, 0.005, 0.3

    def tearDown(self):
        a11y.HOLD_S, a11y.HOLD_STEP_S, a11y.HOLD_DEADLINE_S = self.saved

    def test_a_layout_that_holds_passes(self):
        a11y.hold_layout(type("C", (), {"srv": FlipSrv(False)})(), "t")

    def test_a_layout_that_flips_fails_the_screen_with_what_moved(self):
        with self.assertRaises(RuntimeError) as e:
            a11y.hold_layout(type("C", (), {"srv": FlipSrv(True)})(), "t")
        self.assertIn("did not hold", str(e.exception))
        self.assertIn("waypointAndTracks", str(e.exception))
        self.assertNotIn("scaleBar", str(e.exception), "only the elements that flip with the bar are read")

    def test_the_places_read_hide_from_the_semantics(self):
        self.assertEqual([169, 925, 382, 1025], a11y.layout_places(FlipSrv(False))["Hide"])

    def test_both_screens_are_map_screens_with_a_teardown(self):
        by = {s[0]: s for s in a11y.SCREENS}
        for name in ("recording-stale-unfolded", "activity-covered"):
            self.assertIn(name, by)
            self.assertEqual("map", by[name][3])
            self.assertIsNotNone(by[name][2])
# end T381

print("test_android_a11y: ok")
