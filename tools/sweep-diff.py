"""The before-and-after sheet (T259): every screenshot of a sweep against the previous sweep's, by name.

    python -I tools/sweep-diff.py <previous sweep folder> <this sweep folder> [--first <screen names>]
        [--leave-out <names>] [--leave-unknown] [--channel chrome|""] [--frac 0.004] [--pixel 40] [--width 270]

Pairs each PNG of the two folders by its path relative to the folder (the device tour's views, the ui/<part>/ sets of
the release parts, first-run and its direct run, the release smoke views, the world tour's renders, the demo and live
screenshots). Composites and server data are left out: sheet.png, changes*.png, the tour contact sheets
(*-sheet.png, tour/contact-sheet.png) and anything under an `out/` folder (the sweep servers' sprites). T289: the route
sheet's pictures (`route-sheet/`) and its own sheet (`route-changes*.png`) are left out in both folders too: they have
their own sheet (tools/route-diff.py), so here they are neither paired nor added nor removed, and no --first name
matches them.

The tolerance for timing (two sweeps of the same build differ by the tiles that had landed, a label placed a frame
later, the clock): both images are drawn at --width pixels wide (a 1080 px screenshot shrinks four times, an average
over 4 by 4 pixels) and blurred by one pixel; a pixel differs when a colour channel moved by more than --pixel (of
255); a pair is changed when the fraction of differing pixels passes --frac. Two exclusions by name:
- the clock: the status bar (the top STATUS_BAR of the height) of the device screenshots (device/, ui/, first-run/,
  release/) is masked, because the full-screen captures show the clock and the notification icons there;
- the camera in flight: a shot whose name ends in `-first` (01-map-first, 10-import-map-first, ...) is taken by the
  tools before the map settles, on purpose; it is compared and listed, but as `timing`, never counted as changed.
Pairs of different sizes are changed (fraction 1). Shots in only one folder are listed as added or removed.

T295, the place a device frame shows (DEVICE_PREFIXES: device/, ui/, first-run/, release/, a11y/; the renders, demo and
live frames are of public areas by construction and are not checked). Each device capture writes a sidecar beside
it, <frame>.place.json: {"area": <the areas.json area whose bbox holds the viewed centre> | "outside" | "unknown",
"basis": "camera" | "fix" | "none"}; a camera at zoom 6 or below names the first area within half a phone's width of
its centre, since such a frame spans hundreds of kilometres (tools/android-check.py's area_of and write_place, used by android-check.py itself and by
Device.screencap and Device.save in android-firstrun.py, so by android-ui.py and android-a11y.py too; android-release.ps1
has its own Write-Place). Only a name is written, never a coordinate. camera: the debug build's GET /camera. fix: a
place the part set and did not read back (the last `adb emu geo fix` it sent, else the phone's last GPS location;
android-release.ps1's deep link): a fix says where the phone is, not where the map looks, so a release frame of a map
moved off the fix (the drive step's other pack, a search, a pan) reads the fix's area and proves nothing about the map.
none: neither answered (the area reads unknown).
Each side of a device pair reads public (an area's name), outside, or unknown (no sidecar, as in a sweep from before
T295, or a sidecar reading unknown). A pair, or an added or removed shot, is left out (neither compared nor shown, nor
listed among the changed, timing, added or removed) when:
- a side reads outside (always; what is printed is "outside the public areas", never a place);
- --leave-out names it (comma-separated, matched against either side's path as --first is);
- --leave-unknown is given and a side reads unknown.
A device pair with an unknown side that is kept is shown and listed under `unknown`. Before changes.png is written,
every frame on it is read again: an outside frame there (which the rules above leave out) refuses the sheet (exit 2,
no changes.png). tools/sweep.ps1 passes the flags through -DiffArgs ('--leave-unknown --leave-out a,b').

Writes into <this sweep folder>:
- changes.png: the changed pairs side by side (before, after, and the after with the differing pixels in red),
  then the added shots, and nothing else; the pairs named by --first (screen names the merge bodies flag for the
  owner, matched case-insensitively against the path, `-` and space alike) come first and are marked, and so do the
  added shots a name matches, first among the added (T288: a wave's own new step has no namesake before);
- changes.json: the count, the thresholds, every changed pair with its fraction, the timing pairs, the added and
  removed shots, every pair's fraction (`fractions`), T295's `leftOut` (each: path, `was` when renumbered, why:
  outside, named or unknown, the `sides` that read so, the `names` that matched) and `unknown` (the kept pairs with a
  side unknown, and those `sides`), `leaveOutMissing` (a --leave-out name that matched no frame) and `places` (this
  sweep's device frames counted by area and by basis), and T288's --first outcome, each name matched against every
  frame of both folders: `firstMissing` (matches no frame at all), `firstUnchanged` (matches frames, none changed or
  new), `firstAdded` (the new shots a name matches);
and, when summary.json is there, its `changes` entry (sweep-summary.py's `changes()` reads the same file, so a
rerun of the summary keeps it, `leftOut` and `unknown` among its lists; the three --first lists are added to it here) and the diff's own part `diff` there.
T288, the red: a --first name that matches no frame is printed and, after changes.json and the sheet are written,
the exit is 1; summary.json then lists `diff` under `failed` with `ok` false (tools/sweep.ps1 appends the diff's line
to steps.tsv and takes its exit code into its own). A rerun with corrected names clears both: the summary's entry,
and the `diff` line of steps.tsv when there is one. The image work runs in Playwright's Chromium canvas (the machine
has no Pillow; Playwright is already needed by the sheet). Prints changes.json's summary; exit 0 whatever changed,
1 on a --first name that matched nothing, 2 on an error (also written into summary.json as a failed `diff`).
"""
import argparse
import fnmatch
import html
import importlib.util
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ORIGIN = "http://sweep-diff.invalid"

