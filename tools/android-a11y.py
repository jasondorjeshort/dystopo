"""The accessibility part (T270): every control's size, label and place on the app's screens, at two font scales.

    python -I tools/android-a11y.py --serial emulator-5574 --port 8698 [--font-scales 1.0,2.0] [--baseline tools/a11y-baseline.json]
                                    [--screens map,route,...] [--dem out/rainier-dem.pmtiles --osm out/rainier-outdoors.pmtiles]
                                    [--no-first-run] [--out out/a11y]

Drives the debug build through its debug routes (the tour's own steps: no taps to get anywhere) to each screen of SCREENS
(the map with each card up, a track's profile, the Activity and Record-as pickers, the recording panel, the Tracks and routes
screen, Offline maps, Settings and each of its pages, the share screen, the first-run card), at each font scale (the system's,
`settings put system font_scale`, put back at the end), and at each screen takes a raw screencap, `uiautomator dump` and the
app's own semantics (`GET /semantics`, `debug/SemanticsRoutes.kt`: Compose's merged and unmerged trees with each node's
bounds and touch bounds; no outside tool reads the touch bounds, and Compose stretches a small `clickable` to a 48 dp touch
area its dumped bounds do not show, so a size check from the dump alone flags wrongly: the dump's count is reported beside it
as `dumpUnder48`). Per screen it lists, by kind:

  under48      a clickable node whose touch bounds are under 48 dp in either side (Android's and Material's figure;
               `under24` true below WCAG 2.5.8's 24 px floor). The 8 dp spacing is not checked.
  unlabelled   a clickable node with no text, no content description and no descendant text.
  duplicate    two clickable nodes with the same bounds; overlap: two whose bounds intersect, neither holding the other.
  textOutside  a text node whose rectangle holds none of its own text's pixels (one colour throughout: T243's case), and
               `GET /layout` `semanticsAway` on the map screens (an element whose semantics are not where it is drawn).
  contrast     text under 4.5:1 (3:1 for large text: 24 dp, or 18.66 dp bold, from the node's text layout): the dominant colour
               of the node's rectangle against the colour farthest from it (a colour with at least 1% of the rectangle),
               WCAG's relative luminance; `advisory` true where the dominant colour covers under 45% of the rectangle (text
               over map imagery) or on a live readout (DYNAMIC_TAGS: the strip dims a stale value), and advisory rows
               never turn the part red nor enter the baseline.
  tapMissed    a node tapped at its centre (only where the tour knows the effect to read back, TAPS) without that effect.
  covered      on a screen or sheet opened over the map: the map's own controls (the test tags the bare map shows) still in
               the tree where the screen's controls are drawn, so TalkBack reaches them under it; one finding a screen with
               `count` and `labels`, and those nodes are left out of the other checks (they were checked on the map).

A node cut by its list's edge (`sizePx` larger than its drawn rectangle) is left out of the size check: it is measured where
it shows whole. Nodes drawn at alpha 0 (`transparent`) or hidden from accessibility are left out of every check.

The MapLibre view is one opaque node: only the Compose overlays are checked. Each screen's frame is saved with its offending
nodes outlined (`<n>-<screen>-<scale>.png`, colours in KIND_COLOURS) and all of them on `a11y-sheet.png`. The report (stdout,
one JSON object) has per screen the counts and the findings; with `--baseline`, a finding is keyed by screen (with the font
scale), kind, text or description (digits read as `#`) and bounds in dp (left, right and height within MATCH_DP, the top
within MATCH_Y_DP), and the part is red only
on a finding the baseline does not list (`baseline.new`); `baseline.gone` lists entries no longer seen (strike them).
Every run writes `baseline-candidate.json` in --out, the file's format, from its non-advisory findings. A screen whose setup
fails is `skipped` with the reason (red unless --allow-skips). Exit 0 when nothing is new (or no --baseline and no error).

`--dem`/`--osm` copy the packs into `files/packs/` first when the phone's copy differs (as the startup part does), and a
synthetic track over Rainier is imported for the profile and navigation screens (android-startup.py's first seed track).
"""
import argparse
import importlib.util
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


firstrun = _load("android_firstrun", "android-firstrun.py")  # Device (captures, dump), raw frames
check = firstrun.check  # Raster, encode_png, fill, draw_text, contact_sheet, changed_share
debug = firstrun.debug
startup = _load("android_startup", "android-startup.py")  # seed_gpx, push_pack

PACKAGE = debug.PACKAGE
ACTIVITY = firstrun.ACTIVITY

MIN_DP = 48.0  # Android's and Material's touch target
FLOOR_DP = 24.0  # WCAG 2.5.8 (AA)
CONTRAST = 4.5  # WCAG 1.4.3
CONTRAST_LARGE = 3.0
LARGE_DP = 24.0  # 18 pt
LARGE_BOLD_DP = 18.66  # 14 pt bold
FG_SHARE = 0.003  # a foreground colour holds at least this share of the rectangle (a thin "-" in its button: 0.8%)
NO_INK_RATIO = 1.2  # a foreground this close to the background is no text of its own: textOutside, not contrast
BUSY_SHARE = 0.45  # under this dominant share, the background is imagery: advisory
INK_DIST = 16  # a pixel this far (largest channel) from the dominant colour is ink (low-contrast text included)
# Live readouts whose words change and dim with the fix's age and the climb's certainty (by design: a dimmed value is a
# stale one): their text rows are advisory, as they differed between two sweeps of one emulator with nothing changed.
DYNAMIC_TAGS = {"readoutStrip"}
MATCH_DP = 6.0  # a baseline entry matches a finding whose left and right edges and height are within this many dp
MATCH_Y_DP = 48.0  # and whose top within this many: a card's place moves with what is above it (the Record-as
# picker's Cancel sat 35 dp apart in two runs on one emulator)
KINDS = ("under48", "unlabelled", "duplicate", "overlap", "textOutside", "contrast", "tapMissed", "covered")
KIND_COLOURS = {"under48": (255, 140, 0), "unlabelled": (230, 0, 230), "duplicate": (0, 200, 255), "overlap": (0, 200, 255),
                "textOutside": (255, 230, 0), "contrast": (40, 90, 255), "tapMissed": (255, 0, 0), "covered": (120, 120, 120)}

