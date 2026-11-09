"""The before-and-after sheet (T259): every screenshot of a sweep against the previous sweep's, by name.

    python -I tools/sweep-diff.py <previous sweep folder> <this sweep folder> [--first <screen names>]
        [--channel chrome|""] [--frac 0.004] [--pixel 40] [--width 270]

Pairs each PNG of the two folders by its path relative to the folder (the device tour's views, the ui/<part>/ sets of
the release parts, first-run and its direct run, the release smoke views, the world tour's renders, the demo and live
screenshots). Composites and server data are left out: sheet.png, changes*.png, the tour contact sheets
(*-sheet.png, tour/contact-sheet.png) and anything under an `out/` folder (the sweep servers' sprites).

The tolerance for timing (two sweeps of the same build differ by the tiles that had landed, a label placed a frame
later, the clock): both images are drawn at --width pixels wide (a 1080 px screenshot shrinks four times, an average
over 4 by 4 pixels) and blurred by one pixel; a pixel differs when a colour channel moved by more than --pixel (of
255); a pair is changed when the fraction of differing pixels passes --frac. Two exclusions by name:
- the clock: the status bar (the top STATUS_BAR of the height) of the device screenshots (device/, ui/, first-run/,
  release/) is masked, because the full-screen captures show the clock and the notification icons there;
- the camera in flight: a shot whose name ends in `-first` (01-map-first, 10-import-map-first, ...) is taken by the
  tools before the map settles, on purpose; it is compared and listed, but as `timing`, never counted as changed.
Pairs of different sizes are changed (fraction 1). Shots in only one folder are listed as added or removed.

Writes into <this sweep folder>:
- changes.png: the changed pairs side by side (before, after, and the after with the differing pixels in red),
  then the added shots, and nothing else; the pairs named by --first (screen names the merge bodies flag for the
  owner, matched case-insensitively against the path, `-` and space alike) come first and are marked;
- changes.json: the count, the thresholds, every changed pair with its fraction, the timing pairs, the added and
  removed shots, and every pair's fraction (`fractions`);
and, when summary.json is there, its `changes` entry (sweep-summary.py's `changes()` reads the same file, so a
rerun of the summary keeps it). The image work runs in Playwright's Chromium canvas (the machine has no Pillow;
Playwright is already needed by the sheet). Prints changes.json's summary; exit 0 whatever changed, 2 on an error.
"""
import argparse
import fnmatch
import html
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ORIGIN = "http://sweep-diff.invalid"

# The share of the height masked at the top of a device screenshot: Android's status bar is 24 dp, 63 px of 2400 on
# the sweep's 1080 x 2400 phone (2.6%); landscape is 63 of 1080 (5.8%), so the mask is the larger of the two shares
# applied per orientation.
STATUS_BAR = {"portrait": 0.03, "landscape": 0.06}
DEVICE_PREFIXES = ("device/", "ui/", "first-run/", "release/")
SKIP = ("sheet.png", "changes.png", "changes-*.png", "*-sheet.png", "tour/contact-sheet.png")
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


def in_flight(rel):
    """The tools take these before the map settles (the camera in flight, tiles landing)."""
    return os.path.splitext(os.path.basename(rel))[0].endswith("-first")


def matches_first(rel, names):
    key = rel.lower().replace(" ", "-")
    return [n for n in names if n and n.lower().replace(" ", "-") in key]


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


def sheet_html(prev_name, this_name, changed, added, heats, per_row):
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
        cards.append(f"<div class='card'><div class='cap'>{html.escape(rel)}: new</div><div class='row'>"
                     f"<figure><img src='b/{html.escape(rel)}'><figcaption>after</figcaption></figure></div></div>")
    head = (f"<h1>{len(changed)} changed, {len(added)} new: {html.escape(prev_name)} to {html.escape(this_name)}</h1>")
    return ("<!doctype html><meta charset='utf-8'><title>Changes</title><style>"
            "body{font:13px system-ui,sans-serif;margin:16px;background:#fff;color:#222;width:1568px}h1{font-size:18px}"
            ".grid{display:grid;grid-template-columns:repeat(var(--per,2),1fr);gap:14px}"
            ".card{border:1px solid #ddd;padding:6px}.cap{font-size:12px;margin-bottom:4px;word-break:break-all}"
            ".own{color:#b3261e}.row{display:grid;grid-template-columns:repeat(3,1fr);gap:4px;align-items:start}"
            "figure{margin:0}img{width:100%;display:block;border:1px solid #ccc}figcaption{font-size:11px;color:#555}"
            f"</style><body style='--per:{per_row}'>{head}<div class='grid'>{''.join(cards)}</div>")


def run(prev, this, first, width, pixel, frac, channel):
    from playwright.sync_api import sync_playwright

    a_shots, b_shots = shots(prev), shots(this)
    paired, added, removed = pair_up(a_shots, b_shots)
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
            # The owner's screens first (in --first order), then the rest in the folder's order.
            order = {n.lower(): i for i, n in enumerate(first)}
            changed.sort(key=lambda c: min((order[h.lower()] for h in c.get("first", [])), default=len(order)))
            png = os.path.join(this, "changes.png")
            if changed or added:
                sheet = render_sheet(new_ctx, pages, os.path.basename(os.path.normpath(prev)), os.path.basename(os.path.normpath(this)),
                                     changed, added, heats, png)
            elif os.path.exists(png):
                os.remove(png)  # a sheet from an earlier run against another folder would mislead
        finally:
            b.close()
    return {"prev": prev, "this": this, "thresholds": {"width": width, "pixel": pixel, "frac": frac,
                                                        "statusBar": STATUS_BAR, "timingByName": "-first"},
            "paired": len(paired), "renumbered": [{"was": a, "now": b} for a, b in paired if a != b], "count": len(changed), "changed": changed, "timing": timing,
            "added": added, "removed": removed, "first": first, "sheet": sheet, "fractions": fractions}


def render_sheet(new_ctx, pages, prev_name, this_name, changed, added, heats, png):
    """changes.png, fitted under the capture limit: more pairs per row first, then a device scale below 1."""
    info = {}
    ctx = new_ctx()
    for per in (2, 3, 4):
        pages["/changes.html"] = sheet_html(prev_name, this_name, changed, added, heats, per)
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
    try:
        report = run(prev, this, first, a.width, a.pixel, a.frac, a.channel)
    except Exception as e:
        print(json.dumps({"error": str(e)[:500]}))
        return 2
    with open(os.path.join(this, "changes.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    entry = fold_into_summary(this)
    print(json.dumps(entry or {k: report[k] for k in ("paired", "count", "added", "removed")}, indent=2))
    return 0


def fold_into_summary(this):
    """summary.json's `changes` entry, through sweep-summary.py's own reader, when the summary is there."""
    path = os.path.join(this, "summary.json")
    spec = importlib.util.spec_from_file_location("sweep_summary", os.path.join(HERE, "sweep-summary.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    entry = mod.changes(this)
    s = mod.load(path)
    if isinstance(s, dict) and entry:
        s["changes"] = entry
        with open(path, "w", encoding="utf-8") as f:
            json.dump(s, f, indent=2)
    return entry


if __name__ == "__main__":
    sys.exit(main())