# The share of the height masked at the top of a device screenshot: Android's status bar is 24 dp, 63 px of 2400 on
# the sweep's 1080 x 2400 phone (2.6%); landscape is 63 of 1080 (5.8%), so the mask is the larger of the two shares
# applied per orientation.
STATUS_BAR = {"portrait": 0.03, "landscape": 0.06}
DEVICE_PREFIXES = ("device/", "ui/", "first-run/", "release/", "a11y/")
PLACE_SUFFIX = ".place.json"  # T295: tools/android-check.py's sidecar beside a device frame
SKIP = ("sheet.png", "changes.png", "changes-*.png", "*-sheet.png", "tour/contact-sheet.png", "route-changes*.png")
# T111's capture limit: Chrome's full-page capture is at most 16,384 px tall.
SHEET_MAX_PX = 16000
DEFAULTS = {"width": 270, "pixel": 40, "frac": 0.004}


def norm(rel):
    return rel.replace(os.sep, "/")


def shots(d):
    """Relative paths (with /) of the PNGs worth pairing in a sweep folder."""
    out = []
    for root, dirs, files in os.walk(d):
        rel_root = norm(os.path.relpath(root, d))
        parts = [] if rel_root == "." else rel_root.split("/")
        if "out" in parts or "server-out" in parts:  # the sweep servers' data (sprites)
            continue
        if parts and parts[0] == "route-sheet":  # T289: the route pictures have their own sheet (route-diff.py)
            continue
        for f in files:
            if not f.lower().endswith(".png"):
                continue
            rel = f if not parts else rel_root + "/" + f
            if any(fnmatch.fnmatch(rel, p) or fnmatch.fnmatch(f, p) for p in SKIP):
                continue
            out.append(rel)
    return sorted(out, key=natural)


def natural(s):
    return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", s)]


def is_device(rel):
    return rel.startswith(DEVICE_PREFIXES)