RAINIER_VIEW = (46.80, -121.735, 13.0)
PARADISE = (46.7864, -121.7366)
PANORAMA = (46.8024, -121.7289)


# ---------------------------------------------------------------------------------------------------
# The checks (pure; tools/test_android_a11y.py)

def width(r):
    return r[2] - r[0]


def height(r):
    return r[3] - r[1]


def area(r):
    return max(0, width(r)) * max(0, height(r))


def contains(a, b, slack=1):
    """Whether rectangle a holds b (within `slack` px)."""
    return a[0] - slack <= b[0] and a[1] - slack <= b[1] and a[2] + slack >= b[2] and a[3] + slack >= b[3]


def intersect(a, b):
    return [max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])]


def label_of(n):
    return (n.get("text") or n.get("contentDescription") or n.get("editableText") or "").strip()


def key_label(text):
    """The label a baseline keys by: a number (digits, a decimal point) read as #, white space folded, at most 60
    characters ("30 MB" and "3.4 MB" key alike)."""
    return re.sub(r"\s+", " ", re.sub(r"\d+(?:[.,]\d+)*", "#", text or "")).strip()[:60] or "-"


def visible(n, screen):
    """A node drawn on the screen: placed, not hidden, a rectangle of some size inside the screen."""
    b = n.get("boundsPx") or [0, 0, 0, 0]
    return n.get("placed", True) and not n.get("hidden") and not n.get("transparent") and width(b) > 0 and height(b) > 0 and \
        b[2] > 0 and b[3] > 0 and b[0] < screen[0] and b[1] < screen[1]


def clickables(nodes, screen):
    """The merged tree's clickable nodes (a click or a long click, enabled) drawn on the screen."""
    return [n for n in nodes if (n.get("clickable") or n.get("longClickable")) and n.get("enabled", True) and visible(n, screen)]


def children_of(nodes):
    kids = {}
    for n in nodes:
        kids.setdefault(n.get("parent"), []).append(n)
    return kids


def descendants(n, kids):
    out, stack = [], list(kids.get(n["id"], []))
    while stack:
        c = stack.pop()
        out.append(c)
        stack.extend(kids.get(c["id"], []))
    return out


def ancestors(n, by_id):
    out, p = set(), n.get("parent")
    while p is not None and p not in out:
        out.add(p)
        p = (by_id.get(p) or {}).get("parent")
    return out


def finding(kind, n, density, **extra):
    b = n["boundsPx"]
    return {"kind": kind, "label": label_of(n), "boundsPx": list(b), "boundsDp": [round(v / density, 1) for v in b], **extra}


def clipped(n, slack=1):
    """A node cut by its list's or the screen's edge (its drawn rectangle smaller than its own size): its size is read where
    it shows whole."""
    s, b = n.get("sizePx"), n["boundsPx"]
    return bool(s) and (width(b) < s[0] - slack or height(b) < s[1] - slack)


def size_findings(nodes, screen, density):
    out = []
    for n in clickables(nodes, screen):
        if clipped(n):
            continue
        t = n.get("touchBoundsPx") or n["boundsPx"]
        w, h = width(t) / density, height(t) / density
        if min(w, h) < MIN_DP:
            out.append(finding("under48", n, density, touchDp=[round(w, 1), round(h, 1)],
                               layoutDp=[round(width(n["boundsPx"]) / density, 1), round(height(n["boundsPx"]) / density, 1)],
                               under24=min(w, h) < FLOOR_DP))
    return out


def unlabelled_findings(nodes, screen, density):
    kids = children_of(nodes)
    out = []
    for n in clickables(nodes, screen):
        if label_of(n):
            continue
        if any(label_of(d) for d in descendants(n, kids)):
            continue
        out.append(finding("unlabelled", n, density, role=n.get("role"), editable="editableText" in n))
    return out


def overlap_findings(nodes, screen, density, slack=1):
    """Pairs of clickable nodes with the same bounds (duplicate) or crossing bounds, neither holding the other (overlap)."""
    by_id = {n["id"]: n for n in nodes}
    cs = clickables(nodes, screen)
    out = []
    for i, a in enumerate(cs):
        for b in cs[i + 1:]:
            ba, bb = a["boundsPx"], b["boundsPx"]
            if all(abs(x - y) <= slack for x, y in zip(ba, bb)):
                out.append(finding("duplicate", a, density, other=label_of(b), otherBoundsPx=list(bb)))
                continue
            x = intersect(ba, bb)
            if width(x) <= slack or height(x) <= slack:
                continue
            if contains(ba, bb, slack) or contains(bb, ba, slack) or a["id"] in ancestors(b, by_id) or b["id"] in ancestors(a, by_id):
                continue
            out.append(finding("overlap", a, density, other=label_of(b), otherBoundsPx=list(bb), sharedPx=list(x)))
    return out


def luminance(c):
    def ch(v):
        v /= 255.0
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    return 0.2126 * ch(c[0]) + 0.7152 * ch(c[1]) + 0.0722 * ch(c[2])


