"""The accessibility part (T270): every control's size, label and place on the app's screens, at two font scales.

    python -I tools/android-a11y.py --serial emulator-5574 --port 8698 [--font-scales 1.0,2.0] [--baseline tools/a11y-baseline.json]
                                    [--screens map,route,...] [--dem out/rainier-dem.pmtiles --osm out/rainier-outdoors.pmtiles]
                                    [--no-first-run] [--out out/a11y]
    python -I tools/android-a11y.py --replay out/sweep-<date>/a11y.json --baseline tools/a11y-baseline.json   # T319, no device

Drives the debug build through its debug routes (the tour's own steps: no taps to get anywhere) to each screen of SCREENS
(the map with each card up, a track's profile, the Activity and Record-as pickers, the recording panel, the Tracks and routes
screen, Offline maps, Settings and each of its pages, the Horizon view, the share screen, the first-run card, T311 the flight card, T312 the Horizon view's Flights), at each font scale (the system's,
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
scale), kind and text or description (digits read as `#`). T319: a key alone on its screen on both sides matches its line
wherever it sits; where a key occurs more than once (twins) the pairs go nearest first, and an unlabelled line (`-`) matches
by place only (left, right and height within MATCH_DP, the top within MATCH_Y_DP). A pair past those tolerances is listed
in `baseline.moved` (with `wasDp`) and never makes the part red. The part is red only on a finding the baseline does not
list (`baseline.new`; a new label, or a kind that changed); `baseline.gone` lists entries no longer seen (strike them);
`counts` has new, moved, gone and matched. `--replay <a11y.json> --baseline <file>` makes the same comparison from a saved
run's report with no device (no --serial or --port), prints it, and exits 1 when anything is new, as a live run does.
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

PACKAGE = debug.DEBUG_PACKAGE  # T303: the debug build's own id; firstrun's ACTIVITY is the release's
ACTIVITY = debug.activity(PACKAGE)

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
    """The label a baseline keys by: a number (digits, a decimal point) read as #, a byte unit after it (B, kB, KB,
    MB, GB, TB) read as B, white space folded, at most 60 characters ("30 MB", "3.4 MB" and "840 kB" key alike as
    "# B", so a size that crosses a unit keeps its baseline line). T343: a reading's no-value mark before its unit ("?" or a dash,
    "pressure – mbar", "climb ? ft/h": the readout before the barometer or the climb has a reading) is read as # too, so a line
    holds whether the reading has come yet or not."""
    folded = re.sub(r"\d+(?:[.,]\d+)*", "#", text or "")
    folded = re.sub(r"(?<!\S)[?–—-](?=\s+(?:ft/h|m/h|ft|m|mbar|hPa|inHg|mmHg|mi|km)(?![A-Za-z]))", "#", folded)  # T343
    folded = re.sub(r"#\s*(?:B|kB|KB|MB|GB|TB)(?![A-Za-z])", "# B", folded)
    return re.sub(r"\s+", " ", folded).strip()[:60] or "-"


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


# T353: the map's own overlays that the bare map (Flights off) does not show, so its tags miss them: the Flights layer's
# status line ("Flights: Getting flight data" while the feed loads, AircraftCard.kt). On a sky view with Flights on, the
# line lay in the tree under the view, and its contrast was read on the view's pixels (2.22: the plot, not the line).
MAP_OVERLAY_TAGS = frozenset({"flightsStatus"})


def map_tags(nodes):
    """The test tags of the map's own controls (the map screen with nothing open): the layer a screen opens over; T353:
    with the map's overlays that only a layer turned on shows ([MAP_OVERLAY_TAGS])."""
    return {n["testTag"] for n in nodes if n.get("testTag")} | MAP_OVERLAY_TAGS


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


def same_key(e, f_entry):
    """The same screen (with its font scale), kind and label."""
    return e["screen"] == f_entry["screen"] and e["kind"] == f_entry["kind"] and e["label"] == f_entry["label"]


def matches(e, f_entry, tol=MATCH_DP, tol_y=MATCH_Y_DP):
    """The same key and the same place: left, right and height within `tol`, the top within `tol_y`. T319: compare() pairs
    a label that is alone on its screen whatever this says (a place past it is `moved`); this pairs twins and unlabelled
    lines."""
    if not same_key(e, f_entry):
        return False
    a, b = e["boundsDp"], f_entry["boundsDp"]
    return abs(a[0] - b[0]) <= tol and abs(a[2] - b[2]) <= tol and abs(a[1] - b[1]) <= tol_y and \
        abs((a[3] - a[1]) - (b[3] - b[1])) <= tol


def distance(e, f_entry):
    """How far apart two lines' bounds are in dp (the sum over the four edges): the nearest pair first among twins."""
    return sum(abs(x - y) for x, y in zip(e["boundsDp"], f_entry["boundsDp"]))