def read_place(folder, rel):
    """T295: what a frame's sidecar says, (area, basis): area an areas.json name, "outside" or "unknown" (also for no
    sidecar or one that cannot be read)."""
    try:
        with open(os.path.join(folder, os.path.splitext(rel)[0] + PLACE_SUFFIX), encoding="utf-8") as f:
            p = json.load(f)
    except (OSError, ValueError):
        return "unknown", None
    if not isinstance(p, dict):
        return "unknown", None
    area = p.get("area")
    return (area if isinstance(area, str) and area else "unknown"), p.get("basis")


def side_kind(area):
    return area if area in ("outside", "unknown") else "public"


def place_filter(prev, this, paired, added, removed, leave_out=(), leave_unknown=False):
    """T295: ((pairs, added, removed) kept for the sheet, leftOut, unknown, the --leave-out names that matched nothing).
    Each device side reads its sidecar; a frame outside DEVICE_PREFIXES is not read (left out only by name)."""
    def sides(was, rel):
        out = {}
        if was is not None and is_device(was):
            out["before"] = side_kind(read_place(prev, was)[0])
        if rel is not None and is_device(rel):
            out["after"] = side_kind(read_place(this, rel)[0])
        return out

    left, unknown, kept = [], [], ([], [], [])
    seen = set()
    items = [(a, b) for a, b in paired] + [(None, r) for r in added] + [(r, None) for r in removed]
    for was, rel in items:
        path = rel if rel is not None else was
        s = sides(was, rel)
        named = set(matches_first(path, leave_out))
        if was is not None and was != path:
            named.update(matches_first(was, leave_out))
        seen |= named
        why, which = None, []
        if any(v == "outside" for v in s.values()):
            why, which = "outside", [k for k, v in s.items() if v == "outside"]
        elif named:
            why = "named"
        elif leave_unknown and any(v == "unknown" for v in s.values()):
            why, which = "unknown", [k for k, v in s.items() if v == "unknown"]
        renamed = {"was": was} if was is not None and rel is not None and was != rel else {}
        if why:
            left.append({"path": path, **renamed, "why": why, **({"sides": which} if which else {}),
                         **({"names": sorted(named)} if named else {})})
            continue
        unk = [k for k, v in s.items() if v == "unknown"]
        if unk:
            unknown.append({"path": path, **renamed, "sides": unk})
        if was is not None and rel is not None:
            kept[0].append((was, rel))
        elif rel is not None:
            kept[1].append(rel)
        else:
            kept[2].append(was)
    return kept, left, unknown, [n for n in leave_out if n not in seen]


def place_counts(this, b_shots):
    """T295: this sweep's device frames counted by the area their sidecars name (outside is a count, never a place) and
    by basis (`none` also for a frame with no sidecar)."""
    areas, basis = {}, {}
    for r in b_shots:
        if is_device(r):
            area, b = read_place(this, r)
            areas[area] = areas.get(area, 0) + 1
            basis[b or "none"] = basis.get(b or "none", 0) + 1
    return {"areas": dict(sorted(areas.items())), "basis": dict(sorted(basis.items()))}


class OutsideOnSheet(RuntimeError):
    pass


def in_flight(rel):
    """The tools take these before the map settles (the camera in flight, tiles landing)."""
    return os.path.splitext(os.path.basename(rel))[0].endswith("-first")


def matches_first(rel, names):
    key = rel.lower().replace(" ", "-")
    return [n for n in names if n and n.lower().replace(" ", "-") in key]


def first_outcome(first, a_shots, b_shots, changed, added):
    """T288: what each --first name met, matched against every frame of both sweeps (not only the changed pairs):
    `missing`, the names that match no frame at all (the sweep turns red: the name is wrong or no step opens the screen);
    `unchanged`, the names that match frames but none changed or new (a pair alike, a timing pair, a removed shot);
    `added`, the new shots a name matches (the case of a wave's own new step: they go first among the added)."""
    every = set(a_shots) | set(b_shots)
    shown = {c["path"] for c in changed} | set(added)
    missing, unchanged = [], []
    for n in first:
        hits = [r for r in every if matches_first(r, [n])]
        if not hits:
            missing.append(n)
        elif not any(r in shown for r in hits):
            unchanged.append(n)
    return {"missing": missing, "unchanged": unchanged, "added": [r for r in added if matches_first(r, first)]}