def contrast_ratio(a, b):
    la, lb = luminance(a), luminance(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def region_colours(r, rect, step=1):
    """The colours of `rect` (clipped to the raster) in buckets of 8 levels a channel: {bucket: [count, sum r, g, b]}, and
    the pixel count."""
    x0, y0 = max(0, rect[0]), max(0, rect[1])
    x1, y1 = min(r.w, rect[2]), min(r.h, rect[3])
    buckets, n = {}, 0
    data, w = r.data, r.w
    for y in range(y0, y1, step):
        row = y * w * 3
        for x in range(x0, x1, step):
            i = row + x * 3
            c0, c1, c2 = data[i], data[i + 1], data[i + 2]
            k = (c0 >> 5, c1 >> 5, c2 >> 5)
            e = buckets.get(k)
            if e is None:
                buckets[k] = [1, c0, c1, c2]
            else:
                e[0] += 1
                e[1] += c0
                e[2] += c1
                e[3] += c2
            n += 1
    return buckets, n


def colour_read(r, rect):
    """(background, foreground, dominant share, ink share, ratio) of `rect`: the dominant bucket's mean colour against the
    bucket of at least FG_SHARE (and 4 pixels) farthest from it in contrast; ink is the share of pixels INK_DIST or more from the background.
    None for an empty rectangle."""
    step = 1 if area(rect) <= 120_000 else 2
    buckets, n = region_colours(r, rect, step)
    if not n:
        return None
    mean = {k: (e[1] / e[0], e[2] / e[0], e[3] / e[0]) for k, e in buckets.items()}
    dom = max(buckets, key=lambda k: buckets[k][0])
    bg = mean[dom]
    fg, best = bg, 1.0
    ink = 0
    for k, e in buckets.items():
        c = mean[k]
        if max(abs(c[0] - bg[0]), abs(c[1] - bg[1]), abs(c[2] - bg[2])) >= INK_DIST:
            ink += e[0]
        if k == dom or e[0] < max(4, FG_SHARE * n):
            continue
        ratio = contrast_ratio(bg, c)
        if ratio > best:
            fg, best = c, ratio
    return {"bg": [round(v) for v in bg], "fg": [round(v) for v in fg], "dominantShare": round(buckets[dom][0] / n, 3),
            "inkShare": round(ink / n, 4), "ratio": round(best, 2)}


def is_large(n):
    st = n.get("textStyle") or {}
    size = st.get("sizeDp")
    if size is None:
        return False
    return size >= LARGE_DP or (st.get("bold") and size >= LARGE_BOLD_DP)


def text_findings(unmerged, screen, density, raster):
    """textOutside and contrast over the unmerged tree's text nodes drawn on the screen; nodes under a disabled control
    (WCAG exempts an inactive component), nodes cut by their list's edge and fields' editable text are left out."""
    by_id = {n["id"]: n for n in unmerged}
    live = in_layer(unmerged, DYNAMIC_TAGS)
    out, read = [], 0
    for n in unmerged:
        if not n.get("text") or not visible(n, screen) or clipped(n):
            continue
        if not n.get("enabled", True) or any(not (by_id.get(a) or {}).get("enabled", True) for a in ancestors(n, by_id)):
            continue
        b = n["boundsPx"]
        rect = [max(0, b[0]), max(0, b[1]), min(raster.w, b[2]), min(raster.h, b[3])]
        if area(rect) <= 0:
            continue
        c = colour_read(raster, rect)
        read += 1
        if c is None:
            continue
        if c["inkShare"] < 0.002 or c["ratio"] < NO_INK_RATIO:
            out.append(finding("textOutside", n, density, source="pixels", colours=c,
                               **({"advisory": True, "dynamic": True} if n["id"] in live else {})))
            continue
        need = CONTRAST_LARGE if is_large(n) else CONTRAST
        if c["ratio"] < need:
            out.append(finding("contrast", n, density, ratio=c["ratio"], need=need, large=is_large(n), colours=c,
                               advisory=c["dominantShare"] < BUSY_SHARE or n["id"] in live, dynamic=n["id"] in live,
                               textStyle=n.get("textStyle")))
    return out, read


def map_tags(nodes):
    """The test tags of the map's own controls (the map screen with nothing open): the layer a screen opens over."""
    return {n["testTag"] for n in nodes if n.get("testTag")}


def in_layer(nodes, tags):
    """The ids of the nodes that carry one of `tags` or lie under a node that does."""
    by_id = {n["id"]: n for n in nodes}
    tagged = {n["id"] for n in nodes if n.get("testTag") in tags}
    return {n["id"] for n in nodes if n["id"] in tagged or ancestors(n, by_id) & tagged}


def union(rects):
    rects = [r for r in rects if area(r) > 0]
    if not rects:
        return None
    return [min(r[0] for r in rects), min(r[1] for r in rects), max(r[2] for r in rects), max(r[3] for r in rects)]


def covered_finding(merged, layer_ids, screen, density):
    """On a screen opened over the map: the map's controls that are still in the tree (TalkBack reaches them) where the
    screen's own controls are drawn, as one finding (`count`, `labels`), its rectangle the screen's controls' extent."""
    own = [n for n in merged if n["id"] not in layer_ids and visible(n, screen) and (n.get("clickable") or label_of(n))]
    area_ = union([n["boundsPx"] for n in own])
    if not area_:
        return None
    under = [n for n in merged if n["id"] in layer_ids and visible(n, screen) and (n.get("clickable") or label_of(n))
             and width(intersect(n["boundsPx"], area_)) > 0 and height(intersect(n["boundsPx"], area_)) > 0]
    if not under:
        return None
    # Keyed by the whole screen: the screen's extent moves with its content (a longer track list).
    return finding("covered", {"text": "map controls under the screen", "boundsPx": [0, 0, screen[0], screen[1]]}, density,
                   count=len(under), areaPx=area_, labels=[label_of(n)[:40] for n in under][:12])


def layout_away(layout, density):
    """`GET /layout` `semanticsAway` as textOutside findings (the layout rule's elements whose semantics are not where drawn)."""
    out = []
    for a in (layout or {}).get("semanticsAway") or []:
        rect = a.get("semanticsPx") or a.get("rectPx") or a.get("drawnPx") or [0, 0, 0, 0]
        n = {"text": a.get("name") or a.get("id") or "", "boundsPx": [round(v) for v in rect]}
        out.append(finding("textOutside", n, density, source="layout", away=a))
    return out


def dump_counts(nodes, density):
    """The `uiautomator dump`'s clickable nodes and how many a check from the dump alone would call under 48 dp."""
    cl = [n for n in nodes if n.get("clickable") and n.get("bounds") and n.get("pkg", PACKAGE) == PACKAGE]
    small = [n for n in cl if min(width(n["bounds"]), height(n["bounds"])) / density < MIN_DP]
    return {"clickable": len(cl), "under48ByDump": len(small)}


def entry_of(screen, f):
    return {"screen": screen, "kind": f["kind"], "label": key_label(f["label"]), "boundsDp": [round(v) for v in f["boundsDp"]]}


def matches(e, f_entry, tol=MATCH_DP, tol_y=MATCH_Y_DP):
    if e["screen"] != f_entry["screen"] or e["kind"] != f_entry["kind"] or e["label"] != f_entry["label"]:
        return False
    a, b = e["boundsDp"], f_entry["boundsDp"]
    return abs(a[0] - b[0]) <= tol and abs(a[2] - b[2]) <= tol and abs(a[1] - b[1]) <= tol_y and \
        abs((a[3] - a[1]) - (b[3] - b[1])) <= tol


def compare(baseline, entries):
    """(new, gone, matched): each finding entry against the baseline's entries, one to one, first match wins."""
    left = list(baseline)
    new, matched = [], 0
    for f in entries:
        hit = next((i for i, e in enumerate(left) if matches(e, f)), None)
        if hit is None:
            new.append(f)
        else:
            left.pop(hit)
            matched += 1
    return new, left, matched


BASELINE_ABOUT = ("T270: the accessibility part's known findings (tools/android-a11y.py, sweep part a11y). One line per "
                  "finding: screen:font scale, kind, text or description (digits as #) and bounds in dp. The part is red only "
                  "on a finding no line lists (left, right and height within 6 dp, the top within 48). A task that fixes a node strikes its line; a new line "
                  "only with the reason in the dated log.")


def baseline_text(entries, about=BASELINE_ABOUT, taken=None):
    """The baseline file: one entry a line, sorted, so a later task strikes one line."""
    rows = sorted(entries, key=lambda e: (e["screen"], e["kind"], e["label"], e["boundsDp"]))
    head = json.dumps({"about": about, "taken": taken}, ensure_ascii=False)[:-1]
    lines = [json.dumps(e, ensure_ascii=False) for e in rows]
    return head + ', "entries": [\n' + ",\n".join(lines) + "\n]}\n"


def load_baseline(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f).get("entries") or []


# ---------------------------------------------------------------------------------------------------
# The sheet

def outline(r, rect, colour, w=4):
    x0, y0, x1, y1 = [int(v) for v in rect]
    x0, y0 = max(0, x0), max(0, y0)
    x1, y1 = min(r.w, x1), min(r.h, y1)
    if x1 <= x0 or y1 <= y0:
        return
    check.fill(r, x0, y0, x1 - x0, min(w, y1 - y0), colour)
    check.fill(r, x0, max(y0, y1 - w), x1 - x0, min(w, y1 - y0), colour)
    check.fill(r, x0, y0, min(w, x1 - x0), y1 - y0, colour)
    check.fill(r, max(x0, x1 - w), y0, min(w, x1 - x0), y1 - y0, colour)


def mark(raster, findings):
    """A copy of the frame with each finding's node outlined in its kind's colour (advisory ones thin)."""
    r = check.Raster(raster.w, raster.h, bytearray(raster.data))
    for f in findings:
        outline(r, f["boundsPx"], KIND_COLOURS.get(f["kind"], (255, 0, 0)), 2 if f.get("advisory") else 5)
    return r


# ---------------------------------------------------------------------------------------------------
# The device

def log(*a):
    print("[a11y]", *a, file=sys.stderr, flush=True)


class Srv:
    """The app's debug server through the forwarded host port, with the token from logcat; re-reads it after a restart."""

    def __init__(self, exe, serial, port):
        self.exe, self.serial, self.port, self.token = exe, serial, port, None

    def reset(self):
        self.token = None

    def call(self, method, path, body=None, timeout=60.0, ctype="application/json"):
        """(status, JSON or None)."""
        for attempt in range(3):
            if self.token is None:
                _, self.token = debug.bootstrap(self.exe, self.serial, self.port, timeout=60.0)
            req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=body, method=method,
                                         headers={"X-Dystopo-Token": self.token, "Content-Type": ctype})
            try:
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    raw = r.read()
                    code = r.status
            except urllib.error.HTTPError as e:
                raw, code = e.read(), e.code
                if code == 401 and attempt < 2:
                    self.token = None
                    continue
            except (OSError, urllib.error.URLError):
                if attempt == 2:
                    raise
                self.token = None
                time.sleep(1.0)
                continue
            try:
                return code, json.loads(raw.decode("utf-8")) if raw else None
            except ValueError:
                return code, None
        return None, None

    def ok(self, method, path, **kw):
        code, j = self.call(method, path, **kw)
        if code is None or code >= 400:
            raise RuntimeError(f"{method} {path}: {code} {json.dumps(j)[:300] if j is not None else ''}")
        return j