def compare(baseline, entries):
    """(new, gone, matched, moved): each finding entry against the baseline's entries, one to one (T319).

    Lines are grouped by (screen, kind, label). A key that occurs once among the findings and once in the baseline is a
    match wherever the finding sits: a card that grows a row moves every line under it, and the accepted finding is still
    the same one. Where a key occurs more than once on a side (twins, the two "‹ Settings" of a settings page), the pairs
    go nearest first, a pair within the tolerances (matches()) before any pair past them. An unlabelled line (label "-")
    is matched by place only, as before T319: first a finding in order, then the first baseline line within the
    tolerances. A new label, or a kind that changed, has no line of its key: new. `matched` counts every pair; `moved`
    lists the pairs past the tolerances (each the finding with `wasDp`, the baseline line's bounds), which never make the
    part red."""
    def key(e):
        return e["screen"], e["kind"], e["label"]

    base_by, found_by = {}, {}
    for i, e in enumerate(baseline):
        base_by.setdefault(key(e), []).append(i)
    for j, f in enumerate(entries):
        found_by.setdefault(key(f), []).append(j)
    paired = {}  # finding index -> baseline index
    for k, js in found_by.items():
        is_ = base_by.get(k) or []
        if not is_:
            continue
        if k[2] == "-":
            left = list(is_)
            for j in js:
                hit = next((i for i in left if matches(baseline[i], entries[j])), None)
                if hit is not None:
                    left.remove(hit)
                    paired[j] = hit
        elif len(is_) == 1 and len(js) == 1:
            paired[js[0]] = is_[0]
        else:
            ranked = sorted((not matches(baseline[i], entries[j]), distance(baseline[i], entries[j]), j, i)
                            for i in is_ for j in js)
            used_i, used_j = set(), set()
            for _far, _d, j, i in ranked:
                if i in used_i or j in used_j:
                    continue
                used_i.add(i)
                used_j.add(j)
                paired[j] = i
    new = [f for j, f in enumerate(entries) if j not in paired]
    taken = set(paired.values())
    gone = [e for i, e in enumerate(baseline) if i not in taken]
    moved = [dict(entries[j], wasDp=list(baseline[i]["boundsDp"])) for j, i in sorted(paired.items())
             if not matches(baseline[i], entries[j])]
    return new, gone, len(paired), moved


def replay(a11y_json, baseline_path):
    """T319: a saved run (the sweep's a11y.json, the report this tool prints) against a baseline file, with no device: the
    same comparison a live run makes, from the run's non-advisory findings."""
    with open(a11y_json, encoding="utf-8") as f:
        run_report = json.load(f)
    entries = [entry_of(s["screen"], f) for s in run_report.get("screens") or [] for f in s.get("findings") or []
               if not f.get("advisory")]
    missing = not os.path.isfile(baseline_path)
    base = [] if missing else load_baseline(baseline_path)
    new, gone, matched, moved = compare(base, entries)
    return {"part": "a11y", "replay": a11y_json, "ok": not new,
            "counts": {"findings": len(entries), "entries": len(base), "matched": matched, "moved": len(moved),
                       "new": len(new), "gone": len(gone)},
            "baseline": {"file": baseline_path, "entries": len(base), "matched": matched, "new": new, "moved": moved,
                         "gone": gone, **({"missing": True} if missing else {})}}


BASELINE_ABOUT = ("T270: the accessibility part's known findings (tools/android-a11y.py, sweep part a11y). One line per "
                  "finding: screen:font scale, kind, text or description (digits as #) and bounds in dp. A finding matches the "
                  "line of its screen, kind and label wherever it sits when that label is alone there on both sides (listed as "
                  "moved past the tolerances); twins pair nearest first, and unlabelled lines (-) by place only (left, right and "
                  "height within 6 dp, the top within 48). The part is red only on a finding no line lists. A task that fixes a "
                  "node strikes its line; a new line only with the reason in the dated log.")


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
        # T335: the launch check reads only crashes since this launch: the device clock when the tool took over the app
        # (the sweep launched it), and from restart_app the clock before its force-stop (a `since` of None reads the
        # whole crash buffer, where an earlier launch's crash would be reported as this one's).
        self.since = self.device_now()

    def device_now(self):
        return debug.device_now(self.exe, self.serial)

    def reset(self, since=None):
        """The token read again at the next call; `since` (the clock before a launch) when the app was launched again."""
        self.token = None
        if since is not None:
            self.since = since

    def call(self, method, path, body=None, timeout=60.0, ctype="application/json"):
        """(status, JSON or None)."""
        for attempt in range(3):
            if self.token is None:
                _, self.token = debug.bootstrap(self.exe, self.serial, self.port, timeout=60.0, since=self.since)
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
                         ("POST", "/online/chip?open=0"), ("POST", "/help/close"), ("POST", "/gnss/ui?open=0")):
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


def scr_longpress(c, timeout=30.0):
    # T334: the card's sight line stands on the fresh fix at Paradise that close_all injects; the press waits for it
    # (`wait=`, the route's own bound), then the card is polled until the line is worked out, at most `timeout` s.
    j = c.srv.ok("POST", f"/map/longpress?lat={RAINIER_VIEW[0]}&lon={RAINIER_VIEW[1]}&wait=20000") or {}
    deadline = time.time() + timeout
    while ((j.get("sight") or {}).get("state") == "working") and time.time() < deadline:
        time.sleep(0.5)
        j = c.srv.ok("GET", "/map/longpress") or {}


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