def first_order(first):
    """The sort key that puts the paths a --first name matches first, in --first order, and the rest after in theirs."""
    order = {n.lower(): i for i, n in enumerate(first)}

    def key(hits):
        return min((order[h.lower()] for h in hits), default=len(order))
    return key


# Runs in the page: both images drawn at `width` and blurred, the masked top band left out, the fraction of pixels
# whose largest channel difference passes `pixel`; the heat image (the after, grey and pale, the differing pixels red)
# as a data URL when asked.
COMPARE_JS = """
async ({a, b, width, pixel, mask, heat}) => {
  const load = async (u) => { const r = await fetch(u); if (!r.ok) throw new Error(u + ': ' + r.status); return await createImageBitmap(await r.blob()); };
  const [ia, ib] = await Promise.all([load(a), load(b)]);
  const size = {a: [ia.width, ia.height], b: [ib.width, ib.height]};
  if (ia.width !== ib.width || ia.height !== ib.height) return {size, sizeChanged: true, fraction: 1};
  const w = Math.min(width, ia.width), h = Math.max(1, Math.round(ia.height * w / ia.width));
  const draw = (img) => {
    const c = new OffscreenCanvas(w, h), x = c.getContext('2d', {willReadFrequently: true});
    x.imageSmoothingEnabled = true; x.imageSmoothingQuality = 'high'; x.filter = 'blur(1px)';
    x.drawImage(img, 0, 0, w, h);
    return x.getImageData(0, 0, w, h).data;
  };
  const pa = draw(ia), pb = draw(ib);
  const maskTop = mask ? (ia.height >= ia.width ? mask.portrait : mask.landscape) : 0;
  const top = Math.round(h * maskTop);
  let diff = 0, n = 0;
  const hc = heat ? new OffscreenCanvas(w, h) : null;
  const hx = hc ? hc.getContext('2d') : null;
  if (hx) { hx.drawImage(ib, 0, 0, w, h); }
  const hd = hx ? hx.getImageData(0, 0, w, h) : null;
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4;
      const d = Math.max(Math.abs(pa[i] - pb[i]), Math.abs(pa[i + 1] - pb[i + 1]), Math.abs(pa[i + 2] - pb[i + 2]));
      const masked = y < top;
      if (!masked) { n++; if (d > pixel) diff++; }
      if (hd) {
        const g = 170 + 0.33 * (0.3 * hd.data[i] + 0.59 * hd.data[i + 1] + 0.11 * hd.data[i + 2]);
        if (masked) { hd.data[i] = hd.data[i + 1] = hd.data[i + 2] = 235; }
        else if (d > pixel) { hd.data[i] = 230; hd.data[i + 1] = 20; hd.data[i + 2] = 20; }
        else { hd.data[i] = hd.data[i + 1] = hd.data[i + 2] = g; }
        hd.data[i + 3] = 255;
      }
    }
  }
  const out = {size, fraction: n ? diff / n : 0, compared: [w, h], maskTop};
  if (hd && heat) {
    hx.putImageData(hd, 0, 0);
    const blob = await hc.convertToBlob({type: 'image/png'});
    const buf = new Uint8Array(await blob.arrayBuffer());
    let s = ''; for (let i = 0; i < buf.length; i += 8192) s += String.fromCharCode.apply(null, buf.subarray(i, i + 8192));
    out.heat = 'data:image/png;base64,' + btoa(s);
  }
  return out;
}
"""


def compare(page, before, after, width, pixel, heat=False):
    """The pair's fraction of differing pixels (and the heat image as a data URL when asked); the status bar masked
    on device screenshots."""
    return page.evaluate(COMPARE_JS, {"a": f"{ORIGIN}/a/{before}", "b": f"{ORIGIN}/b/{after}", "width": width, "pixel": pixel,
                                      "mask": STATUS_BAR if is_device(after) else None, "heat": heat})


