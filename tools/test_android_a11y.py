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

print("test_android_a11y: ok")