# T300: the navigation screen read what the parts before it left. The off-route line adds the way to turn when the app has
# a heading ("Head NW 3.7 mi to rejoin (behind-right)", a line taller than the baseline's), and the app keeps one: a
# simulated heading (`/camera/heading`, `/location?bearing=`), the course between the last two fixes 5 m to 2 km apart
# (controls/Heading.kt keeps it, however old, until a jump), or the compass while facing; the device tour's replay left a
# course when a11y ran next to it, and a startup part's cold launches cleared it. The camera button reads "Heading up"
# in north-up and "North up" in facing, which persists across restarts. So the screen's setup pins all three: no
# simulated heading, north-up, and a fix 7 km off (a jump, which drops the course) before the fix at Paradise again;
# then checks the started navigation has no heading and the map is north-up, and skips the screen with the reason when not.
NAV_JUMP = (46.7500, -121.8120)  # Longmire, about 7 km from Paradise: farther than Heading.JUMP_M (2 km)
NAV_PIN_S = 6.0  # how long the started navigation may take to read the pinned state


def pin_nav_state(c):
    """What the screen found (logged: the orientation and the app's heading, `GET /camera`), then the pin."""
    o = (c.srv.ok("GET", "/camera") or {}).get("orientation") or {}
    log(f"navigation: found orientation {o.get('mode')}, heading {json.dumps(o.get('heading'))[:300]}")
    c.srv.ok("POST", "/camera/heading")
    c.srv.ok("POST", "/camera/orientation?mode=north-up")
    for lat, lon in (NAV_JUMP, PARADISE):
        c.srv.ok("POST", f"/location?lat={lat}&lon={lon}&accuracy=8")
        time.sleep(0.5)


def nav_state(c, timeout=None):
    """(heading, orientation) once navigation reads no heading and the map is north-up, else after `timeout`: the
    navigation's heading (`GET /nav` `heading`) and the map's orientation mode (`GET /camera`)."""
    deadline = time.time() + (NAV_PIN_S if timeout is None else timeout)
    while True:
        heading = (c.srv.ok("GET", "/nav") or {}).get("heading") or {}
        mode = ((c.srv.ok("GET", "/camera") or {}).get("orientation") or {}).get("mode")
        if (heading.get("deg") is None and mode == "north-up") or time.time() > deadline:
            return heading, mode
        time.sleep(0.5)


def scr_nav(c):
    pin_nav_state(c)
    c.srv.ok("POST", f"/nav/start?track={c.track_id}")
    heading, mode = nav_state(c)
    log(f"navigation: heading {json.dumps(heading)}, orientation {mode}")
    if heading.get("deg") is not None or mode != "north-up":
        raise RuntimeError(f"the pinned state did not hold: heading {json.dumps(heading)}, orientation {mode}")


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
        if group == "navigation":
            scroll_to_whole(c, STEERING_ROW_TEXTS)  # T330: the page's last row, Panorama steering, with its help and choices
    return fn


# T330
STEERING_ROW_TEXTS = ("Panorama steering", "Follow phone")


def scroll_to_whole(c, texts, swipes=14):
    """T330: swipes the open settings page up (adb input swipe on its lower half) until every text of [texts] is drawn
    whole on the screen (its node's bounds as tall as its size), at most [swipes] times; the page is a plain scrolling
    column, so the rows below the fold are in the tree but clipped to nothing."""
    for _ in range(swipes + 1):
        sem = c.srv.ok("GET", "/semantics") or {}
        screen_h = (sem.get("screenPx") or [0, 2400])[1]
        whole = set()
        for w in sem.get("windows") or []:
            for n in w.get("merged") or []:
                t = (n.get("text") or "").replace("✓ ", "")
                b, s = n.get("boundsPx") or [0, 0, 0, 0], n.get("sizePx") or [0, 0]
                if t in texts and s[1] > 0 and b[3] - b[1] >= s[1] - 1:
                    whole.add(t)
        if whole >= set(texts):
            time.sleep(0.5)
            return True
        c.dev.shell("input", "swipe", "540", str(int(screen_h * 0.85)), "540", str(int(screen_h * 0.45)), "300")
        time.sleep(0.8)
    return False
# end T330


def scr_help_drive(c):
    """T288: an open help pop-up (the list had none): Plan drives for's, over its settings page (`POST /help/open` brings the
    page up; close_all's `/help/close` closes both)."""
    c.srv.ok("POST", "/help/open?key=settings.drivePreference")


def scr_horizon(c):
    """T307, T308: the Horizon view (Settings > Navigation and tracking > Horizon view; the GPS view, screen `gps`, before
    T308), over the map, with its layers as set (all on by default): the emulator's satellites, the terrain's skyline, the
    peaks on it and the sun's path at Paradise."""
    c.srv.ok("POST", "/horizon/ui?open=1")
    time.sleep(3.0)  # the first status, the horizon's sweep and the peaks


def end_horizon(c):
    c.srv.call("POST", "/horizon/ui?open=0")


# T321
SUMMIT = (46.8529, -121.7604)  # Mount Rainier's summit (a public place)


def scr_horizon_elsewhere(c):
    """T321 (T317's follow-up): the Horizon view at a chosen point, Mount Rainier's summit, as "Sky from here" opens it
    (the amber place line with "Back to here", Satellites and Flights disabled, their note), with the first peak of its
    list picked (its line under the heading and its "View from here")."""
    c.srv.ok("POST", f"/horizon/ui?lat={SUMMIT[0]}&lon={SUMMIT[1]}&wait=90000", timeout=150.0)
    peaks = ((c.srv.ok("GET", "/horizon?wait=30000", timeout=90.0) or {}).get("peaks") or {}).get("peaks") or []
    if peaks:
        c.srv.ok("POST", "/horizon?select=" + urllib.parse.quote(peaks[0]["name"]))
    time.sleep(1.0)
# end T321