def stem(rel):
    """The path with a step number dropped from the file name (ui/features/03-map.png -> ui/features/map.png)."""
    head, name = os.path.split(rel)
    return (head + "/" if head else "") + re.sub(r"^\d+-", "", name)


def pair_up(a_shots, b_shots):
    """(before, after) pairs by relative path; then, for the shots left over, by the path without the step number
    when that is unique on both sides (a step added or dropped renumbers every later shot of a part)."""
    a_set = set(a_shots)
    pairs = [(r, r) for r in b_shots if r in a_set]
    done = {r for r, _ in pairs}
    a_left = [r for r in a_shots if r not in done]
    b_left = [r for r in b_shots if r not in done]
    a_by, b_by = {}, {}
    for r in a_left:
        a_by.setdefault(stem(r), []).append(r)
    for r in b_left:
        b_by.setdefault(stem(r), []).append(r)
    for k, bs in b_by.items():
        if len(bs) == 1 and len(a_by.get(k, [])) == 1:
            pairs.append((a_by[k][0], bs[0]))
    used_a = {a for a, _ in pairs}
    used_b = {b for _, b in pairs}
    pairs.sort(key=lambda p: natural(p[1]))
    return pairs, [r for r in b_shots if r not in used_b], [r for r in a_shots if r not in used_a]


def sheet_html(prev_name, this_name, changed, added, heats, per_row, first_added=(), first_missing=()):
    cards = []
    for c in changed:
        rel = c["path"]
        was = c.get("was", rel)
        flag = " <b class='own'>for the owner</b>" if c.get("first") else ""
        what = "size changed" if c.get("sizeChanged") else f"{c['fraction'] * 100:.1f}% differs"
        hsrc = heats.get(rel)
        cards.append(f"<div class='card'><div class='cap'>{html.escape(rel)}{' (was ' + html.escape(os.path.basename(was)) + ')' if was != rel else ''}: {what}{flag}</div><div class='row'>"
                     f"<figure><img src='a/{html.escape(was)}'><figcaption>before</figcaption></figure>"
                     f"<figure><img src='b/{html.escape(rel)}'><figcaption>after</figcaption></figure>"
                     + (f"<figure><img src='{hsrc}'><figcaption>differs (red)</figcaption></figure>" if hsrc else "")
                     + "</div></div>")
    for rel in added:
        flag = " <b class='own'>for the owner</b>" if rel in first_added else ""
        cards.append(f"<div class='card'><div class='cap'>{html.escape(rel)}: new{flag}</div><div class='row'>"
                     f"<figure><img src='b/{html.escape(rel)}'><figcaption>after</figcaption></figure></div></div>")
    head = (f"<h1>{len(changed)} changed, {len(added)} new: {html.escape(prev_name)} to {html.escape(this_name)}</h1>"
            + (f"<p class='own'>--first matched no frame: {html.escape(', '.join(first_missing))}</p>" if first_missing else ""))
    return ("<!doctype html><meta charset='utf-8'><title>Changes</title><style>"
            "body{font:13px system-ui,sans-serif;margin:16px;background:#fff;color:#222;width:1568px}h1{font-size:18px}"
            ".grid{display:grid;grid-template-columns:repeat(var(--per,2),1fr);gap:14px}"
            ".card{border:1px solid #ddd;padding:6px}.cap{font-size:12px;margin-bottom:4px;word-break:break-all}"
            ".own{color:#b3261e}.row{display:grid;grid-template-columns:repeat(3,1fr);gap:4px;align-items:start}"
            "figure{margin:0}img{width:100%;display:block;border:1px solid #ccc}figcaption{font-size:11px;color:#555}"
            f"</style><body style='--per:{per_row}'>{head}<div class='grid'>{''.join(cards)}</div>")