def settle(dev, timeout=8.0, quiet=0.002):
    """Raw frames until two in a row 0.6 s apart agree (changed share under `quiet`), or `timeout`; the last frame."""
    deadline = time.time() + timeout
    _, prev = dev.frame()
    while True:
        time.sleep(0.6)
        _, cur = dev.frame()
        if check.changed_share(prev, cur, samples=120) <= quiet or time.time() > deadline:
            return cur
        prev = cur


def set_font_scale(dev, srv, scale, timeout=20.0):
    dev.shell("settings", "put", "system", "font_scale", f"{scale}")
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            code, j = srv.call("GET", "/semantics?tree=merged", timeout=15)
            if code == 200 and abs((j or {}).get("fontScale", 0) - scale) < 0.01:
                return True
        except (OSError, RuntimeError):
            srv.reset()
        time.sleep(1.0)
    return False


def close_all(srv):
    """Every overlay the screens open, closed (each call may answer an error when there is nothing to close), and a fresh
    fix at Paradise, so the readout strip never reads a stale fix's age."""
    for method, path in (("POST", f"/location?lat={PARADISE[0]}&lon={PARADISE[1]}&accuracy=8"), ("POST", "/settings/ui?open=0"), ("POST", "/packs/ui?show=false"), ("POST", "/tracks/ui?open=0"),
                         ("POST", "/tracks/picker?open=close"), ("POST", "/tracks/close"), ("POST", "/map/longpress/cancel"),
                         ("POST", "/nav/stop"), ("POST", "/route/details?open=0"), ("POST", "/route/clear"),
                         ("POST", "/online/chip?open=0"), ("POST", "/help/close")):
        try:
            srv.call(method, path, timeout=20)
        except (OSError, RuntimeError):
            pass