# T326
def scr_panorama(c):
    """T326: the Panorama view at the fix (Paradise), opened by the switch's route as a tap on its third segment does, its
    skyline swept to the view range and its peaks named: the plot (its spoken label and four actions), the zoom buttons, the
    pane's place line, heading, Peaks toggle and list. T351: the lens fills the window; on it Layers, List view, Follow phone
    and the zoom buttons (the heading and toggles are behind Layers: panorama-layers)."""
    c.srv.ok("POST", "/settings?aircraft=1")  # T327: Flight tracking on, so "Show:" has its four toggles
    # T330: the view in Drag (its four actions, "Follow phone" off) whatever an earlier run left; put back at the end.
    c.steering_found = (c.srv.ok("GET", "/settings") or {}).get("panoramaSteering")
    c.srv.ok("POST", "/settings?panoramaSteering=drag")
    c.srv.ok("POST", "/horizon/switch?to=panorama", timeout=30.0)
    c.srv.ok("GET", "/panorama?wait=60000", timeout=120.0)
    time.sleep(1.0)


def end_panorama(c):
    c.srv.call("POST", "/horizon/switch?to=map")
    c.srv.call("POST", "/settings?aircraft=0")  # T327
    found = getattr(c, "steering_found", None)  # T330: the steering setting as it was found
    if found:
        c.srv.call("POST", f"/settings?panoramaSteering={found}")
# end T326


# T327
def scr_panorama_flights(c):
    """T327: the Panorama view with the Flights layer on T311's replay fixture, turned to the closest flight low in the sky (dots, tails
    and names through the lens, the credit in the plot's corner). T344: the Flights list is the List view's now
    (`panorama-list`), so no scroll."""
    with open(FLIGHTS_REPLAY, "rb") as f:
        c.srv.ok("POST", "/aircraft/replay?name=a11y&undulationM=-18.4&wait=3500", body=f.read())
    c.srv.ok("POST", "/horizon?flights=1&skyRange=all")
    c.srv.ok("POST", "/horizon/switch?to=panorama", timeout=30.0)
    c.srv.ok("GET", "/panorama?wait=60000", timeout=120.0)
    time.sleep(4.0)  # a poll
    j = c.srv.ok("GET", "/panorama") or {}
    aircraft = (((j.get("layers") or {}).get("flights") or {}).get("aircraft")) or []
    low = [a for a in aircraft if 2 <= a[3] <= 30] or aircraft  # the closest low in the sky: the skyline and several flights in view
    if low:
        c.srv.ok("POST", f"/panorama?bearing={low[0][2]}")
    time.sleep(1.0)


def end_panorama_flights(c):
    c.srv.call("POST", "/horizon?flight=none")
    c.srv.call("POST", "/horizon/switch?to=map")
    c.srv.call("POST", "/aircraft/replay?stop=1&undulationM=none&wait=0")
    c.srv.call("POST", "/settings?aircraft=0")
# end T327


# T311
FLIGHTS_REPLAY = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shared", "src", "commonTest", "resources", "aircraft", "rainier-replay.json")


def scr_flights(c):
    """T311: the Flights layer on the replay fixture with the nearest aircraft's card up (its title, Close and help icon,
    its lines), over the map at the Rainier pack."""
    c.srv.ok("POST", "/camera?lat=46.86&lon=-121.45&zoom=8.6&bearing=0")
    with open(FLIGHTS_REPLAY, "rb") as f:
        c.srv.ok("POST", "/aircraft/replay?name=a11y&undulationM=-18.4&wait=3500", body=f.read())
    c.srv.ok("POST", "/aircraft?select=c0ffe1&wait=1500")


def end_flights(c):
    c.srv.call("POST", "/aircraft?select=none&wait=0")
    c.srv.call("POST", "/aircraft/replay?stop=1&undulationM=none&wait=0")
    c.srv.call("POST", "/settings?aircraft=0")
# end T311


# T312
def scr_horizon_flights(c):
    """T312: the Horizon view with the Flights layer on T311's replay fixture (dots, tails and names on the plot, its
    toggle) and the area under the heading scrolled to the Flights list (each row a button)."""
    with open(FLIGHTS_REPLAY, "rb") as f:
        c.srv.ok("POST", "/aircraft/replay?name=a11y&undulationM=-18.4&wait=3500", body=f.read())
    c.srv.ok("POST", "/horizon?flights=1&skyRange=all")
    c.srv.ok("POST", "/horizon/ui?open=1")
    time.sleep(4.0)  # the horizon's sweep, the peaks and a poll
    c.srv.ok("POST", "/horizon?scroll=450")


def end_horizon_flights(c):
    c.srv.call("POST", "/horizon?flight=none")
    c.srv.call("POST", "/horizon/ui?open=0")
    c.srv.call("POST", "/aircraft/replay?stop=1&undulationM=none&wait=0")
    c.srv.call("POST", "/settings?aircraft=0")
# end T312


def scr_share(c):
    c.srv.ok("POST", "/share/start")


def end_share(c):
    c.srv.call("POST", "/share/stop")


def restart_app(c):
    since = c.srv.device_now()  # T335: before the launch, for its launch check
    c.dev.shell("am", "force-stop", PACKAGE)
    time.sleep(1.0)
    c.dev.shell("am", "start", "-W", "-n", ACTIVITY, timeout=180)
    c.srv.reset(since)
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