def run(prev, this, first, width, pixel, frac, channel, leave_out=(), leave_unknown=False):
    from playwright.sync_api import sync_playwright

    a_all, b_all = shots(prev), shots(this)
    paired, added, removed = pair_up(a_all, b_all)
    # T295: the pairs left out (a side outside the public areas, a --leave-out name, an unknown side with --leave-unknown)
    # are in none of the lists below and not on the sheet; --first is matched against what is left.
    (paired, added, removed), left_out, unknown, leave_missing = place_filter(prev, this, paired, added, removed,
                                                                               leave_out, leave_unknown)
    gone_a = {e.get("was", e["path"]) for e in left_out}
    gone_b = {e["path"] for e in left_out}
    a_shots = [r for r in a_all if r not in gone_a]
    b_shots = [r for r in b_all if r not in gone_b]
    pages = {"/index.html": "<!doctype html><title>diff</title>"}

    def serve(route):
        path = route.request.url[len(ORIGIN):].split("?")[0]
        from urllib.parse import unquote
        path = unquote(path)
        if path in pages:
            return route.fulfill(status=200, content_type="text/html", body=pages[path])
        for prefix, root in (("/a/", prev), ("/b/", this)):
            if path.startswith(prefix):
                f = os.path.normpath(os.path.join(root, path[len(prefix):]))
                if f.startswith(os.path.normpath(root) + os.sep) and os.path.isfile(f):
                    return route.fulfill(status=200, content_type="image/png", path=f)
        return route.fulfill(status=404, body="")

    fractions, changed, timing, heats = {}, [], [], {}
    sheet = {}
    with sync_playwright() as p:
        launch = {"headless": True}
        if channel:
            launch["channel"] = channel
        b = p.chromium.launch(**launch)
        try:
            def new_ctx(scale=1.0):
                c = b.new_context(viewport={"width": 1600, "height": 900}, device_scale_factor=scale)
                c.route(ORIGIN + "/**", serve)
                return c

            ctx = new_ctx()
            page = ctx.new_page()
            page.goto(ORIGIN + "/index.html")
            for was, rel in paired:
                r = compare(page, was, rel, width, pixel)
                f = round(r["fraction"], 5)
                fractions[rel] = f
                if not (r.get("sizeChanged") or f > frac):
                    continue
                entry = {"path": rel, "fraction": f, **({"was": was} if was != rel else {})}
                if r.get("sizeChanged"):
                    entry.update(sizeChanged=True, before=r["size"]["a"], after=r["size"]["b"])
                if in_flight(rel):
                    timing.append({**entry, "why": "in flight (-first)"})
                    continue
                hits = matches_first(rel, first)
                if hits:
                    entry["first"] = hits
                if not r.get("sizeChanged"):
                    heats[rel] = compare(page, was, rel, width, pixel, heat=True).get("heat")
                changed.append(entry)
            # The owner's screens first (in --first order), then the rest in the folder's order; T288: the same among the
            # added shots (a wave's own new step has no namesake in the previous sweep).
            key = first_order(first)
            changed.sort(key=lambda c: key(c.get("first", [])))
            added = sorted(added, key=lambda r: key(matches_first(r, first)))
            outcome = first_outcome(first, a_shots, b_shots, changed, added)
            png = os.path.join(this, "changes.png")
            # T295: the last word before the sheet: no frame on it may read outside the public areas.
            on_sheet = ([(prev, c.get("was", c["path"])) for c in changed] + [(this, c["path"]) for c in changed]
                        + [(this, r) for r in added])
            if any(is_device(r) and read_place(d, r)[0] == "outside" for d, r in on_sheet):
                if os.path.exists(png):
                    os.remove(png)
                raise OutsideOnSheet("a frame on the sheet is outside the public areas: no changes.png written")
            if changed or added:
                sheet = render_sheet(new_ctx, pages, os.path.basename(os.path.normpath(prev)), os.path.basename(os.path.normpath(this)),
                                     changed, added, heats, png, outcome)
            elif os.path.exists(png):
                os.remove(png)  # a sheet from an earlier run against another folder would mislead
        finally:
            b.close()
    return {"prev": prev, "this": this, "thresholds": {"width": width, "pixel": pixel, "frac": frac,
                                                        "statusBar": STATUS_BAR, "timingByName": "-first"},
            "paired": len(paired), "renumbered": [{"was": a, "now": b} for a, b in paired if a != b], "count": len(changed), "changed": changed, "timing": timing,
            "added": added, "removed": removed, "first": first, "firstMissing": outcome["missing"],
            "firstUnchanged": outcome["unchanged"], "firstAdded": outcome["added"], "sheet": sheet, "fractions": fractions,
            "leftOut": left_out, "unknown": unknown, "leaveOut": list(leave_out), "leaveUnknown": leave_unknown,
            "leaveOutMissing": leave_missing, "places": place_counts(this, b_all)}


