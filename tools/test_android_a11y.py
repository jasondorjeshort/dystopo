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
assert a11y.key_label("Viewed earlier: 3.4 MB of 1 GB") == a11y.key_label("Viewed earlier: 30 MB of 1 GB") == "Viewed earlier: # MB of # GB"
f1 = a11y.finding("under48", node(40, [263, 263, 525, 341], text="Fit"), D)
e1 = a11y.entry_of("route:1.0", f1)
assert e1 == {"screen": "route:1.0", "kind": "under48", "label": "Fit", "boundsDp": [100, 100, 200, 130]}, e1
moved = dict(e1, boundsDp=[104, 100, 200, 130])
far = dict(e1, boundsDp=[110, 100, 200, 130])
down = dict(e1, boundsDp=[100, 135, 200, 165])  # a row lower, same size
taller = dict(e1, boundsDp=[100, 100, 200, 140])
assert a11y.matches(e1, moved) and a11y.matches(e1, down) and not a11y.matches(e1, far) and not a11y.matches(e1, taller)
assert not a11y.matches(e1, dict(e1, screen="route:2.0")) and not a11y.matches(e1, dict(e1, boundsDp=[100, 160, 200, 190]))
new, gone, matched = a11y.compare([e1, dict(e1, label="Old")], [moved, moved])
assert matched == 1 and new == [moved] and gone == [dict(e1, label="Old")], (new, gone, matched)

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

print("test_android_a11y: ok")