# T332
OUTSIDE_VIEW = (45.8326, 6.8652, 10)  # Mont Blanc (a public area of areas.json), outside the Rainier pack


def scr_map_outside(c):
    """T332: the phone holds the Rainier pack and the view is at Mont Blanc with no online source: "No offline map covers
    this view" with "Go to my offline maps", Recentre, the scale bar and the switch kept. The online sources (the server
    and the phone's own reading of the public sources) are turned off and put back by end_map_outside."""
    s = c.srv.ok("GET", "/settings") or {}
    d = c.srv.ok("GET", "/firstrun/direct") or {}
    c.online_before = (s.get("server") or "", bool(d.get("enabled", True)))
    c.srv.ok("POST", "/settings?server=")
    c.srv.ok("POST", "/firstrun/direct?on=0")
    c.srv.ok("POST", f"/camera?lat={OUTSIDE_VIEW[0]}&lon={OUTSIDE_VIEW[1]}&zoom={OUTSIDE_VIEW[2]}&bearing=0")
    deadline = time.time() + 30
    while True:
        j = c.srv.ok("GET", "/firstrun") or {}
        if j.get("mapMessage") == "outside" and j.get("actionShown"):
            break
        if time.time() > deadline:
            raise RuntimeError(f"the outside line did not show: mapMessage {j.get('mapMessage')}, mapHolds {json.dumps(j.get('mapHolds'))}")
        time.sleep(0.5)
    time.sleep(1.0)


def end_map_outside(c):
    server, direct = getattr(c, "online_before", ("", True))
    c.srv.call("POST", "/settings?server=" + urllib.parse.quote(server, safe=""))
    c.srv.call("POST", f"/firstrun/direct?on={1 if direct else 0}")
    c.srv.call("POST", f"/camera?lat={RAINIER_VIEW[0]}&lon={RAINIER_VIEW[1]}&zoom={RAINIER_VIEW[2]}")
# end T332


# T333
WEAK_HOLD_S = 10  # the weak run the screen asks for in place of five minutes (POST /tracks/weak?holdS=)


def scr_recording_weak(c):
    """T333: a recording at Paradise under the simulated `fault` sky (every satellite weak, many above the rim) with the
    watch's five minutes shortened to WEAK_HOLD_S: the recording panel unfolded with "GPS signal unusually weak", its tap
    and its help icon. State it leaves, each put back by end_recording_weak (also after a failed step): the recording
    (stopped and deleted), the injected sky (`POST /gnss?clear=1`) and the shortened time (`holdS=default`). Fixes are
    injected while it waits, so the state line never reads "Waiting for GPS"."""
    c.srv.ok("POST", "/gnss?case=fault")
    c.srv.ok("POST", f"/tracks/weak?holdS={WEAK_HOLD_S}")
    j = c.srv.ok("POST", "/tracks/record?source=injected&activity=foot")
    c.recorded = ((j or {}).get("started") or {}).get("id")
    c.srv.ok("POST", "/tracks/ui?bar=1")
    deadline = time.time() + 60
    k = 0
    while True:
        c.srv.ok("POST", f"/location?lat={PARADISE[0] + k * 0.00003:.6f}&lon={PARADISE[1]}&accuracy=5")
        k += 1
        w = c.srv.ok("GET", "/tracks/weak") or {}
        if w.get("shown"):
            break
        if time.time() > deadline:
            raise RuntimeError(f"the weak-signal line did not show: {json.dumps(w)[:300]}")
        time.sleep(2.0)
    time.sleep(1.0)


def end_recording_weak(c):
    code, j = c.srv.call("POST", "/tracks/stop")
    tid = c.recorded or ((j or {}).get("track") or {}).get("id") or (j or {}).get("id")
    if tid:
        c.srv.call("DELETE", f"/tracks/{tid}")
    c.recorded = None
    c.srv.call("POST", "/tracks/weak?holdS=default")
    c.srv.call("POST", "/gnss?clear=1")
# end T333


# T331
# The Panorama following the phone with everything Follow adds on screen: a simulated attitude (true bearing 60, 20
# degrees down, rolled 25) so nothing depends on the emulator's sensor; Mount Rainier the target, off the view to the
# left (the arrow a sign, no edge and no tap); a heading correction of 4 degrees (its line and Reset at the lower left);
# a simulated accuracy status of 1 (low), so the weak-compass line stands under the plot after its 2 s. Flight tracking
# is left as it is. Each pin is put back by end_panorama_attitude, also after a failed setup (the steering setting as
# found, the simulated attitude and status cleared, the target and the correction let go).
PANORAMA_ATTITUDE = "60,-20,25"


def scr_panorama_attitude(c):
    c.attitude_steering_found = (c.srv.ok("GET", "/settings") or {}).get("panoramaSteering")
    c.srv.ok("POST", "/settings?panoramaSteering=follow")
    c.srv.ok("POST", "/horizon/switch?to=panorama", timeout=30.0)
    c.srv.ok("GET", "/panorama?wait=60000", timeout=120.0)
    c.srv.ok("POST", f"/panorama?attitude={PANORAMA_ATTITUDE}&attitudeNoise=0")
    c.srv.ok("POST", "/panorama?target=" + urllib.parse.quote("peak:Mount Rainier"))
    c.srv.ok("POST", "/panorama?correction=4&compassStatus=1")
    deadline = time.time() + 10
    while True:
        j = c.srv.ok("GET", "/panorama") or {}
        if (j.get("hint") or {}).get("shown") and j.get("arrow") and j.get("correctionDeg") is not None:
            break
        if time.time() > deadline:
            raise RuntimeError(f"Follow's screen did not stand: hint {json.dumps(j.get('hint'))}, arrow {json.dumps(j.get('arrow'))}, correction {j.get('correctionDeg')}")
        time.sleep(0.5)
    time.sleep(1.0)