def plan_route(srv, timeout=400):
    """POST /route Paradise to Panorama, waiting out a graph still building (503) up to `timeout` s."""
    deadline = time.time() + timeout
    while True:
        code, j = srv.call("POST", f"/route?from={PARADISE[0]},{PARADISE[1]}&to={PANORAMA[0]},{PANORAMA[1]}&mode=hike&wait=60000", timeout=120)
        if code == 200:
            return j
        if code != 503 or time.time() > deadline:
            raise RuntimeError(f"POST /route: {code} {json.dumps(j)[:200]}")
        time.sleep(5)


class Ctx:
    def __init__(self, dev, srv, args):
        self.dev, self.srv, self.args = dev, srv, args
        self.track_id = None
        self.recorded = None
        self.packs_aside = False
        self.best_start = None


def scr_map(c):
    pass


def scr_longpress(c):
    c.srv.ok("POST", f"/map/longpress?lat={RAINIER_VIEW[0]}&lon={RAINIER_VIEW[1]}")


def scr_online(c):
    c.srv.ok("POST", "/online/chip?open=1")


# T280: Trip details' start-time line is there or not by the clock. Planned at 5:19 PM Eastern on a November day (the
# closing sweep of wave 32) every start ties (`bestStart` kind `any`) and the card draws no line; at 6:18 PM it reads
# "Start at 11:45 AM tomorrow for the most sun", at 3:00 PM "Your start time is the sunniest". Without the line the card
# was 54 dp shorter and the four lines above it 54 dp lower than the baseline's. The route screens plan at a fixed
# instant (the debug clock of `POST /nav/shade`, which the start-time sweep reads: 8:00 AM at Rainier on a past date, so
# no answer the app cached for today's date is reused) and a fixed air temperature (the sun aim, whatever a weather
# station says), wait for the line to be worked out ("Start at 11:30 AM for the most sun"), and put the clock back after.
BEST_START_AT = "2026-11-09T16:00:00Z"
BEST_START_TEMP_C = 5


def pin_clock(c):
    c.srv.ok("POST", f"/nav/shade?at={BEST_START_AT}&tempC={BEST_START_TEMP_C}")


def unpin_clock(c):
    c.srv.call("POST", "/nav/shade?at=now&tempC=", timeout=20)


def wait_best_start(c, timeout=120.0):
    """The card's start-time line once it is worked out (`card.lines.bestStart` no longer `working`): its state and text,
    with the sweep's answer from `GET /route` `bestStart` (`state`, `kind`, `now`, the temperature's source, any error), so
    a frame taken without the line says why."""
    deadline = time.time() + timeout
    line = {}
    while time.time() < deadline:
        j = c.srv.ok("GET", "/route?wait=0") or {}
        line = ((j.get("card") or {}).get("lines") or {}).get("bestStart") or {}
        if line.get("state") not in (None, "working"):
            b = j.get("bestStart") or {}
            return {"state": line.get("state"), "text": line.get("text"), "answer": b.get("state"), "kind": b.get("kind"),
                    "now": b.get("now"), "tempSource": (b.get("temperature") or {}).get("source"), "error": b.get("error")}
        time.sleep(1.0)
    raise RuntimeError(f"the start-time line still working after {timeout:.0f} s: {json.dumps(line)[:200]}")


def scr_route(c):
    pin_clock(c)
    plan_route(c.srv)
    c.srv.ok("POST", "/route/details?open=0")


def scr_route_details(c):
    pin_clock(c)
    plan_route(c.srv)
    c.srv.ok("POST", "/route/details?open=1")
    c.best_start = wait_best_start(c)


def scr_nav(c):
    c.srv.ok("POST", f"/nav/start?track={c.track_id}")


def scr_profile(c):
    c.srv.ok("POST", f"/tracks/{c.track_id}/profile")