def render_sheet(new_ctx, pages, prev_name, this_name, changed, added, heats, png, outcome=None):
    """changes.png, fitted under the capture limit: more pairs per row first, then a device scale below 1."""
    info = {}
    outcome = outcome or {}
    ctx = new_ctx()
    for per in (2, 3, 4):
        pages["/changes.html"] = sheet_html(prev_name, this_name, changed, added, heats, per, outcome.get("added", ()),
                                            outcome.get("missing", ()))
        page = ctx.new_page()
        page.goto(ORIGIN + "/changes.html", wait_until="load")
        h = page.evaluate("() => document.documentElement.scrollHeight")
        info = {"perRow": per, "pageHeight": h, "scale": 1.0}
        if h <= SHEET_MAX_PX or per == 4:
            break
        page.close()
    if info["pageHeight"] > SHEET_MAX_PX:
        info["scale"] = int(SHEET_MAX_PX / info["pageHeight"] * 1000) / 1000
        page.close()
        ctx.close()
        ctx = new_ctx(info["scale"])
        page = ctx.new_page()
        page.goto(ORIGIN + "/changes.html", wait_until="load")
    page.screenshot(path=png, full_page=True)
    ctx.close()
    w, h = png_size(png)
    info.update(width=w, height=h, cut=h is not None and h >= 16384)
    return info