def end_panorama_attitude(c):
    c.srv.call("POST", "/panorama?compassStatus=none&correction=none&target=none&attitude=none")
    c.srv.call("POST", "/horizon/switch?to=map")
    found = getattr(c, "attitude_steering_found", None)
    c.srv.call("POST", f"/settings?panoramaSteering={found or 'drag'}")
# end T331


# T340
def scr_panorama_elsewhere(c):
    """T340: the Panorama at a chosen point, Mount Rainier's summit, as "Sky from here" and then the switch's Panorama open
    it (the amber place line with "Back to here", the terrain line naming what answered: T340's merged read), its skyline
    and peaks drawn."""
    c.elsewhere_steering_found = (c.srv.ok("GET", "/settings") or {}).get("panoramaSteering")
    c.srv.ok("POST", "/settings?panoramaSteering=drag")
    c.srv.ok("POST", f"/horizon/ui?lat={SUMMIT[0]}&lon={SUMMIT[1]}&wait=90000", timeout=150.0)
    c.srv.ok("POST", "/horizon/switch?to=panorama", timeout=30.0)
    c.srv.ok("GET", "/panorama?wait=90000", timeout=150.0)
    time.sleep(1.0)


def end_panorama_elsewhere(c):
    c.srv.call("POST", "/horizon/switch?to=map")
    c.srv.call("POST", "/horizon/ui?open=0")
    found = getattr(c, "elsewhere_steering_found", None)
    if found:
        c.srv.call("POST", f"/settings?panoramaSteering={found}")
# end T340


# T342
def scr_offline_maps_off(c):
    """T342: the Offline maps list with a map switched off: the first row with a "Use on this phone" switch turned off
    (`POST /areas/rows/off`), so the row draws the switch off and its line "Off: not used. ..."; put back on by
    end_offline_maps_off, also after a failed setup."""
    rows = (c.srv.ok("GET", "/areas/rows") or {}).get("rows") or []
    row = next((r for r in rows if r.get("off") is not None), None)
    if row is None:
        raise RuntimeError("no row with a \"Use on this phone\" switch (no map on the phone)")
    c.offline_off_id = row["id"]
    c.srv.ok("POST", "/areas/rows/off?off=1&id=" + urllib.parse.quote(row["id"]), timeout=60.0)
    c.srv.ok("POST", "/packs/ui?show=true")
    time.sleep(2.0)  # the list's poll reads the rows again


def end_offline_maps_off(c):
    rid = getattr(c, "offline_off_id", None)
    if rid:
        c.srv.call("POST", "/areas/rows/off?off=0&id=" + urllib.parse.quote(rid))
    c.srv.call("POST", "/packs/ui?show=false")
# end T342


# T348
STALE_AGE_H = 72  # the age the screen gives the recording's last fix (POST /tracks/stale?lastFixAgeH=)


def scr_recording_stale(c):
    """T348: a recording at Paradise whose last fix reads three days old (the debug age, held against the fixes the part injects; no clock moved): the question
    card at the bottom ("This recording stopped getting location updates 3 days ago. ...", Finish it, Keep recording) and
    the recording panel unfolded with the turn-back chip on, whose card says "No turn-back time" and how old the last
    location update is. State it leaves, put back by end_recording_stale (also after a failed step): the recording
    (stopped and deleted; its end forgets the debug age)."""
    c.srv.ok("POST", f"/location?lat={PARADISE[0]}&lon={PARADISE[1]}&accuracy=8")
    j = c.srv.ok("POST", "/tracks/record?source=injected&activity=foot")
    c.recorded = ((j or {}).get("started") or {}).get("id")
    c.srv.ok("POST", f"/location?lat={PARADISE[0] + 0.0002:.6f}&lon={PARADISE[1]}&accuracy=5")
    c.srv.ok("POST", f"/tracks/stale?lastFixAgeH={STALE_AGE_H}&hold=1")
    c.srv.ok("POST", "/tracks/wayback?on=1")
    c.srv.ok("POST", "/tracks/ui?bar=1")
    deadline = time.time() + 30
    while True:
        s = c.srv.ok("GET", "/tracks/stale") or {}
        if s.get("shown"):
            break
        if time.time() > deadline:
            raise RuntimeError(f"the stale recording's question did not show: {json.dumps(s)[:300]}")
        time.sleep(1.0)
    time.sleep(1.0)


def end_recording_stale(c):
    code, j = c.srv.call("POST", "/tracks/stop")
    tid = c.recorded or ((j or {}).get("track") or {}).get("id") or (j or {}).get("id")
    if tid:
        c.srv.call("DELETE", f"/tracks/{tid}")
    c.recorded = None
# end T348