def scr_activity(c):
    c.srv.ok("POST", "/tracks/picker?open=mode")


def scr_record_as(c):
    chip = (c.srv.ok("GET", "/tracks/picker").get("chip") or {})
    if not chip.get("askOnRecord", True):
        raise RuntimeError("recordAskActivity is off: Record starts at once, no picker")
    c.srv.ok("POST", "/tracks/picker?open=new")


def scr_recording(c):
    c.srv.ok("POST", f"/location?lat={PARADISE[0]}&lon={PARADISE[1]}&accuracy=8")
    j = c.srv.ok("POST", "/tracks/record?activity=foot")
    c.recorded = ((j or {}).get("recording") or {}).get("id") or (j or {}).get("id")


def end_recording(c):
    code, j = c.srv.call("POST", "/tracks/stop")
    tid = c.recorded or ((j or {}).get("track") or {}).get("id") or (j or {}).get("id")
    if tid:
        c.srv.call("DELETE", f"/tracks/{tid}")
    c.recorded = None


def scr_tracks(c):
    c.srv.ok("POST", "/tracks/ui?open=1")


def scr_packs(c):
    c.srv.ok("POST", "/packs/ui?show=true")


def settings_page(group):
    def fn(c):
        c.srv.ok("POST", f"/settings/open?group={group}")
    return fn


def scr_share(c):
    c.srv.ok("POST", "/share/start")


def end_share(c):
    c.srv.call("POST", "/share/stop")


def restart_app(c):
    c.dev.shell("am", "force-stop", PACKAGE)
    time.sleep(1.0)
    c.dev.shell("am", "start", "-W", "-n", ACTIVITY, timeout=180)
    c.srv.reset()
    deadline = time.time() + 90
    while True:
        try:
            code, j = c.srv.call("GET", "/health", timeout=15)
            if code == 200:
                break
        except (OSError, RuntimeError):
            c.srv.reset()
        if time.time() > deadline:
            raise RuntimeError("the app's debug server did not answer after a restart")
        time.sleep(1.0)
    time.sleep(3.0)


def scr_first_run(c):
    """The packs moved aside in files/packs (run-as), the app restarted: the card shows; put back by end_first_run."""
    c.dev.shell(f"run-as {PACKAGE} sh -c 'mkdir -p files/a11y-aside && mv files/packs/*.pmtiles files/a11y-aside/'", timeout=120)
    c.packs_aside = True
    restart_app(c)
    c.srv.call("POST", "/firstrun/dismiss?undo=1")
    deadline = time.time() + 30
    while time.time() < deadline:
        if (c.srv.ok("GET", "/firstrun") or {}).get("cardVisible"):
            return
        time.sleep(1.0)
    raise RuntimeError("the first-run card did not show with no pack")


def end_first_run(c):
    if c.packs_aside:
        c.dev.shell(f"run-as {PACKAGE} sh -c 'mv files/a11y-aside/*.pmtiles files/packs/ && rmdir files/a11y-aside'", timeout=120)
        c.packs_aside = False
        restart_app(c)
        c.srv.call("POST", f"/camera?lat={RAINIER_VIEW[0]}&lon={RAINIER_VIEW[1]}&zoom={RAINIER_VIEW[2]}")


# (name, setup, teardown or None, layer): "map" for the map with a card up (its controls checked, GET /layout's semanticsAway
# applies), "screen" for a screen or sheet opened over it (the map's controls under it are one `covered` finding).
# close_all runs before every screen; a teardown is for what it does not close.
SCREENS = [
    ("map", scr_map, None, "map"),
    ("longpress", scr_longpress, None, "map"),
    ("online-card", scr_online, None, "map"),
    ("route", scr_route, unpin_clock, "map"),
    ("route-details", scr_route_details, unpin_clock, "map"),
    ("navigation", scr_nav, None, "map"),
    ("profile", scr_profile, None, "map"),
    ("activity-picker", scr_activity, None, "map"),
    ("record-as", scr_record_as, None, "map"),
    ("recording", scr_recording, end_recording, "map"),
    ("tracks-routes", scr_tracks, None, "screen"),
    ("offline-maps", scr_packs, None, "screen"),
    ("settings", settings_page("list"), None, "screen"),
] + [(f"settings-{g}", settings_page(g), None, "screen") for g in ("data", "map", "display", "navigation", "sync", "about")] + [
    ("share", scr_share, end_share, "screen"),
    ("first-run", scr_first_run, end_first_run, "map"),
]


def find_node(sem, pred):
    for w in sem.get("windows") or []:
        for n in w.get("merged") or []:
            if pred(n) and n.get("clickable"):
                return n
    return None