def png_size(path):
    try:
        with open(path, "rb") as f:
            head = f.read(24)
        if head[:8] == b"\x89PNG\r\n\x1a\n":
            return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")
    except OSError:
        pass
    return None, None


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("prev", help="the previous sweep folder")
    ap.add_argument("this", help="this sweep folder (changes.png and changes.json are written here)")
    ap.add_argument("--first", default="", help="screen names the merge bodies flag for the owner, comma-separated")
    ap.add_argument("--leave-out", default="", help="T295: frames to leave off the sheet, comma-separated names matched "
                    "against the path as --first is")
    ap.add_argument("--leave-unknown", action="store_true", help="T295: leave out a device pair with a side that has no "
                    "place sidecar (a sweep from before T295)")
    ap.add_argument("--channel", default="chrome", help="playwright browser channel; '' for bundled chromium")
    ap.add_argument("--width", type=int, default=DEFAULTS["width"], help="the width both images are compared at")
    ap.add_argument("--pixel", type=int, default=DEFAULTS["pixel"], help="a channel difference (of 255) that counts")
    ap.add_argument("--frac", type=float, default=DEFAULTS["frac"], help="the share of differing pixels that changes a pair")
    a = ap.parse_args()
    prev, this = os.path.abspath(a.prev), os.path.abspath(a.this)
    for d in (prev, this):
        if not os.path.isdir(d):
            print(json.dumps({"error": f"not a folder: {d}"}))
            return 2
    first = [n.strip() for n in a.first.split(",") if n.strip()]
    leave_out = [n.strip() for n in a.leave_out.split(",") if n.strip()]
    t0 = time.time()
    try:
        report = run(prev, this, first, a.width, a.pixel, a.frac, a.channel, leave_out, a.leave_unknown)
    except Exception as e:
        note = f"error: {str(e)[:300]}"
        fold_into_summary(this, 2, time.time() - t0, note, changes=False)
        print(json.dumps({"error": str(e)[:500]}))
        return 2
    with open(os.path.join(this, "changes.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    missing = report["firstMissing"]
    code = 1 if missing else 0
    # T295: what was left out, said without a place.
    why = {}
    for e in report["leftOut"]:
        why[e["why"]] = why.get(e["why"], 0) + 1
    words = {"outside": "a side outside the public areas", "named": "named by --leave-out",
             "unknown": "a side unknown (--leave-unknown)"}
    for k, n in why.items():
        print(f"[sweep-diff] {n} left out: {words[k]}", file=sys.stderr)
    if report["unknown"]:
        print(f"[sweep-diff] {len(report['unknown'])} kept with a side unknown (no place sidecar)", file=sys.stderr)
    if report["leaveOutMissing"]:
        print(f"[sweep-diff] --leave-out matched no frame: {', '.join(report['leaveOutMissing'])}", file=sys.stderr)
    note = f"--first matched no frame: {', '.join(missing)}" if missing else ""
    if missing:
        print(f"[sweep-diff] {note}", file=sys.stderr)
    entry = fold_into_summary(this, code, time.time() - t0, note)
    print(json.dumps(entry or {k: report[k] for k in ("paired", "count", "added", "removed", "firstMissing", "firstUnchanged",
                                                      "leftOut", "unknown")}, indent=2))
    return code


# T288: the diff's own line in steps.tsv and entry in summary.json. tools/sweep.ps1 appends the line after a run; a rerun of
# the diff alone rewrites a line already there, so a corrected --first clears the red in both files.
STEP = "diff"


def rewrite_step_line(this, code, seconds, note):
    """Replaces the `diff` lines of steps.tsv with this run's, when there is one (never appends: that is sweep.ps1's)."""
    path = os.path.join(this, "steps.tsv")
    try:
        with open(path, encoding="utf-8-sig") as f:
            lines = f.read().splitlines()
    except OSError:
        return False
    if not any(l.split("\t", 1)[0] == STEP for l in lines):
        return False
    clean = re.sub(r"[\t\r\n]", " ", note)
    line = f"{STEP}\t{code}\t{round(seconds, 1)}\t{clean}"
    out = [line if l.split("\t", 1)[0] == STEP else l for l in lines]
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("".join(l + "\r\n" for l in out))
    return True


def fold_into_summary(this, code=0, seconds=0.0, note="", changes=True):
    """summary.json's `changes` entry, through sweep-summary.py's own reader, when the summary is there; T288: with the
    --first outcome (`firstMissing`, `firstUnchanged`, `firstAdded`), and the diff's own part (`parts.diff`) failed with the
    summary's result when a --first name matched nothing or the diff failed, cleared again by a run that passes."""
    path = os.path.join(this, "summary.json")
    spec = importlib.util.spec_from_file_location("sweep_summary", os.path.join(HERE, "sweep-summary.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    entry = mod.changes(this) if changes else None
    if entry:
        r = mod.load(os.path.join(this, "changes.json")) or {}
        entry.update({k: r.get(k) or [] for k in ("firstMissing", "firstUnchanged", "firstAdded")})
    rewrite_step_line(this, code, seconds, note)
    s = mod.load(path)
    if isinstance(s, dict):
        if entry:
            s["changes"] = entry
        failed = [f for f in s.get("failed") or [] if f != STEP]
        parts = s.setdefault("parts", {})
        if code:
            failed.append(STEP)
        parts[STEP] = {"ok": not code, "seconds": round(seconds, 1), **({"exit": str(code)} if code else {}),
                       **({"note": note} if note else {})}
        s["failed"] = failed
        s["ok"] = not failed
        with open(path, "w", encoding="utf-8") as f:
            json.dump(s, f, indent=2)
    return entry


if __name__ == "__main__":
    sys.exit(main())