# T343
def scr_download_card(c):
    """T343: the Download card ("Download this area", `POST /areas/region`) over the view at Mount Rainier's summit at z13: the
    card opens with what the phone holds there (its first line, `GET /areas/picker` `heldLine`) and, where the phone holds every
    tile at the detail shown, the line in place of Download (`nothingNew`); closed by end_download_card. The readout strip
    under the card is one overlap line of the baseline: its climb is pinned to no reading (`POST /readout/reset`, then a fresh
    fix at Paradise), since a climb left by an earlier screen's fixes ("climb ↓1.2k ft/h") keyed apart; the pressure keys alike
    with a reading or without ("pressure – mbar", key_label)."""
    c.srv.ok("POST", "/readout/reset")
    c.srv.ok("POST", f"/location?lat={PARADISE[0]}&lon={PARADISE[1]}&accuracy=8")
    c.srv.ok("POST", f"/camera?lat={SUMMIT[0]}&lon={SUMMIT[1]}&zoom=13&bearing=0")
    time.sleep(2.0)
    c.srv.ok("POST", "/areas/region?wait=60000", timeout=120.0)
    time.sleep(1.5)


def end_download_card(c):
    c.srv.call("POST", "/areas/picker/close")
    c.srv.call("POST", f"/camera?lat={RAINIER_VIEW[0]}&lon={RAINIER_VIEW[1]}&zoom={RAINIER_VIEW[2]}")
# end T343


# T344
def scr_panorama_list(c):
    """T344: the List view, the switch's fourth segment, at the fix (Paradise) with the Flights layer on T311's replay
    fixture, opened by the switch's route as a tap on its segment does: the place line, "List view" with its help, "Show:"
    and the four toggles, the terrain line, the Flights list (each row a button), the Peaks list (each row a button), Sun
    and Satellites below. State it leaves, put back by end_panorama_list: the replay and Flight tracking (as
    end_panorama_flights puts them back)."""
    with open(FLIGHTS_REPLAY, "rb") as f:
        c.srv.ok("POST", "/aircraft/replay?name=a11y&undulationM=-18.4&wait=3500", body=f.read())
    c.srv.ok("POST", "/horizon?flights=1&skyRange=all")
    c.srv.ok("POST", "/horizon/switch?to=list", timeout=30.0)
    c.srv.ok("GET", "/skylist?wait=60000", timeout=120.0)
    time.sleep(4.0)  # a poll


def end_panorama_list(c):
    end_panorama_flights(c)
# end T344


# T351
def scr_panorama_layers(c):
    """T351: the Panorama view at the fix (Paradise) with the layers' panel open (POST /panorama?layers=1, as a tap on
    "Layers" does): the heading "Panorama view" with its help, "Show:" and the toggles (Satellites, Peaks, Sun, and
    Flights with Flight tracking on) over the lens, the lens's own controls under the panel. State it leaves, put back by
    end_panorama_layers: the panel closed, Flight tracking off, the steering setting as found."""
    c.srv.ok("POST", "/settings?aircraft=1")  # Flight tracking on, so the panel has its four toggles
    c.layers_steering_found = (c.srv.ok("GET", "/settings") or {}).get("panoramaSteering")
    c.srv.ok("POST", "/settings?panoramaSteering=drag")
    c.srv.ok("POST", "/horizon/switch?to=panorama", timeout=30.0)
    c.srv.ok("GET", "/panorama?wait=60000", timeout=120.0)
    c.srv.ok("POST", "/panorama?layers=1")
    time.sleep(1.0)


def end_panorama_layers(c):
    c.srv.call("POST", "/panorama?layers=0")
    end_panorama(c)
    found = getattr(c, "layers_steering_found", None)
    if found:
        c.srv.call("POST", f"/settings?panoramaSteering={found}")
# end T351


# T363
def readout_gap(c, jump=False):
    """T363: the readout bar in states that are not plain figures: the barometer set to Never (else the emulator's fixed
    sea-level pressure takes over the height 10 s after the last fix), the readout reset, four fixes at Paradise a second
    apart, [jump] then a network fix 3 km north with no height and one back, then 8 s with none: the values dimmed with
    "no fix 8 s" and the bar's "?" marks. snapshot()'s fresh fix just before the frame makes it "resuming" (still dimmed,
    still marked: the dim leaves on the second fix of a run). The barometer's setting as found is put back by
    end_readout_states."""
    c.readout_baro_found = (c.srv.ok("GET", "/settings") or {}).get("readoutBarometer")
    c.srv.ok("POST", "/settings?readoutBarometer=never")
    c.srv.ok("POST", "/readout/reset")
    for k in range(4):
        c.srv.ok("POST", f"/location?lat={PARADISE[0] + k * 0.00002:.6f}&lon={PARADISE[1]}&accuracy=8&ele=1650")
        time.sleep(1.0)
    if jump:
        c.srv.ok("POST", f"/location?lat={PARADISE[0] + 0.027:.6f}&lon={PARADISE[1]}&accuracy=40&provider=network")
        time.sleep(1.0)
        c.srv.ok("POST", f"/location?lat={PARADISE[0]:.6f}&lon={PARADISE[1]}&accuracy=8&ele=1650")
    time.sleep(8.0)


def scr_readout_states(c):
    """T363: the map with the readout bar dimmed in a gap of the fixes, its values marked with the small "?"."""
    readout_gap(c)
    j = c.srv.ok("GET", "/readout") or {}
    if not any(f.get("mark") for f in ((j.get("bar") or {}).get("fields") or [])):
        raise RuntimeError(f"no value marked: {json.dumps((j.get('bar') or {}).get('fields'))[:300]}")