def tap_centre(dev, n):
    b = n["boundsPx"]
    dev.shell("input", "tap", str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
    time.sleep(1.5)


def tap_settings(c, sem):
    n = find_node(sem, lambda n: label_of(n) == "Settings")
    if not n:
        return None
    tap_centre(c.dev, n)
    ok = bool((c.srv.ok("GET", "/settings/layout") or {}).get("open"))
    c.srv.call("POST", "/settings/ui?open=0")
    return n, "settings sheet open (GET /settings/layout open)", ok


def tap_activity(c, sem):
    n = find_node(sem, lambda n: (n.get("contentDescription") or "").startswith("Activity:"))
    if not n:
        return None
    tap_centre(c.dev, n)
    ok = bool((c.srv.ok("GET", "/tracks/picker") or {}).get("open"))
    c.srv.call("POST", "/tracks/picker?open=close")
    return n, "activity picker open (GET /tracks/picker open)", ok


def tap_trip_details(c, sem):
    n = find_node(sem, lambda n: label_of(n).startswith("Trip details"))
    if not n:
        return None
    before = bool(((c.srv.ok("GET", "/route?wait=0") or {}).get("tripDetails") or {}).get("open"))
    tap_centre(c.dev, n)
    after = bool(((c.srv.ok("GET", "/route?wait=0") or {}).get("tripDetails") or {}).get("open"))
    c.srv.call("POST", f"/route/details?open={1 if before else 0}")
    return n, f"Trip details open flips ({before} to {after})", after != before


def tap_profile_close(c, sem):
    n = find_node(sem, lambda n: label_of(n) == "Close")
    if not n:
        return None
    tap_centre(c.dev, n)
    t = c.srv.ok("GET", "/tracks") or {}
    ok = not (t.get("profile") or {}).get("open", bool(t.get("profile")))
    return n, "the profile closed (GET /tracks profile)", ok


TAPS = {"map": [tap_settings, tap_activity], "route": [tap_trip_details], "profile": [tap_profile_close]}


def snapshot(c, name, scale, layer, tags=frozenset()):
    """One screen: settled frame, semantics, dump, layout; the findings. On a screen opened over the map (`layer` "screen")
    the map's own controls (`tags`) are left out of the checks and listed as one `covered` finding where they lie under it."""
    dev, srv = c.dev, c.srv
    settle(dev)
    # A fresh fix at the same place just before the frame: the readout strip dims its words a few seconds after the last
    # fix ("no fix 8 s"), which made a slow screen's strip a contrast finding in one sweep and not the next.
    srv.call("POST", f"/location?lat={PARADISE[0]}&lon={PARADISE[1]}&accuracy=8")
    time.sleep(0.5)
    _, raster = dev.frame()
    t = time.time()
    sem = srv.ok("GET", "/semantics")
    sem_ms = round((time.time() - t) * 1000)
    density = sem.get("density") or 1.0
    screen = sem.get("screenPx") or [raster.w, raster.h]
    nodes = dev.nodes()
    findings, texts_read = [], 0
    merged_all = []
    for w in sem.get("windows") or []:
        merged, unmerged = w.get("merged") or [], w.get("unmerged") or []
        if layer == "screen" and tags:
            mine = in_layer(merged, tags)
            cov = covered_finding(merged, mine, screen, density)
            if cov:
                findings.append(cov)
            merged = [n for n in merged if n["id"] not in mine]
            unmine = in_layer(unmerged, tags)
            unmerged = [n for n in unmerged if n["id"] not in unmine]
        merged_all += merged
        findings += size_findings(merged, screen, density)
        findings += unlabelled_findings(merged, screen, density)
        findings += overlap_findings(merged, screen, density)
        tf, n = text_findings(unmerged, screen, density, raster)
        findings += tf
        texts_read += n
    if layer == "map":
        code, layout = srv.call("GET", "/layout")
        if code == 200:
            findings += layout_away(layout, density)
    return raster, sem, findings, {"semanticsMs": sem_ms, "semanticsWalkMs": sem.get("ms"), "nodes": len(merged_all),
                                    "clickable": len(clickables(merged_all, screen)), "textsRead": texts_read,
                                    "dump": dump_counts(nodes, density), "density": density}


def counts(findings):
    out = {k: 0 for k in KINDS}
    for f in findings:
        out[f["kind"]] += 1
    out["under24"] = sum(1 for f in findings if f.get("under24"))
    out["contrastAdvisory"] = sum(1 for f in findings if f["kind"] == "contrast" and f.get("advisory"))
    return out


def run(args):
    t0 = time.time()
    os.makedirs(args.out, exist_ok=True)
    dev = firstrun.Device(args.serial, args.out)
    srv = Srv(dev.exe, args.serial, args.port)
    c = Ctx(dev, srv, args)
    report = {"part": "a11y", "ok": False, "serial": args.serial, "fontScales": [], "screens": [], "errors": []}
    seed = {"packsCopied": []}
    for src, name in ((args.dem, "dem.pmtiles"), (args.osm, "osm.pmtiles")):
        if src and startup.push_pack(dev.exe, args.serial, src, name):
            seed["packsCopied"].append(name)
    if seed["packsCopied"]:
        restart_app(c)
    srv.ok("GET", "/health")
    tid, gpx = startup.seed_gpx(0, 400)
    have = {t.get("id") for t in (srv.ok("GET", "/tracks").get("tracks") or [])}
    if tid not in have:
        srv.ok("POST", "/tracks/import", body=gpx.encode("utf-8"), ctype="application/gpx+xml")
    c.track_id = tid
    seed["track"] = tid
    report["seed"] = seed
    before = (dev.shell("settings", "get", "system", "font_scale", check_ok=False) or "").strip()
    report["fontScaleBefore"] = before
    wanted = [s for s in SCREENS if not args.screens or s[0] in args.screens]
    if args.no_first_run:
        wanted = [s for s in wanted if s[0] != "first-run"]
    shot = 0
    rasters, sheet_reps = [], []
    try:
        for scale in args.font_scales:
            if not set_font_scale(dev, srv, scale):
                report["errors"].append(f"font scale {scale} not read back by GET /semantics")
            report["fontScales"].append(scale)
            srv.ok("POST", f"/location?lat={PARADISE[0]}&lon={PARADISE[1]}&accuracy=8")  # the same fix for every run
            srv.ok("POST", f"/camera?lat={RAINIER_VIEW[0]}&lon={RAINIER_VIEW[1]}&zoom={RAINIER_VIEW[2]}")
            close_all(srv)
            time.sleep(1.0)
            tags = set()
            for w in srv.ok("GET", "/semantics?tree=merged").get("windows") or []:
                tags |= map_tags(w.get("merged") or [])
            report.setdefault("mapTags", sorted(tags))
            for name, setup, teardown, layer in wanted:
                sname = f"{name}:{scale:.1f}"
                entry = {"screen": sname, "fontScale": scale}
                ts = time.time()
                close_all(srv)
                try:
                    c.best_start = None
                    setup(c)
                    raster, sem, findings, info = snapshot(c, sname, scale, layer, tags)
                    entry.update(info)
                    if c.best_start is not None:  # T280: the start-time line the frame was taken with
                        entry["bestStart"] = c.best_start
                        log(f"{sname}: start-time line {json.dumps(c.best_start)}")
                    taps = []
                    for tap in TAPS.get(name, []) if not args.no_taps else []:
                        try:
                            got = tap(c, sem)
                        except (OSError, RuntimeError) as e:
                            got = None
                            taps.append({"tap": tap.__name__, "error": str(e)[:200]})
                        if got:
                            n, effect, ok = got
                            taps.append({"tap": tap.__name__, "label": label_of(n), "effect": effect, "ok": ok})
                            if not ok:
                                findings.append(finding("tapMissed", n, info["density"], effect=effect))
                    entry["taps"] = taps
                    entry["counts"] = counts(findings)
                    entry["findings"] = findings
                    shot += 1
                    path = os.path.join(args.out, f"{shot:02d}-{sname.replace(':', '-')}.png")
                    marked = mark(raster, findings)
                    check.encode_png(marked, path)
                    entry["png"] = path
                    rasters.append(marked)
                    sheet_reps.append({"index": shot, "label": sname, "ok": True, "failed": []})
                except Exception as e:  # a screen that cannot be reached is listed with its reason
                    entry["skipped"] = f"{type(e).__name__}: {str(e)[:300]}"
                    log(f"{sname}: skipped: {entry['skipped']}")
                finally:
                    try:
                        if teardown:
                            teardown(c)
                    except Exception as e:
                        report["errors"].append(f"{sname} teardown: {e}")
                entry["seconds"] = round(time.time() - ts, 1)
                if "counts" in entry:
                    log(f"{sname}: {entry['counts']} in {entry['seconds']} s")
                report["screens"].append(entry)
    finally:
        try:
            end_first_run(c)
        except Exception as e:
            report["errors"].append(f"packs back: {e}")
        close_all(srv)
        if before and before != "null":
            dev.shell("settings", "put", "system", "font_scale", before, check_ok=False)
        else:
            dev.shell("settings", "delete", "system", "font_scale", check_ok=False)
    # the baseline
    entries = [entry_of(s["screen"], f) for s in report["screens"] for f in s.get("findings") or [] if not f.get("advisory")]
    cand = os.path.join(args.out, "baseline-candidate.json")
    with open(cand, "w", encoding="utf-8", newline="\n") as f:
        f.write(baseline_text(entries, taken=time.strftime("%Y-%m-%d")))
    report["baselineCandidate"] = cand
    new = []
    if args.baseline:
        missing = not os.path.isfile(args.baseline)  # every finding is new: the first run, whose candidate becomes the file
        base = [] if missing else load_baseline(args.baseline)
        new, gone, matched = compare(base, entries)
        report["baseline"] = {"file": args.baseline, "entries": len(base), "matched": matched, "new": new, "gone": gone,
                              **({"missing": True} if missing else {})}
    for s, rep in zip([s for s in report["screens"] if "png" in s], sheet_reps):
        ns = [e for e in new if e["screen"] == s["screen"]]
        rep["ok"] = not ns
        k = s["counts"]
        rep["failed"] = [f"NEW {len(ns)}"] if ns else []
        rep["label"] = f"{s['screen']} {k['under48']}/{k['unlabelled']}/{k['overlap'] + k['duplicate']}/{k['textOutside']}/{k['contrast']}"
        s["new"] = len(ns)
    sheet = os.path.join(args.out, "a11y-sheet.png")
    if rasters:
        check.contact_sheet(sheet_reps, rasters, sheet, thumb_w=270, cols=6)
        report["sheet"] = sheet
    skipped = [s["screen"] for s in report["screens"] if "skipped" in s]
    report["skipped"] = skipped
    report["totals"] = counts([f for s in report["screens"] for f in s.get("findings") or []])
    report["screensChecked"] = sum(1 for s in report["screens"] if "counts" in s)
    report["seconds"] = round(time.time() - t0, 1)
    report.update(firstrun.capture_report())  # the sweep's steps.tsv line counts the capture retries
    report["ok"] = not new and not report["errors"] and (args.allow_skips or not skipped) and report["screensChecked"] > 0
    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--serial", required=True)
    ap.add_argument("--port", type=int, required=True, help="host port forwarded to the app's debug server")
    ap.add_argument("--font-scales", default="1.0,2.0")
    ap.add_argument("--screens", default="", help="comma-separated screen names (default: all)")
    ap.add_argument("--baseline", default="")
    ap.add_argument("--dem", default="")
    ap.add_argument("--osm", default="")
    ap.add_argument("--no-first-run", action="store_true")
    ap.add_argument("--no-taps", action="store_true")
    ap.add_argument("--allow-skips", action="store_true")
    ap.add_argument("--out", default=os.path.join(REPO, "out", "a11y"))
    args = ap.parse_args()
    args.font_scales = [float(x) for x in args.font_scales.split(",") if x.strip()]
    args.screens = [x for x in args.screens.split(",") if x]
    try:
        report = run(args)
    except Exception as e:
        report = {"part": "a11y", "ok": False, "error": f"{type(e).__name__}: {e}"}
    print(json.dumps(report, indent=1))
    sys.exit(0 if report.get("ok") else 1)


if __name__ == "__main__":
    main()