def end_readout_states(c):
    c.srv.call("POST", "/readout/glance?open=0")
    found = getattr(c, "readout_baro_found", None)
    c.srv.call("POST", f"/settings?readoutBarometer={found or 'auto'}")


def scr_glance(c):
    """T363: the glance view (POST /readout/glance?open=1, as a tap on the bar opens it) after a jump and an 8 s gap that a
    fix ended: a line under each value that is not a plain figure (the climb's window filling after the reset) and "In the
    last minute" with the gap and the jumps. The fix that ends the gap comes before the view opens, so snapshot()'s fresh
    fix (the second of the run: the dim goes) changes no line's count between the frame and the semantics."""
    readout_gap(c, jump=True)
    c.srv.ok("POST", f"/location?lat={PARADISE[0]:.6f}&lon={PARADISE[1]}&accuracy=8&ele=1650")
    time.sleep(1.5)
    c.srv.ok("POST", "/readout/glance?open=1")
    time.sleep(1.0)
# end T363


# T364
def scr_map_flights_hidden(c):
    """T364: the map with Flight tracking on and "Show flights" off (POST /aircraft?map=0, as its row in Map options sets
    it) on T311's replay fixture: no aircraft, no flights status line, and the map asks for none (checked)."""
    c.srv.ok("POST", "/camera?lat=46.86&lon=-121.45&zoom=8.6&bearing=0")
    with open(FLIGHTS_REPLAY, "rb") as f:
        c.srv.ok("POST", "/aircraft/replay?name=a11y&undulationM=-18.4&wait=3500", body=f.read())
    j = c.srv.ok("POST", "/aircraft?map=0&wait=2500") or {}
    if j.get("mapAsksBecause") != "hidden" or j.get("count"):
        raise RuntimeError(f"the map still shows flights: mapAsksBecause {j.get('mapAsksBecause')}, count {j.get('count')}")


def end_map_flights_hidden(c):
    c.srv.call("POST", "/aircraft?map=1&wait=0")
    c.srv.call("POST", "/aircraft/replay?stop=1&undulationM=none&wait=0")
    c.srv.call("POST", "/settings?aircraft=0")
# end T364


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
    ("help-drive", scr_help_drive, None, "screen"),
    ("horizon", scr_horizon, end_horizon, "screen"),
    ("share", scr_share, end_share, "screen"),
    ("first-run", scr_first_run, end_first_run, "map"),
    # T311
    ("flights-card", scr_flights, end_flights, "map"),
    # end T311
    # T312
    ("horizon-flights", scr_horizon_flights, end_horizon_flights, "screen"),
    # end T312
    ("horizon-elsewhere", scr_horizon_elsewhere, end_horizon, "screen"),  # T321
    ("panorama", scr_panorama, end_panorama, "screen"),  # T326
    ("panorama-flights", scr_panorama_flights, end_panorama_flights, "screen"),  # T327
    # T332
    ("map-outside", scr_map_outside, end_map_outside, "map"),
    # end T332
    # T333
    ("recording-weak", scr_recording_weak, end_recording_weak, "map"),
    # end T333
    # T331
    ("panorama-attitude", scr_panorama_attitude, end_panorama_attitude, "screen"),
    # end T331
    # T340
    ("panorama-elsewhere", scr_panorama_elsewhere, end_panorama_elsewhere, "screen"),
    # end T340
    # T342
    ("offline-maps-off", scr_offline_maps_off, end_offline_maps_off, "screen"),
    # end T342
    # T348
    ("recording-stale", scr_recording_stale, end_recording_stale, "map"),
    # end T348
    # T343
    ("download-card", scr_download_card, end_download_card, "map"),
    # end T343
    # T344
    ("panorama-list", scr_panorama_list, end_panorama_list, "screen"),
    # end T344
    # T351
    ("panorama-layers", scr_panorama_layers, end_panorama_layers, "screen"),
    # end T351
    # T363
    ("readout-states", scr_readout_states, end_readout_states, "map"),
    ("glance", scr_glance, end_readout_states, "screen"),
    # end T363
    # T364
    ("map-flights-hidden", scr_map_flights_hidden, end_map_flights_hidden, "map"),
    # end T364
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
                    dev.place(path)  # T295: the frame's sidecar, the area GET /camera shows (a name, never a coordinate)
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
        new, gone, matched, moved = compare(base, entries)
        report["baseline"] = {"file": args.baseline, "entries": len(base), "matched": matched, "new": new, "moved": moved,
                              "gone": gone, **({"missing": True} if missing else {})}
        report["counts"] = {"new": len(new), "moved": len(moved), "gone": len(gone), "matched": matched}
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
    ap.add_argument("--serial", help="the emulator or phone (required except with --replay)")
    ap.add_argument("--port", type=int, help="host port forwarded to the app's debug server (required except with --replay)")
    ap.add_argument("--replay", default="", help="T319: a saved run's report (the sweep's a11y.json) compared with --baseline, "
                    "no device")
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
    if args.replay:
        if not args.baseline:
            ap.error("--replay needs --baseline")
        try:
            report = replay(args.replay, args.baseline)
        except (OSError, ValueError) as e:
            print(json.dumps({"part": "a11y", "ok": False, "error": f"{type(e).__name__}: {e}"}))
            sys.exit(2)
        print(json.dumps(report, indent=1))
        sys.exit(0 if report["ok"] else 1)
    if not args.serial or args.port is None:
        ap.error("--serial and --port are required (except with --replay)")
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
